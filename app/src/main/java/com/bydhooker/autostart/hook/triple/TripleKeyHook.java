package com.bydhooker.autostart.hook.triple;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * 电源键三击 Hook（注入 **system_server**，`docs/32` 的可运行实现）。
 *
 * 【为什么需要它】HyperOS 中国区把电源键多击上限**锁死在 2**，三击在原生配置下物理上不存在：
 * ① 不 Hook {@code getMiuiMaxMultiPressCount()} 返回 3 ⇒ 框架永远数不到第三击；
 * ② 不 Hook {@code triggerTripleClick()} ⇒ 即使数到了也是空实现、什么都不做。
 *
 * 【⚠️ 本类必须保持「零项目依赖」】
 * 它运行在 **system_server** 里。那个进程没有本项目的 Application、没有 AndroidX/Compose、
 * 没有 miuix。**只能** import：JDK + `android.*` + `io.github.libxposed.*`。
 * 一旦引用 `EventLog` / `ConfigStore` / `BridgeContract` 等项目类，会 ClassNotFound，
 * 而 system_server 崩溃会导致系统重启 —— 这是本类最硬的一条约束。
 *
 * 【动作落点】`docs/32` §5.2 推荐的落点 ②：`ContentResolver.call()` 到模块 App 的 Provider。
 * system_server 是系统进程，不受 HyperOS「关联启动」限制（对比宿主 App 会被
 * `WakePathChecker: security reject start` 拒绝），所以它能把模块**冷启动**起来。
 *
 * 【安全约束（逐条来自 `docs/32` §6）】
 * · 默认关：开关文件不存在 ⇒ 一律 `chain.proceed()`，系统行为与未装模块完全一致
 * · 防重复：3 s 去重
 * · 不崩系统：所有 Hook 全 try/catch，异常一律回落原方法
 * · 不阻塞按键线程：网络/跨进程一律丢子线程；日志 5 s 节流
 * · 已知副作用：开关开时双击 mi_pay 会延后一个多击窗口（几百毫秒）才响应（`docs/32` §6）
 */
public final class TripleKeyHook {

    private static final String TAG = "BYDHK_TRIPLE";

    /** 目标类（反汇编实证：`PowerKeyRule.smali:1419`，`docs/32` §3.2）。 */
    private static final String POWER_RULE_CLASS =
            "com.android.server.input.shortcut.singlekeyrule.PowerKeyRule";

    /** system_server 侧日志（`/data/system` 属 system uid，system_server 可写）。 */
    private static final String TRIPLE_LOG = "/data/system/bydhk-triple.log";

    /** 开关文件：root 写、system_server 读（避开 SettingsProvider 缓存与跨进程延迟）。 */
    private static final String SWITCH_FILE = "/data/system/bydhk_triple_on";

    /**
     * 就绪标记：Hook 全部挂上后写 "1"。
     *
     * 模块 App（设置页）读它来判断「三击能力是否可用」——
     * 跨进程无法直接查询 Hook 状态，用一个文件最省事；模块有 root，读 `/data/system` 没问题。
     */
    private static final String READY_FILE = "/data/system/bydhk-triple.ready";

    /** Settings.Global 键（与文件双判据，任一为 1 即启用；`docs/32` §4.5）。 */
    private static final String GLOBAL_TRIPLE_ON = "bydhk_triple_on";

    /**
     * 执行层工作目录（DE 存储）。
     *
     * 两个用途：① chcon 过的 `libwbsk_crypto_tool.so` 放这里；② 作为
     * `DexClassLoader` 的优化输出目录（API 26+ 实际走内存，给个可写路径即可）。
     */
    private static final String DE_DIR = "/data/system/bydhk";

    // ⚠️ 架构 C 之后本类**不再**通过 ContentProvider 通知模块 App（那条链要跨进程
    // 唤醒一个后台 App，正是「模块被杀就没人执行」的根源）。原来的
    // AUTHORITY / METHOD_TRIPLE_CLICK / EXTRA_TS 三个常量已随之删除。

    /** 日志上限：超过就清空重写（`docs/32` §2.4）。 */
    private static final long MAX_LOG_BYTES = 128 * 1024L;

    /** 三击去重窗口。 */
    private static final long DEDUPE_MS = 3000L;

    /**
     * Hook 实现版本：**改了本类行为就 +1**。
     *
     * 【为什么需要】本类跑在 system_server 里，APK 覆盖安装**不会**让新代码生效 ——
     * 必须重启手机（system_server 只在启动时加载模块）。改完之后最常问的一句就是
     * 「现在跑的是新代码还是旧的」，靠 `HOOKS_OK` 里的这个数字一眼确认。
     * 14 = 2026-11-13 加三击短震（前一版 13 无震动）。
     * 19 = 2026-11-24 三击动作改由 `triggerMultiPress(3)` 接管（见 {@link MultiPressHooker}）。
     * 20 = 2026-11-25 日志追加失败自愈（重建文件）+ 振动获取三路径与分级诊断。
     */
    private static final int HOOK_VER = 20;

    /**
     * 命中三击时的短震时长（ms）。
     *
     * 50 ms 是「轻点一下」的量级：够让手心/口袋感知到，又不至于像来电那样突兀。
     * 三击是明确的手动操作，不需要长震；太长反而会让用户以为出错了。
     */
    private static final long VIBRATE_MS = 50L;

    /** MAXCOUNT 日志节流：该方法在按键路径上被频繁调用，不能每次都写文件。 */
    private static final long MAXCOUNT_LOG_THROTTLE_MS = 5000L;

    /** MULTI（非三击）日志节流：单击/双击路径同样频繁。 */
    private static final long MULTI_LOG_THROTTLE_MS = 5000L;

    private static final int RETRY_TIMES = 12;
    private static final long RETRY_INTERVAL_MS = 3000L;

    /** A′：attachContext(Context) 是否注入成功（失败会在下一次三击重试）。 */
    private static volatile boolean sCtxAttached = false;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile ClassLoader sysClassLoader = null;
    private static volatile long lastTripleTs = 0L;
    private static volatile long lastMaxCountLogTs = 0L;
    private static volatile long lastMultiLogTs = 0L;
    private static volatile boolean installing = false;
    private static volatile boolean installed = false;

    /**
     * 把 `hook(Method).intercept(Hooker)` 委托回调用方（`XposedModule` 的实例方法）。
     *
     * 本类是纯静态工具，拿不到 `XposedModule` 实例，所以由 `XposedModuleEntry` 传进来。
     * 这样能保证本类**不依赖任何项目类**（system_server 里加载安全）。
     */
    public interface HookBinder {
        void bind(Method method, XposedInterface.Hooker hooker) throws Throwable;
    }

    private TripleKeyHook() {
    }

    /**
     * 安装三个 Hook（幂等；失败自动重试）。
     *
     * @param cl     注入 system_server 时的 ClassLoader（null 则用当前线程的）
     * @param reason 触发来源，仅用于日志（`onSystemServerStarting` / `onModuleLoaded`）
     */
    public static synchronized void install(final ClassLoader cl, final String reason,
                                            final HookBinder binder) {
        if (installed) {
            return;
        }
        if (cl != null) {
            sysClassLoader = cl;
        }
        if (installing) {
            return;
        }
        installing = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // PowerKeyRule 在 system_server 启动早期可能还没加载 ⇒ 延迟补挂（`docs/32` §4.1）
                    for (int i = 0; i < RETRY_TIMES; i++) {
                        ClassLoader use = sysClassLoader != null
                                ? sysClassLoader
                                : Thread.currentThread().getContextClassLoader();
                        if (installOnce(use, i, binder)) {
                            installed = true;
                            writeReady();
                            tripleLog("TRIPLE|HOOKS_OK|reason=" + reason + "|try=" + (i + 1)
                                    + "|ver=" + HOOK_VER);
                            // 架构 C：开机就把执行层装好（so / 凭据 / 配置），
                            // 免得第一次三击才现加载、白等几百毫秒。
                            initExec();
                            return;
                        }
                        try {
                            Thread.sleep(RETRY_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                    tripleLog("TRIPLE|HOOKS_FAIL|reason=" + reason
                            + "|tries=" + RETRY_TIMES);
                } catch (Throwable th) {
                    tripleLog("TRIPLE|INSTALL_ERR|" + th.getClass().getSimpleName());
                } finally {
                    installing = false;
                }
            }
        }, "bydhk-triple-install");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 执行层装载（架构 C）

    /** 执行层入口类（`com.bydhooker.autostart.exec.InjectableExec`），由 DexClassLoader 加载。 */
    private static volatile Class<?> sExecCls = null;

    /** 入口方法 `onTriple(long)`；null = 还没装好。 */
    private static volatile java.lang.reflect.Method sOnTriple = null;

    /** 初始化是否成功（so / 通道就绪）。 */
    private static volatile boolean sInitOk = false;

    /**
     * 装载并初始化执行层。
     *
     * 【为什么这么绕】system_server 的 classloader 里**没有**本 APK 的任何类
     * （真机实测 `DIRECT|ok=0/4`；LSPosed 只注入入口链，`TripleKeyHook` 注释里的老断言是对的）。
     * 所以只能用 `DexClassLoader` 指向模块 base.apk 的 dex，把执行层整个加载进来。
     * 实测这条路可行（`DEX_OK`，类名 `gs` 即 R8 混淆后的名字）。
     *
     * 【为什么仍然守「零项目依赖】】入口类用**反射**调 ⇒ 本类不需要 import 任何项目类，
     * 也就不会因为模块 APK 升级、类被移除/改名而让 system_server 崩掉。
     * exec 包已加 keep 规则，类名与方法名不会被混淆，反射稳定。
     */
    private static void initExec() {
        try {
            if (sExecCls == null) {
                String apk = findModuleApk();
                if (apk == null) {
                    tripleLog("TRIPLE|EXEC_NOAPK|找不到模块 APK");
                    return;
                }
                ClassLoader cl = new dalvik.system.DexClassLoader(
                        apk, DE_DIR, null, ClassLoader.getSystemClassLoader());
                sExecCls = cl.loadClass("com.bydhooker.autostart.exec.InjectableExec");
                sOnTriple = sExecCls.getMethod("onTriple", long.class);
            }
            Object r = sExecCls.getMethod("init", String.class).invoke(null, (Object) DE_DIR);
            sInitOk = Boolean.TRUE.equals(r);
            tripleLog("TRIPLE|EXEC_INIT|ok=" + r + "|dir=" + DE_DIR);
        } catch (Throwable t) {
            sInitOk = false;
            tripleLog("TRIPLE|EXEC_INIT_ERR|" + t.getClass().getSimpleName()
                    + "|" + t.getMessage());
        }
    }

    /** 定位模块 APK（Android 11+ 是 `/data/app/~~x==/<pkg>-y==/base.apk`，所以要递归）。 */
    private static String findModuleApk() {
        try {
            File[] top = new File("/data/app").listFiles();
            if (top != null) {
                for (File d : top) {
                    String hit = scanApk(d, 0);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String scanApk(File dir, int depth) {
        if (dir == null || depth > 3) {
            return null;
        }
        if (dir.getName().contains("com.bydhooker.autostart")) {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().endsWith(".apk")) {
                        return f.getAbsolutePath();
                    }
                }
                for (File f : fs) {
                    if (f.isDirectory()) {
                        String hit = scanApk(f, depth + 1);
                        if (hit != null) {
                            return hit;
                        }
                    }
                }
            }
            return null;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return null;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                String hit = scanApk(f, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 尝试挂一轮；三个 Hook 各自 try/catch，一个失败不影响其余（`docs/32` §4.1）。 */
    private static boolean installOnce(ClassLoader cl, int round, HookBinder binder) {
        Class<?> c;
        try {
            c = Class.forName(POWER_RULE_CLASS, false, cl);
        } catch (Throwable t) {
            return false;
        }
        boolean any = false;

        // Hook A：把上限提到 3（**核心，不能省**）
        try {
            Method mc = c.getDeclaredMethod("getMiuiMaxMultiPressCount");
            mc.setAccessible(true);
            binder.bind(mc, new MaxCountHooker());
            any = true;
            tripleLog("TRIPLE|HOOK_MAXCOUNT_OK|round=" + (round + 1));
        } catch (Throwable t) {
            tripleLog("TRIPLE|HOOK_MAXCOUNT_ERR|" + t.getClass().getSimpleName());
        }

        // Hook B：动作注入点
        try {
            Method m = c.getDeclaredMethod("triggerTripleClick");
            m.setAccessible(true);
            binder.bind(m, new TripleClickHooker());
            any = true;
            tripleLog("TRIPLE|HOOK_CLICK_OK|round=" + (round + 1));
        } catch (Throwable t) {
            tripleLog("TRIPLE|HOOK_CLICK_ERR|" + t.getClass().getSimpleName());
        }

        // Hook C：三击落点（v19 起在这里就地接管，见 MultiPressHooker 注释）
        try {
            Method mm = c.getDeclaredMethod("triggerMultiPress", int.class);
            mm.setAccessible(true);
            binder.bind(mm, new MultiPressHooker());
            any = true;
            tripleLog("TRIPLE|HOOK_PROBE_OK|round=" + (round + 1));
        } catch (Throwable t) {
            tripleLog("TRIPLE|HOOK_PROBE_ERR|" + t.getClass().getSimpleName());
        }

        // 两个 Hook 都挂上才算成功（Probe 是诊断用，缺了不阻塞）
        return any;
    }

    // ---------------------------------------------------------------- 三个 Hooker

    /** Hook A：`getMiuiMaxMultiPressCount()` —— 开关开时返回 3。 */
    private static final class MaxCountHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object orig;
            try {
                // ⚠️ 只能调一次：取原生值留证，原方法仅读 map、无副作用（`docs/32` §4.2）
                orig = chain.proceed();
            } catch (Throwable t) {
                tripleLog("TRIPLE|MAXCOUNT_PROCEED_ERR|" + t.getClass().getSimpleName());
                return Integer.valueOf(1);
            }
            int origVal = (orig instanceof Integer) ? ((Integer) orig).intValue() : -1;
            boolean on = tripleSwitchOn(null);
            long now = System.currentTimeMillis();
            if (now - lastMaxCountLogTs > MAXCOUNT_LOG_THROTTLE_MS) {
                lastMaxCountLogTs = now;
                tripleLog("TRIPLE|MAXCOUNT|orig=" + origVal
                        + "|ret=" + (on ? 3 : origVal) + "|sw=" + on);
            }
            // 开关关时必须原样返回，否则禁用状态下也会改变双击延迟
            if (!on) {
                return orig;
            }
            return Integer.valueOf(3);
        }
    }

    /** Hook B：`triggerTripleClick()` —— 开关开时接管动作并返回 TRUE。 */
    private static final class TripleClickHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return onTripleHit(chain, "click");
        }
    }

    /**
     * Hook C：`triggerMultiPress(int)` —— **三击的真正落点**（v19 起）。
     *
     * 【为什么从「只读观测」改成「接管」】2026-11-24 一位 HyperOS3（SDK 36）用户日志实证：
     * `MAXCOUNT|orig=1|ret=3` 与 `MULTI|count=3` 都出现，但 `HIT` 一行都没有 ——
     * 说明 `triggerMultiPress(3)` 进了 Hook，而它内部的 `triggerTripleClick()` 调用
     * **没有走方法入口**（ART 把这种同类私有小方法内联进了调用方；Hook 外层方法后，
     * `chain.proceed()` 执行的是原编译代码副本，内层 Hook 被绕过）。
     *
     * 所以这里不再依赖 Hook B：count == 3 时**就地接管**，直接 `return TRUE`
     * （= `triggerMultiPress` 返回 true ⇒ `onMiuiMultiPress` 不再回落
     * `OriginalPowerKeyRuleBridge`），其余 count 原样 `proceed()`。
     * Hook B 保留作冗余：真机若走的是 B 的入口，两者共用 3 s 去重，不会双触发。
     */
    private static final class MultiPressHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            int count = -1;
            try {
                Object a = chain.getArg(0);
                if (a instanceof Integer) {
                    count = ((Integer) a).intValue();
                }
            } catch (Throwable ignored) {
            }
            if (count == 3) {
                return onTripleHit(chain, "multi");
            }
            // 其余击数只观测（节流），保留「系统把它判成了几击」这条最便宜的诊断
            try {
                long now = System.currentTimeMillis();
                if (now - lastMultiLogTs > MULTI_LOG_THROTTLE_MS) {
                    lastMultiLogTs = now;
                    tripleLog("TRIPLE|MULTI|count=" + count + "|sw=" + tripleSwitchOn(null));
                }
            } catch (Throwable ignored) {
                // 观测失败绝不影响原逻辑
            }
            return chain.proceed();
        }
    }

    /**
     * 三击命中后的统一处理（Hook B / C 共用）。
     *
     * 返回 `Boolean.TRUE` = 吞掉这次三击，不回落原逻辑；返回 `chain.proceed()` = 交还原生。
     * 开关关、取不到 Context、异常 —— 一律回落，绝不改变未启用时的系统行为。
     */
    private static Object onTripleHit(XposedInterface.Chain chain, String via) throws Throwable {
        try {
            Object thiz = chain.getThisObject();
            Context ctx = readPowerContext(thiz);
            boolean on = tripleSwitchOn(ctx);
            long now = System.currentTimeMillis();
            tripleLog("TRIPLE|HIT|via=" + via + "|on=" + on + "|ctx=" + (ctx != null) + "|ts=" + now);
            if (!on) {
                return chain.proceed();
            }
            if (now - lastTripleTs < DEDUPE_MS) {
                tripleLog("TRIPLE|DEDUPE|via=" + via + "|ts=" + now);
                return Boolean.TRUE;
            }
            lastTripleTs = now;
            if (ctx == null) {
                tripleLog("TRIPLE|NO_CONTEXT|fallback-original");
                return chain.proceed();
            }
            startFire(ctx, now);
            // 返回 TRUE：跳过 OriginalPowerKeyRuleBridge ⇒ 三击不会落回双击分支 / mi_pay
            return Boolean.TRUE;
        } catch (Throwable t) {
            tripleLog("TRIPLE|INTERCEPT_ERR|" + via + "|" + t.getClass().getSimpleName());
            try {
                return chain.proceed();
            } catch (Throwable ignored) {
                return Boolean.FALSE;
            }
        }
    }

    /** 动作下发：先短震（用户回执，必须立刻到），再跨进程执行（可能几百毫秒）。 */
    private static void startFire(final Context ctx, final long ts) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                vibrateShort(ctx);
                notifyModule(ctx, ts);
            }
        }, "bydhk-triple-fire");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 短震一下：告诉用户「这次三击已经被识别了」（2026-11-13 00:0x 加）。
     *
     * 【为什么需要】三击 → 车门真的解锁有 1~2 s 的物理延迟（云端→车端）。
     * 这段时间手机没有任何反馈，用户会怀疑「是不是没击到」并再击一次 ——
     * 而第二次会被 3 s 去重直接吃掉，体验上就是「反应特别慢」。
     * 一记 50 ms 的短震把这个空档填上，感知滞后基本消失。
     *
     * 【为什么在子线程】`vibrate()` 是到 VibratorService 的**同步** binder 调用。
     * 万一它卡住，按键线程被拖住 = 电源键整个失响应。放进子线程后，
     * 最坏情况也只是少一次震动。调用点在 `notifyModule` 之前，震动先出、跨进程后走。
     *
     * 【为什么不用 haptic feedback】`performHapticFeedback` 需要 View/窗口，
     * system_server 没有；而且它受「触摸震动」开关影响，用户关了就不震。
     * 这里是功能回执，用独立 Vibrator 更可控。
     */
    private static void vibrateShort(Context ctx) {
        if (ctx == null) {
            tripleLog("TRIPLE|VIBRATE|ctx=null");
            return;
        }
        try {
            android.os.Vibrator v = null;
            String via = "none";
            // Android 12+ 用 VibratorManager（VIBRATOR_SERVICE 已废弃）
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Object vm = null;
                try {
                    vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                } catch (Throwable ignored) {
                }
                if (vm instanceof android.os.VibratorManager) {
                    try {
                        v = ((android.os.VibratorManager) vm).getDefaultVibrator();
                        via = "manager";
                    } catch (Throwable t) {
                        tripleLog("TRIPLE|VIBRATE_ERR|getDefaultVibrator|"
                                + t.getClass().getSimpleName());
                    }
                } else {
                    // v3.3.5 诊断：VibratorManager 拿不到时把实际拿到的类型记下来
                    tripleLog("TRIPLE|VIBRATE|vm=" + (vm == null ? "null" : vm.getClass().getName()));
                }
            }
            if (v == null) {
                Object o = null;
                try {
                    o = ctx.getSystemService(Context.VIBRATOR_SERVICE);
                } catch (Throwable ignored) {
                }
                if (o instanceof android.os.Vibrator) {
                    v = (android.os.Vibrator) o;
                    via = "legacy";
                }
            }
            if (v == null) {
                // 最后兜底：泛型查找（个别 ROM 的字符串键返回 null，但按类型能拿到）
                try {
                    Object o2 = ctx.getSystemService(android.os.Vibrator.class);
                    if (o2 instanceof android.os.Vibrator) {
                        v = (android.os.Vibrator) o2;
                        via = "typed";
                    }
                } catch (Throwable ignored) {
                }
            }
            if (v == null) {
                tripleLog("TRIPLE|VIBRATE|svc=null|三条路径均未取到 Vibrator");
                Log.w(TAG, "TRIPLE|VIBRATE|svc=null");
                return;
            }
            boolean has;
            try {
                has = v.hasVibrator();
            } catch (Throwable t) {
                tripleLog("TRIPLE|VIBRATE_ERR|hasVibrator|" + t.getClass().getSimpleName());
                return;
            }
            if (!has) {
                tripleLog("TRIPLE|VIBRATE|no-vibrator|via=" + via);
                return;
            }
            // 一次性短震：强度用系统默认（不做自定义 amplitude，避免不同机型表现差异太大）
            v.vibrate(android.os.VibrationEffect.createOneShot(
                    VIBRATE_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            tripleLog("TRIPLE|VIBRATE|ok=" + VIBRATE_MS + "ms|via=" + via);
        } catch (Throwable t) {
            // 震动失败绝不影响三击本身；额外打 logcat，防止日志文件本身不可写时丢证据
            tripleLog("TRIPLE|VIBRATE_ERR|" + t.getClass().getSimpleName() + "|" + t.getMessage());
            Log.e(TAG, "TRIPLE|VIBRATE_ERR", t);
        }
    }

    /**
     * 把「三击」通知给模块 App（`docs/32` §5.2 落点 ②）。
     *
     * 为什么不是广播：HyperOS 冻结后台应用后广播被直接丢弃（`BroadcastQueue: Greezer Denial`），
     * 而 Provider 在「冻结」与「force-stop」两种状态下都能穿透并冷启动接收方（22:1x 三组对照）。
     *
     * 为什么 system_server 能调通而宿主 App 不行：HyperOS 的「关联启动」策略
     * （`WakePathChecker: security reject start`）只限制普通应用；system_server 是系统进程。
     */
    private static void notifyModule(Context ctx, long ts) {
        // 【架构 C · 2026-11-13】就地执行：system_server 自己发 HTTPS，
        // 不再走 ContentProvider 通知模块 App（那条链要跨进程唤醒一个后台 App，
        // 正是「模块被杀就没人执行」的根源）。
        try {
            if (!sInitOk) {
                // 开机时 so 可能还没被模块 App 发布（chcon 是 root 干的），
                // 这里再试一次；失败就明确记下来，不静默。
                initExec();
            }
            if (sOnTriple == null) {
                tripleLog("TRIPLE|EXEC_UNAVAILABLE|执行层入口未就绪");
                return;
            }
            // A′（docs/37 §8.6）：给执行层注入 system_server 的 Context，岛事件发射
            // 需要 ContentResolver。只传框架类型 Context、按方法名反射，
            // 本类不 import 任何项目类 ⇒ 零项目依赖硬约束不破。
            // 不改 initExec() 签名（它在开机装配时还有一个无参调用点）。
            if (!sCtxAttached && ctx != null && sExecCls != null) {
                try {
                    sExecCls.getMethod("attachContext", Context.class).invoke(null, ctx);
                    sCtxAttached = true;
                    tripleLog("TRIPLE|CTX_ATTACH|ok=true");
                } catch (Throwable t) {
                    tripleLog("TRIPLE|CTX_ATTACH_ERR|" + t.getClass().getSimpleName());
                }
            }
            Object r = sOnTriple.invoke(null, Long.valueOf(ts));
            tripleLog("TRIPLE|EXEC|ok=" + r + "|ts=" + ts);
        } catch (Throwable t) {
            tripleLog("TRIPLE|EXEC_ERR|" + t.getClass().getSimpleName() + "|" + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- 开关 / 日志 / 工具

    /**
     * 三击总开关（双判据：Settings.Global 或文件，任一为 1 即启用）。
     *
     * 默认 **false**：首次注入绝不误触发车控（`docs/32` §6 第一条）。
     */
    private static boolean tripleSwitchOn(Context ctx) {
        try {
            if (ctx != null && android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), GLOBAL_TRIPLE_ON, 0) == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(SWITCH_FILE);
            byte[] b = new byte[8];
            int n = in.read(b);
            if (n > 0 && new String(b, 0, n, Charset.forName("UTF-8")).trim().equals("1")) {
                return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** 从 `PowerKeyRule` 实例取 Context：字段 → synthetic bridge → ActivityThread（`docs/32` §4.3）。 */
    private static Context readPowerContext(Object thiz) {
        if (thiz == null) {
            return null;
        }
        try {
            Field f = thiz.getClass().getDeclaredField("mContext");
            f.setAccessible(true);
            Object v = f.get(thiz);
            if (v instanceof Context) {
                return (Context) v;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = thiz.getClass().getDeclaredMethod(
                    "-$$Nest$fgetmContext", thiz.getClass());
            m.setAccessible(true);
            Object v = m.invoke(null, thiz);
            if (v instanceof Context) {
                return (Context) v;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 写就绪标记（供设置页读）。 */
    private static void writeReady() {
        try {
            FileOutputStream fos = new FileOutputStream(READY_FILE, false);
            try {
                fos.write("1".getBytes(Charset.forName("UTF-8")));
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * system_server 侧日志：logcat + `/data/system/bydhk-triple.log`。
     *
     * 超过 128 KB 直接清空重写（不是追加）—— 这个文件在按键路径上会被写，
     * 不能无限增长；丢弃历史比拖慢按键安全。
     */
    private static synchronized void tripleLog(String line) {
        String out = stamp(line);
        Log.i(TAG, out);
        try {
            File f = new File(TRIPLE_LOG);
            if (appendTripleLog(f, out)) {
                return;
            }
            // 【v3.3.5】追加失败必须自愈，否则日志会**永久静默停摆**。
            // 真机实证（2026-11-25 OS3 用户）：文件最后一条停在 11-24 的 HOOKS_OK，
            // 而业务日志显示三击一直在执行 —— 那个文件大概率被 root 侧写成了
            // `root:root 0644`（system_server uid 1000 只能读不能追加）。
            // 删掉重建可以让新 inode 继承 /data/system 的 `system:system`，写入即恢复。
            if (reopenTripleLog(f)) {
                appendTripleLog(f, stamp("TRIPLE|LOG_REOPENED|旧日志不可写，已重建"));
                appendTripleLog(f, out);
            } else {
                // 重建也失败：只留 logcat（此时事件日志另一条链仍可用）
                Log.w(TAG, "TRIPLE|LOG_UNWRITABLE|" + TRIPLE_LOG);
            }
        } catch (Throwable ignored) {
            // 日志失败绝不影响按键路径
        }
    }

    private static String stamp(String line) {
        synchronized (TS) {
            return TS.format(new Date()) + "|" + line;
        }
    }

    /** 追加一行；成功返回 true。任何异常都吞掉并返回 false（绝不阻塞按键路径）。 */
    private static boolean appendTripleLog(File f, String out) {
        try {
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                FileOutputStream clear = new FileOutputStream(f, false);
                try {
                    clear.write("".getBytes(Charset.forName("UTF-8")));
                    clear.flush();
                } finally {
                    clear.close();
                }
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
                w.write(out.replace('\n', ' ').replace('\r', ' '));
                w.write('\n');
                w.flush();
            } finally {
                fos.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删除后重建空文件；返回是否已可写。 */
    private static boolean reopenTripleLog(File f) {
        try {
            if (f.exists() && !f.delete()) {
                return false;
            }
            FileOutputStream fos = new FileOutputStream(f, false);
            try {
                fos.write("".getBytes(Charset.forName("UTF-8")));
                fos.flush();
            } finally {
                fos.close();
            }
            return f.exists();
        } catch (Throwable t) {
            return false;
        }
    }
}

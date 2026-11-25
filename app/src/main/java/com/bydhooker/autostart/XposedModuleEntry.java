package com.bydhooker.autostart;

import com.bydhooker.autostart.bridge.BridgeContract;
import com.bydhooker.autostart.bridge.RemotePrefs;
import com.bydhooker.autostart.core.EventLog;
import com.bydhooker.autostart.core.ModuleBridgeHost;
import com.bydhooker.autostart.core.SelfCheck;
import com.bydhooker.autostart.crypto.WatchRequest;
import com.bydhooker.autostart.crypto.WbKeys;
import com.bydhooker.autostart.crypto.WrapDevice;
import com.bydhooker.autostart.hook.UnlockHooker;

import android.util.Log;
import java.io.File;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * BYDAutoStart 正式模块 · libxposed Modern Xposed API 102 入口（T03 装配版）。
 *
 * 类声明与回调签名逐字取自 {@code offline/api102_signatures.md} 的 javap 输出
 * （**0 处凭记忆**）；Hook 注册按裁定 I（`XposedInterface.Hooker` 非泛型，
 * `HookBuilder.intercept(Hooker)` 返回 HookHandle）。
 *
 * 装配序列（onPackageLoaded 内，全部异常捕获——**绝不崩宿主**）：
 * ① EventLog（宿主 files 目录）→ ② RemotePrefs（只读桥 + Listener）→
 * ③ ModuleBridgeHost（凭据/清除/导出动作）→ ④ wb_keys/表就绪检查 →
 * ⑤ Whitebox（so 三路径，P1-3 ✅）→ ⑥ UnlockHooker 注册（B3 指令即起点）→
 * ⑦ WatchRequest.initStatusSource（StatusPoller 数据源）→ ⑧ SelfCheck（调试项）。
 */
public class XposedModuleEntry extends XposedModule {

    private static final String TAG = "BYDHK";

    /** 作用域唯一目标包（01 §10.1，【实测】已安装）。 */
    private static final String TARGET = "com.byd.aeri.caranywhere";

    /**
     * NFC 方案的第二作用域：小米智能卡（`docs/30` §5.8）。
     *
     * 这里只做「刷到带 AID 的事件 → 转发给模块」，不写存储、不碰 root、不下发车控。
     * 主进程与 `:daemon` 子进程都会被注入，**子进程必须跳过**（否则一次刷卡转发两次）。
     */
    private static final String TARGET_NFC = "com.miui.tsmclient";

    /**
     * 架构 C 超级岛的第三作用域：系统界面（docs/37 §8.11）。
     * 这里放两个 Hook：焦点通知白名单放行 + 岛事件总线；岛通知由 SystemUI 自己 notify。
     */
    private static final String TARGET_SYSTEMUI = "com.android.systemui";

    private static final java.util.concurrent.atomic.AtomicBoolean ASSEMBLED_SYSUI =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean sysuiWhitelistHooked = false;
    private static volatile boolean sysuiBusHooked = false;

    /** Hook 目标（探针 v109 / 02 报告【实测】；OTA 漂移时仅改这里）。 */
    private static final String HOOK_CLASS = "com.byd.base.control.ControlImpl";
    private static final String HOOK_METHOD = "sendRemoteControl";
    private static final String HOOK_PARAM = "com.byd.base.control.IControl";

    /**
     * 当前进程名（T06 跨进程修复）。
     *
     * `PackageLoadedParam` **没有** `getProcessName()`（javap：api102_signatures.md §3.8
     * 仅 `getPackageName/getApplicationInfo/isFirstPackage/getDefaultClassLoader`），
     * 只有 `ModuleLoadedParam` 有。`onModuleLoaded` 在每个进程装配前必回调
     * （logcat 实测三进程均有 MODULE_LOADED），故在此捕获供 `onPackageLoaded` 使用。
     */
    private static volatile String processName = null;

    /** 必须 public 无参构造：java_init.list 反射实例化 + R8 keep 规则依赖。 */
    public XposedModuleEntry() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        StringBuilder sb = new StringBuilder("MODULE_LOADED");
        if (param != null) {
            processName = param.getProcessName();
            sb.append("|processName=").append(param.getProcessName());
            sb.append("|isSystemServer=").append(param.isSystemServer());
        }
        try {
            sb.append("|apiVersion=").append(getApiVersion());
            sb.append("|frameworkName=").append(getFrameworkName());
            sb.append("|frameworkVersion=").append(getFrameworkVersion());
        } catch (Throwable t) {
            sb.append("|frameworkProbeErr=").append(t.getClass().getName());
        }
        Log.i(TAG, sb.toString());

        // system_server：电源键三击 Hook（`docs/32`）
        // ⚠️ 这条路径**绝不能碰 EventLog / ConfigStore 等项目类**（system_server 里没有
        // Application，引用会 CNFE，崩了会导致系统重启）。TripleKeyHook 只依赖 JDK + Xposed。
        try {
            if (param != null && param.isSystemServer()) {
                installTripleHooks(null, "onModuleLoaded");
            }
        } catch (Throwable t) {
            Log.e(TAG, "TRIPLE_ON_MODULE_LOADED_ERR|" + t.getClass().getSimpleName());
        }
    }

    /**
     * 注入 system_server 时的回调（`docs/32` §4.1）。
     *
     * 与 [onModuleLoaded] 二选一即可，两个都实现是文档要求的「双保险」——
     * `SystemServerStartingParam` 的 ClassLoader 更权威，后到者覆盖先到者。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            ClassLoader cl = param == null ? null : param.getClassLoader();
            installTripleHooks(cl, "onSystemServerStarting");
        } catch (Throwable t) {
            Log.e(TAG, "TRIPLE_ON_SS_ERR|" + t.getClass().getSimpleName());
        }
    }

    /**
     * 装三击 Hook。
     *
     * 【为什么用匿名 HookBinder 而不是静态字段】`hook()` 是 `XposedModule` 的**实例方法**，
     * 静态上下文拿不到；把绑定动作回传给本类，`TripleKeyHook` 就能保持纯静态、零项目依赖。
     */
    private void installTripleHooks(ClassLoader cl, String reason) {
        com.bydhooker.autostart.hook.triple.TripleKeyHook.HookBinder binder =
                new com.bydhooker.autostart.hook.triple.TripleKeyHook.HookBinder() {
                    @Override
                    public void bind(Method method, io.github.libxposed.api.XposedInterface.Hooker hooker)
                            throws Throwable {
                        hook(method).intercept(hooker);
                    }
                };
        com.bydhooker.autostart.hook.triple.TripleKeyHook.install(cl, reason, binder);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param == null ? "<null>" : param.getPackageName();

        // ---- 分支 A：小米智能卡（NFC 事件源，docs/30 §5.8）----
        if (TARGET_NFC.equals(pkg)) {
            String procNfc = processName != null ? processName : readProcCmdline();
            if (procNfc != null && procNfc.indexOf(':') >= 0) {
                // :daemon 也被注入，重复装配会让一次刷卡转发两次
                Log.i(TAG, "NFC_SUBPROCESS_SKIP|" + procNfc);
                return;
            }
            Log.i(TAG, "NFC_PACKAGE_LOADED|process=" + procNfc);
            assembleNfc(param);
            return;
        }

        // ---- 分支 B：SystemUI（超级岛，docs/37 §8.11）----
        if (TARGET_SYSTEMUI.equals(pkg)) {
            Log.i(TAG, "SYSUI_PACKAGE_LOADED|isFirstPackage=" + param.isFirstPackage());
            assembleSystemUI(param);
            return;
        }

        if (!TARGET.equals(pkg)) {
            return;
        }
        // ⚠ 高危修复（T06）：子进程一律不装配、不 hook。
        // 实测 `com.byd.aeri.caranywhere` 有 3 个进程（main / :tools / :pushservice），
        // 三进程各自走到这里 → 各自 assemble → 各自装一套 UnlockHooker；而
        // M2Runner.inFlight / UnlockHooker 去重都是 **进程内 static**（跨进程互不可见），
        // 一次解锁最多可触发 3 次 RUNENGINENODRIVE。下发只允许主进程执行。
        // 主进程名不含 ':'（已实测）；目标类 com.byd.base.control.ControlImpl 由主进程
        // 的 HomeAiCarFragment 驱动，主进程 hook 足够。
        String proc = processName;
        if (proc == null) {
            // 兜底：ModuleLoadedParam 未取到（理论上不会）时读 /proc/self/cmdline
            proc = readProcCmdline();
        }
        if (proc != null && proc.indexOf(':') >= 0) {
            Log.i(TAG, "SUBPROCESS_SKIP|" + proc);
            return;
        }
        Log.i(TAG, "TARGET_PACKAGE_LOADED|packageName=" + pkg
                + "|isFirstPackage=" + (param.isFirstPackage())
                + "|process=" + proc);
        assemble(param);
    }

    /** 兜底读当前进程名（/proc/self/cmdline 为 '\0' 分隔；失败返回 null）。 */
    private static String readProcCmdline() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ========================================================================
    // SystemUI 侧（com.android.systemui）—— 超级岛展示层
    // ========================================================================

    /**
     * SystemUI 装配（docs/37 §8.11）。
     *
     * 【为什么要延迟补挂】SystemUI 是多 dex / 插件化进程，装配期目标类可能还没挂载，
     * 且 currentApplication() 可能为 null（项目既有坑）。沿用 3/6/9/12/15 s 补挂模式，
     * 每轮只补没挂上的那个 Hook，全部 try/catch，绝不崩 SystemUI。
     */
    private void assembleSystemUI(XposedModuleInterface.PackageLoadedParam param) {
        if (!ASSEMBLED_SYSUI.compareAndSet(false, true)) {
            Log.i(TAG, "ASSEMBLE_SYSUI skipped|already assembled");
            com.bydhooker.autostart.island.SysUiLog.i("SUI_HOOK", "skipped|already assembled");
            return;
        }
        final ClassLoader cl = param == null ? null : param.getDefaultClassLoader();
        Thread t = new Thread(() -> {
            long[] delays = {0L, 3000L, 3000L, 3000L, 3000L, 3000L};
            for (long d : delays) {
                if (d > 0L) {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
                try {
                    // 装配期的日志也要能被导出（否则岛不显示时没有任何证据）
                    com.bydhooker.autostart.island.SysUiLog.initFromActivityThread();
                    hookFocusWhitelist(cl);
                    hookIslandBus(cl);
                    if (sysuiWhitelistHooked && sysuiBusHooked) {
                        com.bydhooker.autostart.island.SysUiLog.i("SUI_HOOK",
                                "OK|whitelist=1|bus=1");
                        return;
                    }
                } catch (Throwable tt) {
                    com.bydhooker.autostart.island.SysUiLog.w("SUI_HOOK_RETRY",
                            tt.getClass().getSimpleName());
                }
            }
            com.bydhooker.autostart.island.SysUiLog.w("SUI_HOOK",
                    "FAIL|whitelist=" + sysuiWhitelistHooked + "|bus=" + sysuiBusHooked);
        }, "bydhk-sysui-hook");
        t.setDaemon(true);
        t.start();
    }

    /** 放行 canShowFocusState / canShowFocusStateApp 对 com.android.systemui 的校验。 */
    private void hookFocusWhitelist(ClassLoader cl) throws Throwable {
        if (sysuiWhitelistHooked) {
            return;
        }
        ClassLoader use = cl != null ? cl : ClassLoader.getSystemClassLoader();
        Class<?> mgr = Class.forName(
                "com.miui.systemui.notification.NotificationSettingsManager", false, use);
        boolean any = false;
        // HyperOS4（SDK 37）：canShowFocusState / canShowFocusStateApp → int
        for (String name : new String[]{"canShowFocusState", "canShowFocusStateApp"}) {
            Method m = findMethod(mgr, name, int.class, 2);
            if (m != null) {
                hook(m).intercept(
                        com.bydhooker.autostart.hook.systemui.FocusWhitelistHook.INSTANCE);
                any = true;
                com.bydhooker.autostart.island.SysUiLog.i("SUI_WHITELIST", "已挂载 " + name + "（int）");
            }
        }
        // HyperOS3（SDK 36）：整套 dex 里没有 canShowFocusState，只有
        // canShowFocus(Context,String) static boolean（OS3.0.314 MiuiSystemUI 实证）。
        // 默认返回 true 本不挡岛，但用户手动关过焦点通知时会返回 false ⇒ 这里补齐两代一致。
        if (!any) {
            Method m = findMethod(mgr, "canShowFocus", boolean.class, 2);
            if (m != null) {
                hook(m).intercept(
                        com.bydhooker.autostart.hook.systemui.FocusWhitelistBoolHook.INSTANCE);
                any = true;
                com.bydhooker.autostart.island.SysUiLog.i("SUI_WHITELIST",
                        "已挂载 canShowFocus（boolean，OS3 形态）");
            }
        }
        sysuiWhitelistHooked = any;
    }

    /** 事件总线：NotificationProviderPublic.call 的自定义 method 前置短路。 */
    private void hookIslandBus(ClassLoader cl) throws Throwable {
        if (sysuiBusHooked) {
            return;
        }
        ClassLoader use = cl != null ? cl : ClassLoader.getSystemClassLoader();
        Class<?> pub = Class.forName(
                "com.android.systemui.statusbar.notification.NotificationProviderPublic",
                false, use);
        Method call = pub.getMethod("call", String.class, String.class,
                android.os.Bundle.class);
        hook(call).intercept(com.bydhooker.autostart.hook.systemui.IslandBusHook.INSTANCE);
        sysuiBusHooked = true;
    }

    /** 按「方法名 + 参数个数 + 返回类型」找方法（不硬编码参数类名，兼容两代 ROM 的返回类型差异）。 */
    private static Method findMethod(Class<?> c, String name, Class<?> returnType, int argc) {
        for (Method m : c.getDeclaredMethods()) {
            if (name.equals(m.getName()) && m.getReturnType() == returnType
                    && m.getParameterTypes().length == argc) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // ========================================================================
    // NFC 侧（com.miui.tsmclient）—— 与官方 App 那套完全独立，互不影响
    // ========================================================================

    /** NFC 侧装配闸门（独立于官方 App 的 {@link #ASSEMBLED}）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean ASSEMBLED_NFC =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean nfcHookRegistered = false;

    /** 预热 / 采卡 Hook 是否已装上（`DoubleClickActivity#onResume`）。 */
    private static volatile boolean nfcPrewarmRegistered = false;

    /**
     * 小米智能卡进程装配（`docs/30` §5.8；架构 C 第二期已改为**就地执行**）。
     *
     * 旧版只需要一个 Hook：把带 AID 的刷卡事件转发给模块 App。
     * 架构 C 起本进程要自己发 HTTPS，所以还要装配一套与官方 App 同款的执行前置：
     * 日志 / 配置 / 凭据 / 白盒 so / 车况数据源，**全部落在 tsmclient 自己的目录**。
     * 装配失败不崩宿主，NfcLocalExec 会把刷卡降级回 B 架构的跨进程转发。
     */
    private void assembleNfc(XposedModuleInterface.PackageLoadedParam param) {
        if (!ASSEMBLED_NFC.compareAndSet(false, true)) {
            Log.i(TAG, "ASSEMBLE_NFC skipped|already assembled");
            return;
        }
        try {
            com.bydhooker.autostart.hook.nfc.NfcHookProbe.markL1();
        } catch (Throwable t) {
            Log.w(TAG, "NFC_L1_FAIL|" + t.getClass().getSimpleName());
        }

        assembleNfcExec(param);

        // 状态补发：本阶段 ActivityThread.currentApplication() 还是 null（真机实测 ctx=false），
        // 三态变更发不出去。等 Application 就绪后重发两次（幂等，状态不变也不会重复唤醒模块）。
        try {
            final android.os.Handler rh =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            for (long delay : new long[]{3000L, 8000L}) {
                rh.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = com.bydhooker.autostart.hook.nfc.NfcHookProbe.retryReport();
                        if (!ok) {
                            Log.w(TAG, "NFC_STATE_RESEND_SKIP|no context yet");
                        }
                    }
                }, delay);
            }
        } catch (Throwable ignored) {
        }

        try {
            final ClassLoader cl = param.getDefaultClassLoader();
            // 两个 Hook 的目标类都可能还没载入：
            //  · `NfcEventMonitorService` 由 NFC 服务拉起时才载入
            //  · `DoubleClickActivity` 要等用户第一次打开刷卡页才载入
            // 所以统一按 3/6/9/12/15 s 补挂，各自成功即止。
            boolean swipeOk = tryRegisterNfcHook(cl);
            boolean prewarmOk = tryRegisterPrewarmHook(cl);
            if (!swipeOk || !prewarmOk) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                for (int i = 1; i <= 5; i++) {
                    final int n = i;
                    h.postDelayed(() -> {
                        boolean a = tryRegisterNfcHook(cl);
                        boolean b = tryRegisterPrewarmHook(cl);
                        if (!a || !b) {
                            Log.w(TAG, "NFC 延迟补挂 #" + n
                                    + "|swipe=" + a + "|prewarm=" + b);
                        }
                    }, n * 3000L);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "NFC_HOOK_SCHEDULE_FAIL", t);
        }
    }

    /**
     * 架构 C 第二期：在 tsmclient 进程装配**就地执行层**。
     *
     * 与 `assemble()`（官方 App 进程）刻意保持同一套顺序，差别只有目录：
     * 这里用 tsmclient 自己的 dataDir，凭据/日志/白盒 so 各存一份（D-34-2 四份凭据）。
     * 不用 `ModuleBridgeHost` / `RemotePrefs` 单例：那两个分派里带宿主专属语义
     * （cred.hostRevoked、把设置页拉前台），tsmclient 里不该触发。
     */
    private void assembleNfcExec(XposedModuleInterface.PackageLoadedParam param) {
        try {
            String dataDir = "/data/user/0/" + TARGET_NFC;
            try {
                if (param != null && param.getApplicationInfo() != null
                        && param.getApplicationInfo().dataDir != null) {
                    dataDir = param.getApplicationInfo().dataDir;
                }
            } catch (Throwable ignored) {
            }
            java.io.File filesDir = new java.io.File(dataDir, "files");

            String moduleApk = null;
            try {
                moduleApk = getModuleApplicationInfo().sourceDir;
            } catch (Throwable ignored) {
            }

            android.content.SharedPreferences sp =
                    getRemotePreferences(BridgeContract.INSTANCE.getPrefsGroup());
            com.bydhooker.autostart.hook.nfc.NfcLocalExec.init(
                    sp, filesDir, moduleApk, dataDir);
            Log.i(TAG, "NFC_LOCAL_ASSEMBLE|dataDir=" + dataDir
                    + "|apk=" + moduleApk + "|sp=" + (sp != null));
        } catch (Throwable t) {
            // 装配失败只降级，不能影响 tsmclient 自己的刷卡流程
            Log.e(TAG, "NFC_LOCAL_ASSEMBLE_FAIL|" + t.getClass().getName());
        }
    }

    /**
     * 挂钩 `DoubleClickActivity#onResume`（预热 + 采卡列表，`docs/30` §5.2 / §5.10.2）。
     *
     * **沿继承链向上找 `onResume`**：目标 Activity 未必自己覆写它，直接
     * `getDeclaredMethod("onResume")` 会漏。但**不允许一路找到 `android.app.Activity`** ——
     * 那会 hook 到 tsmclient 里所有 Activity，`getThisObject()` 就不再是刷卡页了。
     */
    private boolean tryRegisterPrewarmHook(ClassLoader cl) {
        if (nfcPrewarmRegistered) {
            return true;
        }
        try {
            Class<?> cls = cl.loadClass(com.bydhooker.autostart.hook.nfc.NfcPrewarmHook.TARGET_CLASS);
            Method target = null;
            Class<?> c = cls;
            while (c != null
                    && c != Object.class
                    && c.getName().startsWith("com.miui.tsmclient")) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("onResume") && m.getParameterTypes().length == 0) {
                        target = m;
                        break;
                    }
                }
                if (target != null) {
                    break;
                }
                c = c.getSuperclass();
            }
            if (target == null) {
                Log.w(TAG, "NFC_PREWARM_NONE|类已载入但没有 onResume");
                return false;
            }
            hook(target).intercept(new com.bydhooker.autostart.hook.nfc.NfcPrewarmHook());
            nfcPrewarmRegistered = true;
            Log.i(TAG, "NFC_PREWARM_OK|" + target.getDeclaringClass().getName() + "#onResume");
            return true;
        } catch (Throwable t) {
            // 类还没载入是常态（用户没打开过刷卡页），不打 error 级日志
            Log.w(TAG, "NFC_PREWARM_MISS|" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 挂钩 `NfcEventMonitorService#z(Intent)`。
     *
     * **必须打方法入口**：BYD 的 AID 没有内置 handler，会在这条链路下游提前 return
     * （`26` §5.2）。按「方法名 + 单 Intent 参数」匹配，不认混淆序号。
     */
    private boolean tryRegisterNfcHook(ClassLoader cl) {
        if (nfcHookRegistered) {
            return true;
        }
        try {
            Class<?> svc = cl.loadClass(com.bydhooker.autostart.hook.nfc.NfcSwipeHook.TARGET_CLASS);
            com.bydhooker.autostart.hook.nfc.NfcHookProbe.markL2();
            for (Method m : svc.getDeclaredMethods()) {
                if (!m.getName().equals(com.bydhooker.autostart.hook.nfc.NfcSwipeHook.TARGET_METHOD)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != android.content.Intent.class) {
                    continue;
                }
                try {
                    hook(m).intercept(new com.bydhooker.autostart.hook.nfc.NfcSwipeHook());
                    nfcHookRegistered = true;
                    com.bydhooker.autostart.hook.nfc.NfcHookProbe.markL3();
                    Log.i(TAG, "NFC_HOOK_OK|"
                            + com.bydhooker.autostart.hook.nfc.NfcSwipeHook.TARGET_CLASS + "#z(Intent)");
                } catch (Throwable te) {
                    Log.e(TAG, "NFC_HOOK_REGISTER_FAIL", te);
                }
            }
            if (!nfcHookRegistered) {
                Log.w(TAG, "NFC_HOOK_NONE|类已载入但没有 z(Intent)（签名漂移？）");
            }
            return nfcHookRegistered;
        } catch (Throwable t) {
            Log.w(TAG, "NFC_CLASS_MISS|" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 全组件装配（每步独立 try——一步失败不影响其余；失败落 EventLog）。 */
    private void assemble(XposedModuleInterface.PackageLoadedParam param) {
        // 装配幂等（T05）：onPackageLoaded 可能多次触发（多 dex / 重载），
        // EventLog/RemotePrefs/SelfCheck 只能初始化一次，否则日志重复、hook 重复注册
        if (!ASSEMBLED.compareAndSet(false, true)) {
            Log.i(TAG, "assemble skipped|already assembled");
            return;
        }
        final String hostDataDir = "/data/user/0/" + TARGET;
        final File hostFilesDir = new File(hostDataDir, "files");

        // ① 事件日志（宿主 files/bydhooker/）
        try {
            EventLog.INSTANCE.init(hostFilesDir);
            EventLog.INSTANCE.info("INIT", "BYDAutoStart T05 装配开始（hostDataDir=" + hostDataDir + "）");
        } catch (Throwable t) {
            Log.e(TAG, "init EventLog failed", t);
            return;
        }

        // ② 宿主 dataDir（P1-3 实证：宿主 files 可写；code_cache 同 UID 可写）
        try {
            ModuleBridgeHost.INSTANCE.init(hostFilesDir);
        } catch (Throwable t) {
            EventLog.INSTANCE.error("INIT", "ModuleBridgeHost 失败："
                    + t.getClass().getName());
        }

        // ③ Remote Preferences 只读桥 + Listener（裁定 G：设置页写 → 宿主收）
        try {
            RemotePrefs.INSTANCE.init(getRemotePreferences(
                    com.bydhooker.autostart.bridge.BridgeContract.INSTANCE.getPrefsGroup()));
        } catch (Throwable t) {
            EventLog.INSTANCE.error("BRIDGE_UNAVAILABLE", "桥初始化失败："
                    + t.getClass().getName());
        }

        // ③.6 框架日志桥（2026-11-09）：EventLog → `XposedModule.log(priority, tag, msg)`。
        // 官方日志通道：进 LSPosed 管理器「日志」页 + logcat，**不需要跨进程共享**
        // （remote prefs 反向写在真机实测无效：commit 返回 false，框架侧只读）。
        try {
            EventLog.INSTANCE.attachFrameworkLogger(
                    new kotlin.jvm.functions.Function3<Integer, String, String, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(Integer priority, String tag, String msg) {
                            try {
                                XposedModuleEntry.this.log(priority, tag, msg);
                            } catch (Throwable ignored) {
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                    });
            EventLog.INSTANCE.info("INIT", "框架日志桥已装配（LSPosed 日志页可见）");
        } catch (Throwable t) {
            Log.w(TAG, "attachFrameworkLogger failed", t);
        }

        // ④ 白盒密钥表（编译期常量，WbTables）
        try {
            WbKeys.INSTANCE.load();
        } catch (Throwable t) {
            EventLog.INSTANCE.error("WHITEBOX_FAIL", "wb_keys 加载失败："
                    + t.getClass().getName());
        }

        // ⑤ Whitebox so 三路径（P1-3 ✅；失败 = 功能禁用，Gate 拒绝下发）
        String moduleApkPath = null;
        try {
            moduleApkPath = getModuleApplicationInfo().sourceDir;
        } catch (Throwable ignored) {
        }
        try {
            ModuleBridgeHost.INSTANCE.ensureWhitebox(moduleApkPath, hostDataDir);
        } catch (Throwable t) {
            EventLog.INSTANCE.error("WHITEBOX_FAIL", "ensureLoaded 异常："
                    + t.getClass().getName());
        }

        // ⑥ UnlockHooker 注册（裁定 I：javap 签名；目标类名 OTA 漂移风险已标注）
        // SecNeo 壳在启动后数秒才把业务 dex 挂进 classloader——v4/v5 探针实测
        // `fallback9s new=160`（T0 几乎挂不上，9s 补挂成功、18s 无新增）。
        // 故 T0 失败不判死，按 3s/6s/9s/12s/15s 延迟补挂（成功即止）。
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            if (!tryRegisterHook(cl)) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                for (int i = 1; i <= 5; i++) {
                    final int n = i;
                    final ClassLoader clF = cl;
                    h.postDelayed(() -> {
                        if (!tryRegisterHook(clF)) {
                            EventLog.INSTANCE.warn("HOOK", "延迟补挂 #" + n + " 未就绪");
                        }
                    }, n * 3000L);
                }
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.error("HOOK", "hook 调度异常：" + t.getClass().getName());
        }

        // ⑦ StatusSource 注入（WatchRequest → StatusPoller）
        try {
        } catch (Throwable ignored) {
        }
        try {
            WatchRequest.INSTANCE.initStatusSource();
            EventLog.INSTANCE.info("INIT", "StatusSource 已注入（WatchRequest）");
        } catch (Throwable t) {
            EventLog.INSTANCE.error("INIT", "StatusSource 注入失败："
                    + t.getClass().getName());
        }

        // ⑧ 内层完整构造自检（B5 调试项；M-Q2 分段闭合；无凭据时 SKIP）
        try {
            SelfCheck.INSTANCE.run(new File(hostDataDir, "files"),
                    com.bydhooker.autostart.core.CredentialVault.INSTANCE.current());
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("SELFCHECK", "自检异常："
                    + t.getClass().getName());
        }
    }

    /**
     * HOOK 注册失败时的 classloader 链诊断（SecNeo 壳可能把业务 dex 挂在
     * 非 default classloader 上；v4/v5 探针时代 lpparam.classLoader 直挂成功）。
     * 只读不改：逐层 loadClass 探测并落 EventLog，供定位 Hook 目标真实所在层。
     */
    private static volatile boolean hookDiagnosed = false;

    /** 装配幂等闸门（T05）：多 dex / 多次 onPackageLoaded 只装配一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean ASSEMBLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 已注册的方法签名集合（T05 修重复 hook）：同一签名只 `hook(m).intercept(...)` 一次，
     * 重复注册会让一次解锁命令被拦截多次 → 多条 UNLOCK。
     */
    private static final java.util.Set<String> HOOKED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 单次 Hook 注册尝试（成功置位 hookRegistered 并落 EventLog）。 */
    private static volatile boolean hookRegistered = false;

    private boolean tryRegisterHook(ClassLoader cl) {
        if (hookRegistered) {
            return true;
        }
        try {
            Class<?> impl = cl.loadClass(UnlockHooker.TARGET_CLASS);
            // 参数类「IControl」是 02 报告的描述性写法，字面类名未实证（首装 CNFE 即落在
            // IControl）——改按方法名匹配 sendRemoteControl 的全部重载，逐个注册并落真实签名
            java.util.List<Method> hits = new java.util.ArrayList<>();
            for (Method m : impl.getDeclaredMethods()) {
                if (m.getName().equals(UnlockHooker.TARGET_METHOD)) {
                    hits.add(m);
                }
            }
            if (hits.isEmpty()) {
                EventLog.INSTANCE.error("HOOK", "ControlImpl 可载入但无 "
                        + UnlockHooker.TARGET_METHOD + " 方法（签名漂移）");
                return false;
            }
            for (Method m : hits) {
                Class<?>[] ps = m.getParameterTypes();
                StringBuilder sig = new StringBuilder(UnlockHooker.TARGET_METHOD).append('(');
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sig.append(',');
                    sig.append(ps[i].getName());
                }
                sig.append(')');
                final String sigStr = sig.toString();
                if (HOOKED.contains(sigStr)) {
                    hookRegistered = true;
                    EventLog.INSTANCE.debug("HOOK", "已注册跳过：" + sigStr);
                    continue;
                }
                try {
                    hook(m).intercept(UnlockHooker.INSTANCE);
                    HOOKED.add(sigStr);
                    hookRegistered = true;
                    EventLog.INSTANCE.info("HOOK", "UnlockHooker 注册成功：" + sigStr);
                } catch (Throwable te) {
                    EventLog.INSTANCE.error("HOOK", "注册失败 " + sigStr + "："
                            + te.getClass().getName() + " msg=" + te.getMessage());
                }
            }
            // 补挂 updateParams：首装实证 sendRemoteControl 实参为 UI 侧
            // HomeAiCarFragment$S（functionId=null，命令字不在实参上）；v4 报告实测
            // 命令字载体 = ControlImpl.updateParams(ControlInputParams).commandType。
            // 同一 Hooker 复用（intercept 按实参读 commandType，通用）。
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().equals("updateParams")) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                StringBuilder sig = new StringBuilder("updateParams(");
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sig.append(',');
                    sig.append(ps[i].getName());
                }
                sig.append(')');
                final String upSig = sig.toString();
                if (HOOKED.contains(upSig)) {
                    EventLog.INSTANCE.debug("HOOK", "已注册跳过：" + upSig);
                    continue;
                }
                try {
                    hook(m).intercept(UnlockHooker.INSTANCE);
                    HOOKED.add(upSig);
                    EventLog.INSTANCE.info("HOOK", "updateParams 注册成功：" + upSig);
                } catch (Throwable te) {
                    EventLog.INSTANCE.error("HOOK", "updateParams 注册失败 " + upSig + "："
                            + te.getClass().getName());
                }
            }
            // 补挂 mqttRemoteResult（回执探针 2026-11-09）：CloudResult 参数承载
            // 车端执行结果（02 报告实测 T+2.8s：code='0'、respondData={res=2}、
            // successLocaMsg=车门上锁成功）。只观测、不下发，用于确定「解锁成功」判据。
            // 按方法名 + 单参数匹配，不硬编码 CloudResult 类名（位于业务 dex）。
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().equals(com.bydhooker.autostart.hook.ReceiptHooker.TARGET_METHOD)) {
                    continue;
                }
                if (m.getParameterTypes().length != 1) {
                    continue;
                }
                final String rcSig = "mqttRemoteResult(" + m.getParameterTypes()[0].getName() + ")";
                if (HOOKED.contains(rcSig)) {
                    EventLog.INSTANCE.debug("HOOK", "已注册跳过：" + rcSig);
                    continue;
                }
                try {
                    hook(m).intercept(com.bydhooker.autostart.hook.ReceiptHooker.INSTANCE);
                    HOOKED.add(rcSig);
                    EventLog.INSTANCE.info("HOOK", "ReceiptHooker 注册成功：" + rcSig);
                } catch (Throwable te) {
                    EventLog.INSTANCE.error("HOOK", "ReceiptHooker 注册失败 " + rcSig + "："
                            + te.getClass().getName());
                }
            }
            // 补挂 WatchKeyLoginActivity.onCreate（快速授权第二阶的注入点探测）：
            // 该类在业务 dex 里，T0 可能载不到 → 失败只记 WARN，不影响主链路。
            try {
                Class<?> watchCls = cl.loadClass(
                        com.bydhooker.autostart.hook.WatchKeyLoginHook.TARGET_CLASS);
                // onCreate 很可能**没有被子类覆写**（getDeclaredMethods 看不到继承方法），
                // 故沿继承链找最近声明 onCreate(Bundle) 的类并挂它；
                // Hook 内部再用 instanceof 过滤，避免影响其它 Activity。
                boolean any = false;
                for (Class<?> c = watchCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    Method found = null;
                    for (Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals("onCreate")) {
                            continue;
                        }
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length == 1 && ps[0] == android.os.Bundle.class) {
                            found = m;
                            break;
                        }
                    }
                    if (found == null) {
                        continue;
                    }
                    String wsig = c.getName() + "#onCreate(Bundle)";
                    if (HOOKED.contains(wsig)) {
                        any = true;
                        break;
                    }
                    try {
                        hook(found).intercept(com.bydhooker.autostart.hook.WatchKeyLoginHook.INSTANCE);
                        HOOKED.add(wsig);
                        any = true;
                        EventLog.INSTANCE.info("HOOK", "WatchKeyLoginHook 注册成功：" + wsig);
                    } catch (Throwable te) {
                        EventLog.INSTANCE.warn("HOOK", "WatchKeyLoginHook 注册失败：" + wsig
                                + " " + te.getClass().getName());
                    }
                    break;
                }
                if (!any) {
                    EventLog.INSTANCE.warn("HOOK",
                            "WatchKeyLoginActivity 继承链上无 onCreate(Bundle) 可挂");
                }
                // onActivityResult 可能没被子类覆写 → 沿继承链找（过滤在 Hook 内做）
                for (Class<?> c = watchCls; c != null && c != Object.class;
                     c = c.getSuperclass()) {
                    Method ar = null;
                    for (Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals("onActivityResult")) {
                            continue;
                        }
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length == 3 && ps[0] == int.class && ps[1] == int.class) {
                            ar = m;
                            break;
                        }
                    }
                    if (ar == null) {
                        continue;
                    }
                    String asig = c.getName() + "#onActivityResult(III)";
                    if (!HOOKED.contains(asig)) {
                        try {
                            hook(ar).intercept(com.bydhooker.autostart.hook.WatchKeyLoginHook.INSTANCE);
                            HOOKED.add(asig);
                            EventLog.INSTANCE.info("HOOK", "onActivityResult 挂载成功：" + asig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "onActivityResult 挂载失败：" + asig
                                    + " " + te.getClass().getName());
                        }
                    }
                    break;
                }

                // 扫码回调/入口（子类自己声明的）——探测用：拿扫码结果落在哪个字段
                for (String mn : com.bydhooker.autostart.hook.WatchKeyLoginHook.TARGET_METHODS) {
                    for (Method m : watchCls.getDeclaredMethods()) {
                        if (!m.getName().equals(mn)) {
                            continue;
                        }
                        String gsig = watchCls.getSimpleName() + "#" + mn
                                + "/" + m.getParameterTypes().length;
                        if (HOOKED.contains(gsig)) {
                            continue;
                        }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.WatchKeyLoginHook.INSTANCE);
                            HOOKED.add(gsig);
                            EventLog.INSTANCE.info("HOOK", "扫码探测挂载成功：" + gsig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "扫码探测挂载失败：" + gsig
                                    + " " + te.getClass().getName());
                        }
                    }
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "WatchKeyLoginActivity 不可载入："
                        + te.getClass().getSimpleName());
            }
            // 扫码页探针（快速授权第二轮）：真机 logcat 实证扫码走的是
            // com.byd.service.scan.ScanActivity，不是 WatchKeyLoginActivity.intentScan()
            try {
                Class<?> scanCls = cl.loadClass(
                        com.bydhooker.autostart.hook.ScanActivityHook.TARGET_CLASS);
                int hooked = 0;
                for (Class<?> c = scanCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName();
                        Class<?>[] ps = m.getParameterTypes();
                        boolean want = "onCreate".equals(mn) && ps.length == 1
                                && ps[0] == android.os.Bundle.class;
                        if (!want) {
                            continue;
                        }
                        String sig = c.getSimpleName() + "#" + mn + "/" + ps.length;
                        if (HOOKED.contains(sig)) {
                            continue;
                        }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                            HOOKED.add(sig);
                            hooked++;
                            EventLog.INSTANCE.info("HOOK", "扫码页探针挂载：" + sig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "扫码页探针挂载失败：" + sig
                                    + " " + te.getClass().getName());
                        }
                    }
                }
                if (hooked == 0) {
                    EventLog.INSTANCE.warn("HOOK", "ScanActivity 无可挂方法（结构漂移）");
                }
                // 注入官方 App classloader（供 provider 反射构造用）
                try {
                    com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE.setHostClassLoader(cl);
                } catch (Throwable ignored) {
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "ScanActivity 不可载入："
                        + te.getClass().getSimpleName());
            }
            // Instrumentation.execStartActivity 探针（第三轮）：所有 Activity 启动必经
            try {
                Class<?> insCls = cl.loadClass("android.app.Instrumentation");
                int hooked = 0;
                for (Method m : insCls.getDeclaredMethods()) {
                    if (!m.getName().equals("execStartActivity")) {
                        continue;
                    }
                    boolean hasIntent = false;
                    for (Class<?> ps : m.getParameterTypes()) {
                        if (ps == android.content.Intent.class) { hasIntent = true; break; }
                    }
                    if (!hasIntent) {
                        continue;
                    }
                    String sig = "Instrumentation#execStartActivity/" + m.getParameterCount();
                    if (HOOKED.contains(sig)) {
                        continue;
                    }
                    try {
                        hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                        HOOKED.add(sig);
                        hooked++;
                        EventLog.INSTANCE.info("HOOK", "execStartActivity 探针挂载：" + sig);
                    } catch (Throwable te) {
                        EventLog.INSTANCE.warn("HOOK", "execStartActivity 挂载失败：" + sig
                                + " " + te.getClass().getName());
                    }
                }
                if (hooked == 0) {
                    EventLog.INSTANCE.warn("HOOK", "Instrumentation.execStartActivity 无可挂重载");
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "Instrumentation 不可载入："
                        + te.getClass().getSimpleName());
            }
            // TabManager onResume/onNewIntent 探针（第五轮）：快速授权注入宿主
            try {
                Class<?> tmCls = cl.loadClass("com.byd.aeri.splash.module_splash.activity.TabManager");
                Class<?> actCls = cl.loadClass("android.app.Activity");

                int hooked = 0;
                for (Class<?> c = tmCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName();
                        if (!(mn.equals("onResume") || mn.equals("onNewIntent"))) { continue; }
                        if (mn.equals("onNewIntent") && m.getParameterCount() != 1) { continue; }
                        String sig = c.getSimpleName() + "#" + mn + "/" + m.getParameterCount();
                        if (HOOKED.contains(sig)) { continue; }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                            HOOKED.add(sig);
                            hooked++;
                            EventLog.INSTANCE.info("HOOK", "TabManager 探针挂载：" + sig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "TabManager 探针挂载失败：" + sig
                                    + " " + te.getClass().getName());
                        }
                    }
                    // 沿继承链找到 Activity 的 onResume/onNewIntent 也挂上
                    for (Method m : actCls.getDeclaredMethods()) {
                        String mn = m.getName();
                        if (!(mn.equals("onResume") || mn.equals("onNewIntent"))) { continue; }
                        if (mn.equals("onNewIntent") && m.getParameterCount() != 1) { continue; }
                        String sig = "Activity#" + mn + "/" + m.getParameterCount();
                        if (HOOKED.contains(sig)) { continue; }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                            HOOKED.add(sig);
                            hooked++;
                            EventLog.INSTANCE.info("HOOK", "Activity 生命周期探针挂载：" + sig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "Activity 生命周期挂载失败：" + sig
                                    + " " + te.getClass().getName());
                        }
                    }
                    break;
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "TabManager 探针注册失败：" + te.getClass().getSimpleName());
            }
            // FlexibleScan.startScan 探针（第四轮）：官方封装的扫码 API
            try {
                Class<?> fsCls = cl.loadClass("com.byd.service.scan.FlexibleScan");
                int hooked = 0;
                for (Method m : fsCls.getDeclaredMethods()) {
                    if (!m.getName().equals("startScan")) {
                        continue;
                    }
                    String sig = "FlexibleScan#startScan/" + m.getParameterCount();
                    if (HOOKED.contains(sig)) { continue; }
                    try {
                        hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                        HOOKED.add(sig);
                        hooked++;
                        EventLog.INSTANCE.info("HOOK", "startScan 探针挂载：" + sig);
                    } catch (Throwable te) {
                        EventLog.INSTANCE.warn("HOOK", "startScan 挂载失败：" + sig
                                + " " + te.getClass().getName());
                    }
                }
                if (hooked == 0) {
                    EventLog.INSTANCE.warn("HOOK", "FlexibleScan.startScan 无可挂重载");
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "FlexibleScan 不可载入：" + te.getClass().getSimpleName());
            }
            // TabManager.onActivityResult 探针（第四轮）：扫码结果真实入参
            try {
                Class<?> tmCls = cl.loadClass("com.byd.aeri.splash.module_splash.activity.TabManager");
                int hooked2 = 0;
                for (Class<?> c = tmCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals("onActivityResult")) { continue; }
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length != 3 || ps[0] != int.class) { continue; }
                        String sig = c.getSimpleName() + "#onActivityResult/3";
                        if (HOOKED.contains(sig)) { continue; }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                            HOOKED.add(sig);
                            hooked2++;
                            EventLog.INSTANCE.info("HOOK", "onActivityResult 探针挂载：" + sig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "onActivityResult 挂载失败：" + sig
                                    + " " + te.getClass().getName());
                        }
                    }
                }
                if (hooked2 == 0) {
                    EventLog.INSTANCE.warn("HOOK", "TabManager.onActivityResult 无可挂方法");
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "TabManager 不可载入：" + te.getClass().getSimpleName());
            }
            // startActivity 探针（第二轮）：抓「首页 → ScanActivity」的调用链
            try {
                Class<?> actCls = cl.loadClass("android.app.Activity");
                int hooked = 0;
                for (Class<?> c = actCls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals("startActivity")) {
                            continue;
                        }
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length != 1 || ps[0] != android.content.Intent.class) {
                            continue;
                        }
                        String sig = c.getSimpleName() + "#startActivity(Intent)";
                        if (HOOKED.contains(sig)) {
                            continue;
                        }
                        try {
                            hook(m).intercept(com.bydhooker.autostart.hook.ScanActivityHook.INSTANCE);
                            HOOKED.add(sig);
                            hooked++;
                            EventLog.INSTANCE.info("HOOK", "startActivity 探针挂载：" + sig);
                        } catch (Throwable te) {
                            EventLog.INSTANCE.warn("HOOK", "startActivity 探针挂载失败：" + sig
                                    + " " + te.getClass().getName());
                        }
                    }
                }
                if (hooked == 0) {
                    EventLog.INSTANCE.warn("HOOK", "Activity.startActivity 无可挂方法");
                }
            } catch (Throwable te) {
                EventLog.INSTANCE.warn("HOOK", "Activity 不可载入：" + te.getClass().getSimpleName());
            }
            return hookRegistered;
        } catch (Throwable t) {
            // 明码授权范围内记录完整异常信息（定位 CNFE 究竟落在哪个类）
            EventLog.INSTANCE.error("HOOK", "UnlockHooker 注册失败："
                    + t.getClass().getName() + " msg=" + t.getMessage());
            if (!hookDiagnosed) {
                hookDiagnosed = true;
                diagnoseClassLoaders(cl, UnlockHooker.TARGET_CLASS);
            }
            return false;
        }
    }

    private void diagnoseClassLoaders(ClassLoader root, String target) {
        try {
            int i = 0;
            for (ClassLoader c = root; c != null && i < 10; c = c.getParent(), i++) {
                try {
                    c.loadClass(target);
                    EventLog.INSTANCE.warn("HOOK", "目标类在 cl[" + i + "]="
                            + c.getClass().getName() + " 可载入（需改用该层注册）");
                    return;
                } catch (Throwable layerMiss) {
                    EventLog.INSTANCE.info("HOOK", "cl[" + i + "]="
                            + c.getClass().getName() + " 无目标类");
                }
            }
            EventLog.INSTANCE.warn("HOOK", "classloader 链 " + i + " 层均无目标类"
                    + "（加固壳动态 dex？需 hook DexClassLoader 兜底）");
        } catch (Throwable ignored) {
        }
    }
}

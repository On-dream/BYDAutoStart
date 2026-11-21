package com.bydhooker.autostart.hook;

import com.bydhooker.autostart.core.EventLog;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;

import androidx.annotation.NonNull;

/**
 * 官方 App 扫码链路 Hook —— 「快速授权」最终实现（2026-11-09）。
 *
 * ## 真机实证的官方扫码链路（四轮探针）
 * ```
 * 首页「扫一扫」→ TabManager.startActivityForResult
 *   ← FlexibleScan.startScan(activity, bool, bool, ScanCodeProviderImpl$b)
 *      （第 4 参数 = 扫码结果处理者，方法 onModuleResultSuccess(Context, str, callback)）
 *   → ScanActivity 摄像头识别 → handleScanResultToModule(str) → 交给 b 实例
 *   → 官方 App 上报云端（uuid 推进到「已扫描」）
 *   → ARouter 跳 WatchKeyLoginActivity（extras={watchUuid}）
 *   → 用户点「登录」→ 云端「已确认」→ 模块轮询 codeStatus=30000 → 换 token
 * ```
 *
 * ## 快速授权（用户点「快速授权」后）
 * 官方 App 首页 onResume 时，按三级回退把「已扫码」流程触发起来：
 * 1. **反射构造** `ScanCodeProviderImpl$b` 并直调 `onModuleResultSuccess`（免扫码页）
 * 2. 失败则用**缓存的 provider 实例**（本进程内扫过码/startScan 被调过时存在）
 * 3. 再失败则**模拟点击首页扫一扫**（走 startScan 拦截，摄像头页不会出现）
 *
 * 依赖：root（未导出 Activity / 受限 mount namespace 需要）。
 */
public final class ScanActivityHook implements XposedInterface.Hooker {

    public static final ScanActivityHook INSTANCE = new ScanActivityHook();

    public static final String SCAN_ACTIVITY = "com.byd.service.scan.ScanActivity";

    /** 兼容旧引用。 */
    public static final String TARGET_CLASS = SCAN_ACTIVITY;

    /** 首页（宿主 Tab 容器）。 */
    public static final String TAB_MANAGER =
            "com.byd.aeri.splash.module_splash.activity.TabManager";

    /** 扫码结果处理者实现类（第四轮探针实测）。 */
    private static final String PROVIDER_IMPL = "com.byd.mine.impl.ScanCodeProviderImpl";

    /** 扫码结果处理方法名（真机 dump 实测）。 */
    private static final String METHOD_SUCCESS = "onModuleResultSuccess";

    /** 设置页写入的二维码内容（base64）。 */
    private static final String INJECT_FILE =
            "/data/user/0/com.byd.aeri.caranywhere/files/bydhooker/qr_inject.txt";

    /** 官方 App 的 classloader（加固壳动态 dex 的业务类在这里，由装配时注入）。 */
    private static volatile ClassLoader hostClassLoader;

    /** 装配时注入官方 App classloader（XposedModuleEntry 调用）。 */
    public static void setHostClassLoader(ClassLoader cl) {
        hostClassLoader = cl;
    }

    /** 底部 Tab 中「我的」的下标（发现/商城/车/服务/我的 → 4）。 */
    private static final int MINE_TAB_INDEX = 4;

    /** Toast 只弹一次。 */
    private static volatile boolean toastShown = false;

    /** TabManager 方法清单只 dump 一次。 */
    private static volatile boolean dumpedTab = false;

    /** startScan 第 4 参数：扫码结果处理者实例（进程内缓存）。 */
    private static volatile Object cachedProvider = null;

    /** startScan 拦截只喂一次。 */
    private static volatile boolean fed = false;

    /** 本进程只注入一次。 */
    private static volatile boolean injected = false;

    private ScanActivityHook() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        String name = methodName(chain);
        Object self = chain.getThisObject();

        // ⓪ TabManager 方法清单 dump（一次性）：定位内部「扫一扫」入口方法
        if ("onResume".equals(name) && self != null
                && isTabManager(self) && !dumpedTab) {
            dumpedTab = true;
            dumpTabMethods(self.getClass());
        }

        // ① 首页 onResume：快速授权的三级注入（在官方代码执行前）
        if (("onResume".equals(name) || "onNewIntent".equals(name)) && self != null
                && self instanceof android.app.Activity) {
            // 引导提示统一在模块设置页弹 Toast（用户点「快速授权」时），官方 App 内不重复提示。
            // 也放弃自动切页（模拟点击不稳定，用户决策放弃）。
        }

        // ② startScan：缓存 provider 实例；若有注入文件则拦截并直喂
        if ("startScan".equals(name)) {
            try {
                Object[] args = chain.getArgs().toArray();
                if (args.length >= 4 && args[3] != null) {
                    cachedProvider = args[3];
                }
                String payload = readPayloadRaw();
                if (payload != null && !fed && args.length >= 4
                        && args[0] != null && args[3] != null) {
                    fed = true;
                    if (invokeOnModuleResultSuccess(args[3], args[0], payload)) {
                        consumeInjectFile();
                        EventLog.INSTANCE.info("QUICK_AUTH",
                                "startScan 已喂入二维码（官方流程照常）");
                    }
                }
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                EventLog.INSTANCE.error("QUICK_AUTH", "startScan 注入失败："
                        + c.getClass().getName() + " msg=" + c.getMessage());
            }
        }

        Object result = chain.proceed();

        // ③ 探测/调试：execStartActivity（扫码页与登录页的启动记录）
        if ("execStartActivity".equals(name)) {
            try {
                Object intent = safeArg(chain, 4);
                if (intent instanceof android.content.Intent) {
                    android.content.Intent it = (android.content.Intent) intent;
                    String target = it.getComponent() == null ? ""
                            : it.getComponent().getClassName();
                    if (target.endsWith("service.scan.ScanActivity")
                            || target.contains("WatchKeyLogin")) {
                        EventLog.INSTANCE.info("QUICK_AUTH",
                                "execStartActivity 目标=" + target + " extras="
                                        + describeExtras(it));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    // ---------------- 注入 ----------------

    /**
     * 反射构造 `ScanCodeProviderImpl$b` 并直调 `onModuleResultSuccess`。
     *
     * @return true = 调用成功（官方 App 开始处理）
     */
    private boolean tryViaReflectionWithLoaders(android.content.Context ctx, String payload,
                                                ClassLoader[] loaders) {
        try {
            Class<?> implCls = null;
            Class<?> bCls = null;
            ClassLoader used = null;
            for (ClassLoader cl : loaders) {
                if (cl == null) { continue; }
                try {
                    Class<?> a = cl.loadClass(PROVIDER_IMPL);
                    Class<?> b = cl.loadClass(PROVIDER_IMPL + "$b");
                    implCls = a; bCls = b; used = cl;
                    break;
                } catch (Throwable ignored) { }
            }
            if (implCls == null || bCls == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH",
                        "ScanCodeProviderImpl 在所有 classloader 中都找不到（"
                                + loaders.length + " 个候选）");
                return false;
            }
            EventLog.INSTANCE.info("QUICK_AUTH",
                    "provider 类加载成功（loader=" + used.getClass().getName() + "）");
            Object impl = newFirst(implCls, ctx);
            Object handler = newFirst(bCls, impl);
            return invokeOnModuleResultSuccess(handler, ctx, payload);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            EventLog.INSTANCE.warn("QUICK_AUTH", "反射构造 provider 失败："
                    + c.getClass().getName() + " msg=" + c.getMessage());
            return false;
        }
    }

    /**
     * 反射调用 `handler.onModuleResultSuccess(ctx, 二维码字符串, null)`。
     *
     * @return true = 方法调用成功（内部逻辑成败由官方 App 决定）
     */
    private boolean invokeOnModuleResultSuccess(Object handler, Object ctx, String payload) {
        try {
            Method m = null;
            Class<?> cbCls = null;
            for (Method mm : handler.getClass().getMethods()) {
                if (mm.getName().equals(METHOD_SUCCESS) && mm.getParameterCount() == 3) {
                    m = mm;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length >= 3) { cbCls = ps[2]; }
                    break;
                }
            }
            if (m == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "未找到 " + METHOD_SUCCESS + "(3参)");
                return false;
            }
            // 第三个参数 AfterScanSuccessUserTodoCallBack：构造一个实例（传 null 会 NPE）
            Object cbArg = null;
            if (cbCls != null && !cbCls.isPrimitive()) {
                // ★ 优先用 provider 内部持有的**真实** callback（它才是负责
                //   「解析结果 → 跳登录页」的那个）；构造空壳会覆盖它导致不跳转。
                cbArg = findFieldOfType(handler, cbCls);
                String src = "provider 内部字段";
                if (cbArg == null) {
                    cbArg = newFirst(cbCls, ctx, handler, cachedProvider);
                    src = "新建实例";
                    if (cbArg == null) {
                        try {
                            cbArg = cbCls.getDeclaredConstructor().newInstance();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                EventLog.INSTANCE.info("QUICK_AUTH", "callback=" + src + "："
                        + (cbArg == null ? "null" : cbArg.getClass().getName()));
            }
            m.setAccessible(true);
            m.invoke(handler, ctx, payload, cbArg);
            EventLog.INSTANCE.info("QUICK_AUTH",
                    "已调用 " + METHOD_SUCCESS + "，等待官方 App 跳转登录页");
            return true;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            EventLog.INSTANCE.error("QUICK_AUTH", METHOD_SUCCESS + " 调用失败："
                    + c.getClass().getName() + " msg=" + c.getMessage());
            return false;
        }
    }

    private void consumeInjectFile() {
        try {
            new File(INJECT_FILE).delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 回退：模拟点击 ----------------

    /** 点击底部「我的」Tab（真机验证有效；切页是用户自己也会做的动作，不代替用户完成授权）。 */
    private void tapMineTab(final android.app.Activity act) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.view.View root = act.getWindow().getDecorView();
                    android.view.View mine = findViewByText(root, "我的", 0);
                    if (mine != null) {
                        mine.performClick();
                        EventLog.INSTANCE.info("QUICK_AUTH",
                                "已切到「我的」页，请点右上角扫一扫");
                    } else {
                        EventLog.INSTANCE.warn("QUICK_AUTH", "未找到底部「我的」Tab");
                    }
                } catch (Throwable t) {
                    EventLog.INSTANCE.warn("QUICK_AUTH", "点击「我的」失败："
                            + t.getClass().getName());
                }
            }, 700);
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "切页调度失败：" + t.getClass().getName());
        }
    }

    /**
     * 把官方 App 切到「我的」页（底部第 5 个 Tab），让用户看到右上角的扫一扫图标。
     * 走官方自己的 {@code changeCurrentPage(int)}，不模拟点击。
     */
    private void switchToMinePage(android.app.Activity act) {
        try {
            Method m = null;
            for (Class<?> c = act.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("changeCurrentPage", int.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "未找到 changeCurrentPage(int)");
                return;
            }
            m.setAccessible(true);
            m.invoke(act, MINE_TAB_INDEX);
            EventLog.INSTANCE.info("QUICK_AUTH",
                    "已调用 changeCurrentPage(" + MINE_TAB_INDEX + ")");
            // 该 index 并非「我的」页（实测页面未切换）→ 改用底栏 Tab 点击（已验证有效）
            tapMineTab(act);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            EventLog.INSTANCE.warn("QUICK_AUTH", "切页失败：" + c.getClass().getName()
                    + " msg=" + c.getMessage());
        }
    }

    /**
     * 第三级回退：切到「我的」页 → 点击右上角「扫一扫」图标。
     * 由官方 App 自己走 startScan（provider 与 callback 都是它构造的），
     * 随后被本类的 startScan 拦截喂入二维码。
     */
    private void scheduleScanFlow(android.app.Activity act) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.view.View root = act.getWindow().getDecorView();
                    android.view.View mine = findViewByText(root, "我的", 0);
                    if (mine != null) {
                        mine.performClick();
                        EventLog.INSTANCE.info("QUICK_AUTH", "已切到「我的」页");
                    } else {
                        EventLog.INSTANCE.warn("QUICK_AUTH", "未找到底部「我的」Tab");
                    }
                } catch (Throwable t) {
                    EventLog.INSTANCE.warn("QUICK_AUTH", "切页失败：" + t.getClass().getName());
                }
                // 等页面切换完成后再找扫一扫
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        android.view.View root = act.getWindow().getDecorView();
                        android.view.View scan = findScanIcon(root, 0);
                        if (scan != null) {
                            scan.performClick();
                            EventLog.INSTANCE.info("QUICK_AUTH",
                                    "已点击右上角扫一扫，等待 startScan 拦截");
                        } else {
                            EventLog.INSTANCE.warn("QUICK_AUTH",
                                    "未找到右上角扫一扫图标（位置法未命中）");
                        }
                    } catch (Throwable t) {
                        EventLog.INSTANCE.warn("QUICK_AUTH", "点击扫一扫失败："
                                + t.getClass().getName());
                    }
                }, 900);
            }, 900);
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "扫码流程调度失败：" + t.getClass().getName());
        }
    }

    /** 递归找文字匹配的控件。 */
    private android.view.View findViewByText(android.view.View v, String text, int depth) {
        if (v == null || depth > 14) { return null; }
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && text.contentEquals(t)) { return v; }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findViewByText(g.getChildAt(i), text, depth + 1);
                if (r != null) { return r; }
            }
        }
        return null;
    }

    /**
     * 按**屏幕位置**找右上角扫一扫图标（真机截图实测：中心约 x=0.75、y=0.08，
     * 为纯图标无文字，id 亦不含 scan）。限定 x∈[0.68,0.84] 以避开右侧铃铛。
     */
    private android.view.View findScanIcon(android.view.View v, int depth) {
        if (v == null || depth > 14) { return null; }
        android.view.ViewGroup g = (v instanceof android.view.ViewGroup)
                ? (android.view.ViewGroup) v : null;
        if (g != null) {
            android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
            float w = dm.widthPixels;
            float h = dm.heightPixels;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View c = g.getChildAt(i);
                android.graphics.Rect r = new android.graphics.Rect();
                if (!c.getGlobalVisibleRect(r)) { continue; }
                if (r.width() <= 0 || r.height() <= 0) { continue; }
                float cx = (r.left + r.right) / 2f / w;
                float cy = (r.top + r.bottom) / 2f / h;
                boolean sizeOk = r.width() < w * 0.16f && r.height() < h * 0.07f;
                if (sizeOk && cx > 0.66f && cx < 0.84f && cy > 0.02f && cy < 0.13f) {
                    return c;
                }
            }
        }
        if (g != null) {
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findScanIcon(g.getChildAt(i), depth + 1);
                if (r != null) { return r; }
            }
        }
        return null;
    }

    /** 延迟后自动点击首页的「扫一扫」按钮，触发 startScan（随后被本类拦截注入）。 */
    private void scheduleTapScanButton(android.app.Activity act) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.view.View root = act.getWindow().getDecorView();
                    android.view.View btn = findScanView(root, 0);
                    if (btn != null) {
                        btn.performClick();
                        EventLog.INSTANCE.info("QUICK_AUTH", "已模拟点击扫一扫，等待 startScan 拦截");
                    } else {
                        EventLog.INSTANCE.warn("QUICK_AUTH",
                                "未找到扫一扫按钮（view 树中无匹配），请手动点击");
                    }
                } catch (Throwable t) {
                    EventLog.INSTANCE.warn("QUICK_AUTH", "模拟点击失败：" + t.getClass().getName());
                }
            }, 800);
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "点击调度失败：" + t.getClass().getName());
        }
    }

    /** 递归找「扫一扫」控件：优先文字，其次 id 资源名含 scan。 */
    private android.view.View findScanView(android.view.View v, int depth) {
        if (v == null || depth > 12) {
            return null;
        }
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.toString().contains("扫")) {
                return v;
            }
        }
        try {
            int id = v.getId();
            if (id != android.view.View.NO_ID) {
                String rn = v.getResources().getResourceEntryName(id);
                if (rn != null && rn.toLowerCase().contains("scan")) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findScanView(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ---------------- 工具 ----------------

    /** 读注入文件（base64 → 二维码字符串）。 */
    private String readPayloadRaw() {
        try {
            File f = new File(INJECT_FILE);
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String b64 = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (b64.isEmpty()) {
                return null;
            }
            byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "read payload fail: " + t.getClass().getName());
            return null;
        }
    }

    private boolean hasInjectFile() {
        try {
            File f = new File(INJECT_FILE);
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** try all constructors (incl. private), pick the first one that fits. */
    private static Object newFirst(Class<?> cls, Object... candidates) {
        for (java.lang.reflect.Constructor<?> ct : cls.getDeclaredConstructors()) {
            ct.setAccessible(true);
            Class<?>[] ps = ct.getParameterTypes();
            Object[] args = new Object[ps.length];
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                Object c = null;
                for (Object cand : candidates) {
                    if (cand != null && ps[i].isInstance(cand)) { c = cand; break; }
                }
                if (c == null && ps[i] == int.class) { c = Integer.valueOf(0); }
                if (c == null && ps[i] == boolean.class) { c = Boolean.FALSE; }
                if (c == null && ps[i] != String.class && ps[i] != Object.class && !ps[i].isPrimitive()) {
                    boolean matched = false;
                    for (Object cand : candidates) {
                        if (cand != null && ps[i].isAssignableFrom(cand.getClass())) { c = cand; matched = true; break; }
                    }
                    if (!matched) { ok = false; break; }
                }
                args[i] = c;
            }
            if (!ok) { continue; }
            try { return ct.newInstance(args); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 在对象（含父类）里找类型匹配的字段值（provider 内部持有的真实 callback）。 */
    private static Object findFieldOfType(Object obj, Class<?> type) {
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!type.isAssignableFrom(f.getType())) { continue; }
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) { return v; }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** dump TabManager（含父类）方法清单，重点标注扫码相关。 */
    private void dumpTabMethods(Class<?> cls) {
        try {
            StringBuilder sb = new StringBuilder("TabManager 可疑方法：");
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    String mn = m.getName().toLowerCase();
                    boolean hit = mn.contains("scan") || mn.contains("qr")
                            || mn.contains("code") || mn.contains("camera")
                            || mn.contains("photo") || mn.contains("album")
                            || mn.contains("image") || mn.contains("result")
                            || mn.contains("module") || mn.contains("provider")
                            || mn.contains("flexible") || mn.contains("watch");
                    if (!hit) { continue; }
                    sb.append('[').append(c.getSimpleName()).append(']')
                            .append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) { sb.append(','); }
                        sb.append(ps[i].getSimpleName());
                    }
                    sb.append(") ");
                }
            }
            EventLog.INSTANCE.info("QUICK_AUTH", sb.toString());
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "dumpTabMethods 失败："
                    + t.getClass().getSimpleName());
        }
    }

    /** TabManager 判定用宽松匹配：官方 App 里实际实例可能是其子类。 */
    private static boolean isTabManager(Object self) {
        if (self == null) { return false; }
        String n = self.getClass().getName();
        return n.equals(TAB_MANAGER) || n.contains("TabManager");
    }

    private String methodName(XposedInterface.Chain chain) {
        try {
            Executable ex = chain.getExecutable();
            return ex instanceof Method ? ((Method) ex).getName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isScanAct(Object self) {
        return self != null && SCAN_ACTIVITY.equals(self.getClass().getName());
    }

    private Object safeArg(XposedInterface.Chain chain, int i) {
        try {
            return chain.getArg(i);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Intent extras 描述（值截断）。 */
    private String describeExtras(android.content.Intent it) {
        android.os.Bundle b = it.getExtras();
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) {
            String v = String.valueOf(b.get(k));
            sb.append(k).append('=').append(v.length() > 220 ? v.substring(0, 220) + "…" : v)
                    .append("; ");
        }
        return sb.append('}').toString();
    }
    /** 官方 App 已打开时的引导提示（一次）。 */
    private void showGuideToast(android.app.Activity act) {
        if (toastShown) { return; }
        toastShown = true;
        try {
            android.widget.Toast.makeText(
                    act.getApplicationContext(),
                    "已进入官方 App，请进入「我的」页，点击右上角扫一扫",
                    android.widget.Toast.LENGTH_LONG).show();
            EventLog.INSTANCE.info("QUICK_AUTH", "已弹 Toast 引导用户自行点扫一扫");
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "Toast 失败：" + t.getClass().getName());
        }
    }

}
package com.bydhooker.autostart.hook;

import com.bydhooker.autostart.core.EventLog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import androidx.annotation.NonNull;

/**
 * 官方 App 手表授权页 Hook —— 「快速授权」的实现（2026-11-09）。
 *
 * 目标：`com.byd.aeri.watch.activity.WatchKeyLoginActivity`
 * （Manifest 实测；未声明 exported，但 root 的 `am start` 可强启，已真机验证）。
 *
 * ## 真机探测结论（2026-11-09 21:44 / 21:47）
 * - 该类未覆写 `onCreate`，实际声明在父类 `com.byd.base.baseMVP.DiActivity`。
 * - 关键方法：`intentScan()`（扫一扫入口）、`getWatchScanLoginSuccess()`（扫码成功回调，
 *   **无参数**——结果藏在字段里）、`getWatchScanLoginFail(RebackInfoResult)`、`onEvent(a)`（EventBus）。
 * - 字段 `selectedAutoInfo` 的类型是
 *   `com.byd.aeri.core.bean.response.childBean.login.DiLinkAutoInfo`，**有无参构造器**；
 *   扫码成功那一刻该字段已填好（vin / autoAlias / autoPlate / channel / tboxVersion…）。
 *
 * ## 注入策略
 * 等价于「扫码成功」：给 `selectedAutoInfo` 赋值后调用 `getWatchScanLoginSuccess()`。
 * 数据来源优先级：
 * 1. **缓存**：之前真正扫码成功时抓到的对象（字段最全，跨用户最准）；
 * 2. **自建**：用本机已授权凭据（vin / autoPlate / autoAlias / tboxVersion / channel=1）构造。
 *
 * 仅当注入文件存在（= 用户点了「快速授权」）才动作，平时纯旁观。
 */
public final class WatchKeyLoginHook implements XposedInterface.Hooker {

    public static final WatchKeyLoginHook INSTANCE = new WatchKeyLoginHook();

    /** Manifest 实测类名。 */
    public static final String TARGET_CLASS =
            "com.byd.aeri.watch.activity.WatchKeyLoginActivity";

    /** 车辆信息 bean（真机 dump 实测）。 */
    private static final String INFO_CLASS =
            "com.byd.aeri.core.bean.response.childBean.login.DiLinkAutoInfo";

    /** 设置页写入的注入文件（base64 payload）。 */
    private static final String INJECT_FILE =
            "/data/user/0/com.byd.aeri.caranywhere/files/bydhooker/qr_inject.txt";

    /** 子类自己声明、需要挂的方法（onCreate / onActivityResult 走继承链）。 */
    public static final String[] TARGET_METHODS = {
            "getWatchScanLoginSuccess", "getWatchScanLoginFail", "intentScan", "onEvent",
            "onResume", "onDestroy", "cancelLoginFail", "cancelLoginSuccess", "verifyPassword",
    };

    /** 扫码成功时缓存的真实对象（进程内有效）。 */
    private static volatile Object cachedAutoInfo = null;

    /** 本轮只探测真实扫码流程，先关掉注入（避免干扰取样）。 */
    private static final boolean INJECT_ENABLED = false;

    /** 是否在注入后调用 getWatchScanLoginSuccess（对照实验开关）。 */
    private static final boolean CALL_CALLBACK = false;

    /** 结构只 dump 一次，避免刷日志。 */
    private static volatile boolean dumped = false;

    private WatchKeyLoginHook() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        String name = methodName(chain);

        // 扫码成功回调：结果在字段里（无参方法）→ 必须在 proceed 之前抓，之后可能被清空
        if ("getWatchScanLoginSuccess".equals(name)) {
            try {
                Object self0 = chain.getThisObject();
                if (isTarget(self0)) {
                    EventLog.INSTANCE.info("QUICK_AUTH",
                            "扫码成功回调触发，字段值：" + dumpFieldValues(self0));
                    cacheAutoInfo(self0);
                }
            } catch (Throwable t) {
                EventLog.INSTANCE.warn("HOOK_ERR", "dump 字段失败：" + t.getClass().getName());
            }
        }

        Object result = chain.proceed();

        try {
            Object self = chain.getThisObject();
            if (!isTarget(self)) {
                return result;
            }
            if ("onCreate".equals(name)) {
                EventLog.INSTANCE.info("QUICK_AUTH",
                        "WatchKeyLoginActivity 已启动；" + readInjectInfo());
                // ★ 最关键：扫码成功后官方 App 是带着 Intent 跳过来的，
                // 里面很可能就带着 watchQRCode 内容 / uuid —— 拿到它就能直接 am start 跳过扫码
                try {
                    java.lang.reflect.Method gi = self.getClass().getMethod("getIntent");
                    Object it = gi.invoke(self);
                    EventLog.INSTANCE.info("QUICK_AUTH",
                            "启动 Intent=" + describeDeep(it, 1));
                } catch (Throwable ignored) {
                }
                if (!dumped) {
                    dumped = true;
                    dumpStructure(self.getClass());
                    dumpAutoInfoType(self);
                }
                // 注入改到 onResume：onCreate 时 carSelectList 常还没加载
            } else if ("onResume".equals(name)) {
                if (!injected && injectRequested()) {
                    injected = true;
                    tryInject(self);
                }
            } else if ("onDestroy".equals(name)) {
                StringBuilder sb = new StringBuilder("onDestroy 调用栈：");
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 0; i < Math.min(st.length, 16); i++) {
                    sb.append(st[i].getClassName()).append('.')
                            .append(st[i].getMethodName()).append(" < ");
                }
                EventLog.INSTANCE.info("QUICK_AUTH", sb.toString());
            } else if ("cancelLoginFail".equals(name)) {
                Object arg = safeArg(chain, 0);
                EventLog.INSTANCE.warn("QUICK_AUTH", "cancelLoginFail：分支=失败"
                        + (arg == null ? "" : " " + CommandTypeDump.describeFields(arg)));
            } else if ("cancelLoginSuccess".equals(name)) {
                EventLog.INSTANCE.info("QUICK_AUTH", "cancelLoginSuccess：分支=成功");
            } else if ("verifyPassword".equals(name)) {
                EventLog.INSTANCE.info("QUICK_AUTH", "verifyPassword：进入密码验证阶段");
            } else if ("intentScan".equals(name)) {
                EventLog.INSTANCE.info("QUICK_AUTH", "intentScan() 被调用（点了扫一扫）");
            } else if ("onActivityResult".equals(name)) {
                dumpActivityResult(chain);
            } else if ("onEvent".equals(name)) {
                Object arg = safeArg(chain, 0);
                if (arg == null) {
                    EventLog.INSTANCE.info("QUICK_AUTH", "onEvent 收到 null");
                } else {
                    Object type = getField(arg, "type");
                    Object key = getField(arg, "key");
                    Object obj = getField(arg, "obj");
                    EventLog.INSTANCE.info("QUICK_AUTH",
                            "onEvent type=" + type + " key=" + key
                                    + " obj=" + describeDeep(obj, 2));
                }
            } else if ("getWatchScanLoginFail".equals(name)) {
                Object arg = safeArg(chain, 0);
                EventLog.INSTANCE.warn("QUICK_AUTH", "扫码失败回调："
                        + (arg == null ? "null" : arg.getClass().getSimpleName() + " "
                        + CommandTypeDump.describeFields(arg)));
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("HOOK_ERR", "WatchKeyLoginHook 异常：" + t.getClass().getName());
        }
        return result;
    }

    // ---------------- 基础工具 ----------------

    private String methodName(XposedInterface.Chain chain) {
        try {
            Executable ex = chain.getExecutable();
            return ex instanceof Method ? ((Method) ex).getName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isTarget(Object self) {
        return self != null && TARGET_CLASS.equals(self.getClass().getName());
    }

    private Object safeArg(XposedInterface.Chain chain, int i) {
        try {
            return chain.getArg(i);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读注入文件：只回报长度，不打印内容（payload 属敏感标识）。 */
    private String readInjectInfo() {
        try {
            File f = new File(INJECT_FILE);
            return f.exists() ? "注入文件存在（" + f.length() + " 字节，base64）" : "无注入文件";
        } catch (Throwable t) {
            return "读取注入文件失败：" + t.getClass().getSimpleName();
        }
    }

    private boolean injectRequested() {
        if (!INJECT_ENABLED) {
            return false;
        }
        try {
            File f = new File(INJECT_FILE);
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void cacheAutoInfo(Object self) {
        try {
            Object v = getField(self, "selectedAutoInfo");
            if (v != null) {
                cachedAutoInfo = v;
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 注入 ----------------

    /**
     * 执行注入：构造/复用 `DiLinkAutoInfo` → 赋给 `selectedAutoInfo` →
     * 调 `getWatchScanLoginSuccess()`，等价于「扫码成功」。
     */
    private void tryInject(Object self) {
        try {
            Object info = cachedAutoInfo;
            String src = "缓存";
            if (info == null) {
                info = fromCarList(self);
                src = "官方车辆列表";
            }
            if (info == null) {
                Class<?> infoCls = null;
                Field ef = findField(self.getClass(), "selectedAutoInfo");
                if (ef != null) {
                    infoCls = ef.getType();
                }
                info = buildAutoInfo(infoCls);
                src = "自建";
            }
            if (info == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "注入失败：无法构造 DiLinkAutoInfo");
                return;
            }
            setField(self, "selectedAutoInfo", info);
            // 只赋值不回调：先验证「回调是否就是 Activity 被 finish 的原因」
            if (CALL_CALLBACK) {
                invokeNoArg(self, "getWatchScanLoginSuccess");
            }
            EventLog.INSTANCE.info("QUICK_AUTH",
                    "已注入 selectedAutoInfo（来源：" + src + "），页面应显示车辆待确认");
            // 一次性消费：删掉注入文件，避免下次打开授权页又自动注入
            try {
                new File(INJECT_FILE).delete();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.error("QUICK_AUTH", "注入异常：" + t.getClass().getName()
                    + " msg=" + t.getMessage());
        }
    }

    /** 上一次注入是否已完成（onResume 会多次触发）。 */
    private static volatile boolean injected = false;

    /**
     * 从官方 App 自己的车辆列表 `carSelectList` 取第一辆车。
     * 比自建对象可靠：字段完整、与官方登录态一致，且不依赖模块凭据。
     */
    private Object fromCarList(Object self) {
        try {
            Object v = getField(self, "carSelectList");
            if (v instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) v;
                EventLog.INSTANCE.info("QUICK_AUTH", "carSelectList size=" + l.size());
                if (!l.isEmpty()) {
                    return l.get(0);
                }
            } else {
                EventLog.INSTANCE.info("QUICK_AUTH", "carSelectList="
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "取 carSelectList 失败："
                    + t.getClass().getSimpleName());
        }
        return null;
    }

    /** 用本机凭据构造 DiLinkAutoInfo（只覆盖登录必需字段）。 */
    private Object buildAutoInfo(Class<?> cls) {
        try {
            if (cls == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH",
                        "自建失败：拿不到 selectedAutoInfo 的字段类型");
                return null;
            }
            Constructor<?> ct = cls.getDeclaredConstructor();
            ct.setAccessible(true);
            Object o = ct.newInstance();
            com.bydhooker.autostart.model.Credential cred =
                    com.bydhooker.autostart.core.CredentialVault.INSTANCE.current();
            if (cred == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "自建失败：无凭据");
                return null;
            }
            setField(o, "vin", cred.getVin());
            setField(o, "autoAlias", cred.getAutoType());
            setField(o, "tboxVersion", cred.getTboxVersion());
            setField(o, "channel", Integer.valueOf(1));
            return o;
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "构造 DiLinkAutoInfo 失败："
                    + t.getClass().getName());
            return null;
        }
    }

    // ---------------- 反射工具 ----------------

    private static Object getField(Object target, String name) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null || value == null) {
                return;
            }
            f.setAccessible(true);
            Class<?> ft = f.getType();
            if (ft == int.class || ft == Integer.class) {
                f.set(target, Integer.valueOf(String.valueOf(value)));
            } else if (ft == long.class || ft == Long.class) {
                f.set(target, Long.valueOf(String.valueOf(value)));
            } else if (ft == boolean.class || ft == Boolean.class) {
                f.set(target, Boolean.valueOf(String.valueOf(value)));
            } else if (ft == String.class) {
                f.set(target, String.valueOf(value));
            } else {
                f.set(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "调用 " + name + " 失败："
                        + t.getClass().getName());
                return null;
            }
        }
        return null;
    }

    // ---------------- 探测 ----------------

    private void dumpActivityResult(XposedInterface.Chain chain) {
        try {
            Object req = safeArg(chain, 0);
            Object res = safeArg(chain, 1);
            Object data = safeArg(chain, 2);
            StringBuilder sb = new StringBuilder("onActivityResult：requestCode=")
                    .append(req).append(" resultCode=").append(res);
            if (data instanceof android.content.Intent) {
                android.content.Intent it = (android.content.Intent) data;
                android.os.Bundle b = it.getExtras();
                sb.append(" extras={");
                if (b != null) {
                    for (String k : b.keySet()) {
                        String v = String.valueOf(b.get(k));
                        sb.append(k).append('=')
                                .append(v.length() > 200 ? v.substring(0, 200) + "…" : v)
                                .append("; ");
                    }
                }
                sb.append("} data=").append(it.getDataString());
            } else {
                sb.append(" data=").append(data == null ? "null" : data.getClass().getName());
            }
            EventLog.INSTANCE.info("QUICK_AUTH", sb.toString());
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("HOOK_ERR", "onActivityResult dump 失败："
                    + t.getClass().getName());
        }
    }

    private void dumpStructure(Class<?> cls) {
        try {
            StringBuilder sb = new StringBuilder("结构 dump：").append(cls.getName());
            sb.append(" | 父类链：");
            for (Class<?> c = cls.getSuperclass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                sb.append(c.getSimpleName()).append(" < ");
            }
            sb.append(" | 字段：");
            int n = 0;
            for (Field f : cls.getDeclaredFields()) {
                if (n++ >= 25) {
                    sb.append("…");
                    break;
                }
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            }
            sb.append(" | 方法：");
            n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (n++ >= 40) {
                    sb.append("…");
                    break;
                }
                String lower = m.getName().toLowerCase();
                if (lower.contains("scan") || lower.contains("qr") || lower.contains("code")
                        || lower.contains("decode") || lower.contains("result")
                        || lower.contains("bind") || lower.contains("auth")
                        || lower.contains("watch")) {
                    sb.append("★");
                }
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") ");
            }
            EventLog.INSTANCE.info("QUICK_AUTH", sb.toString());
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "结构 dump 失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 深度描述任意对象（用于看 EventBus 事件里到底装了什么）。
     * 扫码结果对象里可能藏着 uuid / watchImei / 二维码原文，是伪造注入的关键。
     */
    private String describeDeep(Object o, int depth) {
        if (o == null) {
            return "null";
        }
        String cn = o.getClass().getName();
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) {
            String v = String.valueOf(o);
            return v.length() > 300 ? v.substring(0, 300) + "…" : v;
        }
        StringBuilder sb = new StringBuilder(cn);
        if (depth <= 0) {
            return sb.toString();
        }
        if (o instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) o;
            sb.append("[size=").append(c.size()).append("]");
            int i = 0;
            for (Object e : c) {
                if (i++ >= 3) {
                    sb.append(" …");
                    break;
                }
                sb.append(" {").append(describeDeep(e, depth - 1)).append('}');
            }
            return sb.toString();
        }
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            sb.append("[len=").append(n).append("]");
            for (int i = 0; i < Math.min(n, 3); i++) {
                sb.append(" {").append(describeDeep(java.lang.reflect.Array.get(o, i), depth - 1))
                        .append('}');
            }
            return sb.toString();
        }
        if (o instanceof android.content.Intent) {
            android.content.Intent it = (android.content.Intent) o;
            sb.append("(action=").append(it.getAction()).append(" data=").append(it.getDataString())
                    .append(" extras=");
            android.os.Bundle b = it.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    String v = String.valueOf(b.get(k));
                    sb.append(k).append('=').append(v.length() > 200 ? v.substring(0, 200) + "…" : v)
                            .append("; ");
                }
            }
            return sb.append(')').toString();
        }
        // 普通 bean：展开字段（跳过静态/合成）
        sb.append('{');
        int n = 0;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (n++ >= 14) {
                    sb.append("…");
                    break;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    sb.append(f.getName()).append('=')
                            .append(v instanceof CharSequence || v instanceof Number
                                    || v instanceof Boolean || v == null
                                    ? String.valueOf(v) : describeDeep(v, depth - 1))
                            .append("; ");
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.append('}').toString();
    }

    /** dump `selectedAutoInfo` 类型的字段与构造器（用于自建对象）。 */

    private void dumpAutoInfoType(Object self) {
        try {
            Field f = findField(self.getClass(), "selectedAutoInfo");
            if (f == null) {
                EventLog.INSTANCE.warn("QUICK_AUTH", "未找到 selectedAutoInfo 字段");
                return;
            }
            Class<?> t = f.getType();
            StringBuilder sb = new StringBuilder("selectedAutoInfo 类型：").append(t.getName());
            sb.append(" | 字段：");
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field ff : c.getDeclaredFields()) {
                    if (ff.getName().startsWith("this$") || ff.getName().startsWith("$")) {
                        continue;
                    }
                    sb.append(ff.getName()).append(':').append(ff.getType().getSimpleName())
                            .append(' ');
                }
            }
            sb.append(" | 构造器：");
            for (Constructor<?> ct : t.getDeclaredConstructors()) {
                sb.append('(');
                Class<?>[] ps = ct.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") ");
            }
            EventLog.INSTANCE.info("QUICK_AUTH", sb.toString());
        } catch (Throwable th) {
            EventLog.INSTANCE.warn("QUICK_AUTH", "dumpAutoInfoType 失败："
                    + th.getClass().getSimpleName());
        }
    }

    /** dump 关键字段的运行时值（扫码成功时调用），用于对照注入是否生效。 */
    private String dumpFieldValues(Object self) {
        StringBuilder sb = new StringBuilder();
        String[] keys = {
                "selectedAutoInfo", "authorizedCar", "identifyCar", "otherCar",
                "isSet", "selectedPosition",
        };
        for (String k : keys) {
            try {
                Object v = getField(self, k);
                String sv;
                if (v == null) {
                    sv = "null";
                } else if (v instanceof CharSequence || v instanceof Number
                        || v instanceof Boolean) {
                    sv = String.valueOf(v);
                } else {
                    sv = v.getClass().getSimpleName() + ":" + String.valueOf(v);
                    if (sv.length() > 200) {
                        sv = sv.substring(0, 200) + "…";
                    }
                }
                sb.append(k).append('=').append(sv).append("; ");
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }
}

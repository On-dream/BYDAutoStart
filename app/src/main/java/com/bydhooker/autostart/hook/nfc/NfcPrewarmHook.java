package com.bydhooker.autostart.hook.nfc;

import android.content.Context;
import android.util.Log;

import com.bydhooker.autostart.wake.NfcWakeClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 刷卡界面 Hook（运行在 `com.miui.tsmclient` 进程内）—— 承担两件事（`docs/30` §5.2 / §5.10.2）。
 *
 * 【① 预热】用户双击电源键（或从钱包）打开刷卡界面时，通知模块把宿主拉起来。
 *   不预热的代价：宿主冷启含 LSPosed 注入 + Hook 装配约 3~5 s，而「刷卡 → 开窗」只有一两秒，
 *   端到端会顶到 8 s 以上，用户感知是「刷了没反应」。
 *
 * 【② 采卡列表】`CardInfoManager` 在 **本进程** 的 ClassLoader 里，模块 App 进程加载不到
 *   （探针 `CardProbe.java:116` 实证：`Class.forName(CLS_MANAGER, true, cl)`）。
 *   设置页那张「NFC 触发卡」卡要靠这里采集后经 Provider 传过去。
 *
 * 【为什么挂在 DoubleClickActivity 而不是别的类】
 *   `26` §6 已实测这是「双击电源键 → 刷卡页」的入口 Activity；`onResume` 每次界面可见都会走，
 *   包括切卡后返回（正好覆盖「换了张卡再刷」的场景）。
 *
 * 【安全边界】
 *   · 只读反射（`getProprietaryCarKeyCards` / 字段），**不调用任何 install / activate**
 *   · 全部异常吞掉只记 logcat —— 绝不能因为模块的问题让刷卡页崩
 *   · 采集放独立线程（反射 + 列表遍历不压主线程）
 */
public final class NfcPrewarmHook implements XposedInterface.Hooker {

    private static final String TAG = "BYDHOOKER_NFC";

    /** 刷卡页入口 Activity（`26` §6 实测；OTA 漂移时只改这里）。 */
    public static final String TARGET_CLASS = "com.miui.tsmclient.ui.quick.DoubleClickActivity";

    /** 卡管理器（与探针同一常量，别写错包名）。 */
    private static final String CLS_MANAGER = "com.miui.tsmclient.entity.CardInfoManager";

    /** 两次采集的最小间隔：`onResume` 会因切卡/返回频繁触发，没必要每次都读。 */
    private static final long COLLECT_INTERVAL_MS = 30_000L;

    private static volatile long sLastCollectTs = 0L;
    private static volatile boolean sCollecting = false;

    /** 补报 Hook 三态的最小间隔（见 [resendHookState]）。 */
    private static final long HOOK_RESEND_INTERVAL_MS = 60_000L;
    private static volatile long sLastHookResendTs = 0L;

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        try {
            Object thiz = chain.getThisObject();
            if (thiz instanceof Context) {
                final Context ctx = (Context) thiz;
                // 架构 C 第三期：`sendPrewarm` 已随 B 架构删除。
                // 预热原本是「刷卡前把宿主拉起来」，现在执行者就在本进程，没有预热对象。
                // 这个 Hook 只剩两件事：补报三态、采集卡列表（都是设置页 UI 数据）。
                // v3.0.3 追加第三件：启动 M2 基线轮询（2s 一帧、最长 30s），
                // 刷卡时基线已就位 ⇒ 免等云端门锁边沿，直接下发上电（见 NfcLocalExec）。
                TsmNfcLog.log(ctx, "PREWARM", "trigger=onResume|cards+hookstate+baseline");
                boolean local = false;
                try {
                    local = NfcLocalExec.isLocalMode();
                } catch (Throwable ignored) {
                }
                if (local) {
                    NfcBaseline.start();
                }
                // 补报 Hook 三态（60 s 节流）：模块升级后旧状态作废，见 resendHookState 注释
                resendHookState(ctx);
                collectAsync(ctx, thiz.getClass().getClassLoader());
            }
        } catch (Throwable t) {
            Log.w(TAG, "PREWARM_HOOK_ERR|" + t.getClass().getSimpleName());
        }
        return chain.proceed();
    }

    /**
     * 补报 Hook 三态（60 s 节流）。
     *
     * 【为什么需要】2026-11-12 18:42 真机：模块端日志出现
     * `NFC_HOOK_MISS 未检测到小米智能卡 Hook（L1=false L2=false L3=false）`，
     * 但同一时刻 tsmclient 明明在正常转发刷卡事件 —— 分类讨论后确认是**状态过期**：
     * `NfcLocalState` 从 2.0.4 起把 Hook 状态**绑定模块版本码**（防「假绿」），
     * 模块一升级旧状态即作废；而 [NfcHookProbe#report] 只在三态**变化**时才发，
     * tsmclient 进程不重启就永远不会重报 ⇒ 模块侧一直认为「未注入」⇒ 首页 ①-N 卡消失。
     *
     * 【修法】每次打开刷卡页（`onResume`）顺手补报一次。能走到这里说明 Hook 本身就是活的，
     * 补报只是把「版本码对得上」这个事实同步给模块。60 s 节流，避免切卡来回点时反复唤醒模块。
     */
    private static void resendHookState(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - sLastHookResendTs < HOOK_RESEND_INTERVAL_MS) {
            return;
        }
        sLastHookResendTs = now;
        try {
            NfcHookProbe.retryReport();
        } catch (Throwable t) {
            Log.w(TAG, "HOOK_RESEND_ERR|" + t.getClass().getSimpleName());
        }
    }

    /**
     * 【2026-11-23 新增】注入成功时主动采集一次卡列表。
     *
     * 原先 `collectAsync` **只在刷卡页 `onResume` 调用**（`intercept`），于是：
     *  · 用户不打开刷卡页 ⇒ 卡列表永远不采集；
     *  · 重装模块后 SP 清空 ⇒ 同样永远为空；
     *  · **重启手机也不解决** —— 重启只让 tsmclient 重新注入（HOOK 三态回来、
     *    首页 ①-N 卡显示），但注入路径不触发采集，设置页「NFC 触发卡」依旧没有。
     * 这正是用户反馈「首页有 NFC 卡、设置页没有触发卡、重启也没用」的完整成因。
     *
     * 延迟 2.5 s 是等 Application / 用户服务初始化完（注入发生在 `attach` 阶段，
     * 立刻反射 `getProprietaryCarKeyCards` 可能拿到空列表）。
     * 采集本身有 30 s 节流，重复调用安全。
     */
    public static void collectOnAttach(final Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        collectAsync(ctx, ctx.getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
            }, 2500L);
        } catch (Throwable ignored) {
        }
    }

    /** 后台采集卡列表（30 s 内只做一次；已在采集中则跳过）。 */
    private static void collectAsync(final Context ctx, final ClassLoader cl) {
        long now = System.currentTimeMillis();
        if (now - sLastCollectTs < COLLECT_INTERVAL_MS || sCollecting) {
            return;
        }
        sCollecting = true;
        sLastCollectTs = now;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = collect(ctx, cl);
                    if (json != null) {
                        Log.i(TAG, "CARDS_COLLECT|json=" + json);
                        TsmNfcLog.log(ctx, "CARDS_COLLECT", "json=" + json);
                        NfcWakeClient.sendCards(ctx, json);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "CARDS_COLLECT_ERR|" + e.getClass().getSimpleName());
                } finally {
                    sCollecting = false;
                }
            }
        }, "bydhooker-card-collect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 读「本机已开通的厂家车钥匙卡」并序列化成 JSON。
     *
     * 用 `getProprietaryCarKeyCards(null)` 而**不是** `getAll()`：
     * 后者是 70 条混合卡目录（含公交卡/门禁卡/未开通项），前者只返回已开通的车钥匙卡（D6 实测 1 条）。
     * 再用 `isTraditionalCarKeyCard()` 过滤掉非传统车钥匙（`ProprietaryCarKeyCardInfo:138` 恒 true）。
     *
     * @return JSON 数组字符串；取不到返回 null（不是空数组 —— 空数组会覆盖掉模块里已有的列表）
     */
    private static String collect(Context ctx, ClassLoader cl) {
        try {
            Class<?> mgrCls = Class.forName(CLS_MANAGER, true, cl);
            Method getInstance = mgrCls.getDeclaredMethod("getInstance", Context.class);
            getInstance.setAccessible(true);
            Object mgr = getInstance.invoke(null, ctx.getApplicationContext());
            if (mgr == null) {
                Log.w(TAG, "CARDS_MGR_NULL");
                return null;
            }
            Method list = null;
            for (Method m : mgrCls.getDeclaredMethods()) {
                if (m.getName().equals("getProprietaryCarKeyCards") && m.getParameterTypes().length == 1) {
                    list = m;
                    break;
                }
            }
            if (list == null) {
                Log.w(TAG, "CARDS_METHOD_MISS|getProprietaryCarKeyCards");
                return null;
            }
            list.setAccessible(true);
            Object r = list.invoke(mgr, new Object[]{null});
            if (!(r instanceof List)) {
                return null;
            }
            JSONArray arr = new JSONArray();
            for (Object card : (List<?>) r) {
                if (card == null) {
                    continue;
                }
                // 只保留传统车钥匙卡；其余（蓝牙钥匙/数字钥匙等）不进列表
                if (!"true".equals(strOf(card, "isTraditionalCarKeyCard"))) {
                    continue;
                }
                String aid = strOf(card, "getAid");
                if (aid.isEmpty()) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("aid", aid);
                o.put("cid", strOf(card, "getCid"));
                // name 仅用于显示，**严禁作判据**（`mCardName` 用户可改）
                o.put("name", strOf(card, "getCardName"));
                arr.put(o);
            }
            return arr.toString();
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "CARDS_CNFE|" + CLS_MANAGER);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "CARDS_ERR|" + t.getClass().getSimpleName() + "|" + t.getMessage());
            return null;
        }
    }

    /** 字段名或零参 getter 都能取；取不到返回空串，不抛异常（与探针同一实现）。 */
    private static String strOf(Object o, String name) {
        if (o == null) {
            return "";
        }
        try {
            Field f = findField(o.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(o);
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = findNoArgMethod(o.getClass(), name);
            if (m != null) {
                m.setAccessible(true);
                Object v = m.invoke(o);
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}

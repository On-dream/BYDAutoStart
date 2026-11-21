package com.bydhooker.autostart.wake;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * NFC 本地状态（模块进程内，**不走 remote prefs**）—— 只给设置页读，不进宿主。
 *
 * 【为什么单独存一份】
 * `nfc.hook.*` 与卡列表都产生在**模块自己的进程**（Provider 被调用时），
 * 消费方也是**模块自己的设置页**（同一个 App，不同进程？不是 —— 同一 uid 同一进程名）。
 * 因此不需要绕 remote prefs，直接写本 App 的私有 SP，设置页同进程直读，零延迟。
 *
 * 【与 remote prefs 的分工（别混）】
 *  · remote prefs（`BridgeContract.PREFS_GROUP`）= 模块 ↔ 宿主 的通道，存**业务配置与事件**
 *  · 本 SP（`bydhooker_nfc_state`）= 模块内部状态，存**只给 UI 看的东西**
 * 混用会导致：① 宿主读到一堆自己不认识的状态键；② 写 remote prefs 要等桥绑定，
 * 而 UI 状态没有这个必要。
 */
public final class NfcLocalState {

    private static final String TAG = "BYDHOOKER_NFC";
    private static final String SP = "bydhooker_nfc_state";

    public static final String K_L1 = "hook.l1";
    public static final String K_L2 = "hook.l2";
    public static final String K_L3 = "hook.l3";
    public static final String K_LAST_HOOK_TS = "hook.ts";
    /** 卡列表 JSON：`[{"aid":"…","cid":"…","name":"…"}]`（文档 §3.2 kind=cards 的载荷原样落地）。 */
    public static final String K_CARDS_JSON = "cards.json";
    public static final String K_CARDS_TS = "cards.ts";

    /**
     * 写入 Hook 状态时的**模块版本码**（`BuildConfig.VERSION_CODE`）。
     *
     * 【为什么要有这个键 —— 2026-11-12 07:5x 真车 bug 的修复】
     * 「小米智能卡有没有被注入」这件事，**只有注入成功的那个进程能作证**。
     * 但模块一升级，之前成功注入时写下的 `l1/l2/l3=true` 会**留在 SP 里**，
     * 于是首页照样显示「NFC解锁自动上电」卡片 —— 但小米智能卡里跑的还是旧模块代码，
     * 甚至根本没被注入（本次真车故障：LSPosed 作用域里没有 `com.miui.tsmclient`，
     * 日志里 `MODULE_LOADED` 只出现在比亚迪 App 上，tsmclient 里一行都没有）。
     * **结果就是「界面说能用、实际刷卡毫无反应」**，用户完全无从判断。
     *
     * 绑定版本码后：模块升级 ⇒ 旧状态自动失效（`readHookLevel` 返回全 false）
     * ⇒ 首页 ①-N 卡**自动隐藏** ⇒ 直到小米智能卡真的被注入并上报状态，卡片才回来。
     * 「卡片不见了」本身就是最直观的故障信号。
     */
    public static final String K_HOOK_VER = "hook.ver";

    /** v3.0.1：root 拉起 tsmclient 的节流时间戳（见 [TsmclientWaker]）。 */
    public static final String K_WAKE_TS = "wake.ts";

    private NfcLocalState() {
    }

    private static SharedPreferences sp(Context c) {
        if (c == null) {
            return null;
        }
        try {
            return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Hook 三层状态（L1 模块已加载 / L2 目标方法已找到 / L3 Hook 已装上）。
     * 首页 ①-N 卡的显示条件 = `l1 && l2 && l3`（`docs/29` §5.1 Q2=乙，L4 不作门槛）。
     *
     * 同时写入**当前模块版本码**（见 [K_HOOK_VER]）：只有「本次模块版本真的被注入过」
     * 的状态才算数，旧版本留下的残留一律作废。
     */
    public static void setHookLevel(Context c, String l1, String l2, String l3) {
        SharedPreferences p = sp(c);
        if (p == null) {
            return;
        }
        try {
            p.edit()
                    .putBoolean(K_L1, "1".equals(l1) || "true".equals(l1))
                    .putBoolean(K_L2, "1".equals(l2) || "true".equals(l2))
                    .putBoolean(K_L3, "1".equals(l3) || "true".equals(l3))
                    .putLong(K_LAST_HOOK_TS, System.currentTimeMillis())
                    .putInt(K_HOOK_VER, com.bydhooker.autostart.BuildConfig.VERSION_CODE)
                    .commit();
        } catch (Throwable t) {
            Log.w(TAG, "LOCAL_HOOK_ERR|" + t.getClass().getSimpleName());
        }
    }

    /**
     * 卡列表（tsmclient 侧采集后经 Provider 传来）。
     * **内容相同则跳过写入** —— 每次打开刷卡界面都会上报一次，无谓写盘没有意义。
     */
    public static void setCards(Context c, String json) {
        SharedPreferences p = sp(c);
        if (p == null || json == null) {
            return;
        }
        try {
            String old = p.getString(K_CARDS_JSON, "");
            if (json.equals(old)) {
                return;
            }
            p.edit()
                    .putString(K_CARDS_JSON, json)
                    .putLong(K_CARDS_TS, System.currentTimeMillis())
                    .commit();
        } catch (Throwable t) {
            Log.w(TAG, "LOCAL_CARDS_ERR|" + t.getClass().getSimpleName());
        }
    }

    /**
     * 读 Hook 三层状态。
     *
     * **版本校验**（2026-11-12 修复，见 [K_HOOK_VER]）：SP 里记录的版本码
     * 与当前模块版本不一致 ⇒ 视为**从未注入过**，返回全 false。
     * 这样「模块升级但小米智能卡没重新注入」的情况不会继续显示 ①-N 卡。
     */
    public static boolean[] readHookLevel(Context c) {
        boolean[] r = new boolean[]{false, false, false};
        SharedPreferences p = sp(c);
        if (p == null) {
            return r;
        }
        try {
            int ver = p.getInt(K_HOOK_VER, -1);
            if (ver != com.bydhooker.autostart.BuildConfig.VERSION_CODE) {
                Log.i(TAG, "HOOK_VER_MISMATCH|recorded=" + ver
                        + "|current=" + com.bydhooker.autostart.BuildConfig.VERSION_CODE
                        + "|treat_as_not_injected");
                return r;
            }
            r[0] = p.getBoolean(K_L1, false);
            r[1] = p.getBoolean(K_L2, false);
            r[2] = p.getBoolean(K_L3, false);
        } catch (Throwable ignored) {
        }
        return r;
    }

    public static String readCardsJson(Context c) {
        SharedPreferences p = sp(c);
        if (p == null) {
            return "";
        }
        try {
            String s = p.getString(K_CARDS_JSON, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * `hook.ver` 是否已过期（模块升级后 tsmclient 未重新注入 ⇒ 卡片被版本校验隐藏）。
     *
     * 【2026-11-13 真机实证】tsmclient 主进程**开机不自启**（开机 22 分钟 `pidof` 为空），
     * 模块升级也不会让它重启 ⇒ SP 里的 ver 停在旧值 ⇒ 卡片长期不显示，
     * 只能手动结束小米智能卡/NFC 才回来。修复见 `TsmclientWaker`。
     */
    public static boolean isHookVerStale(Context c) {
        SharedPreferences p = sp(c);
        if (p == null) {
            return false;
        }
        try {
            return p.getInt(K_HOOK_VER, -1)
                    != com.bydhooker.autostart.BuildConfig.VERSION_CODE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long readWakeTs(Context c) {
        SharedPreferences p = sp(c);
        if (p == null) {
            return 0L;
        }
        try {
            return p.getLong(K_WAKE_TS, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void writeWakeTs(Context c, long ts) {
        SharedPreferences p = sp(c);
        if (p == null) {
            return;
        }
        try {
            p.edit().putLong(K_WAKE_TS, ts).commit();
        } catch (Throwable ignored) {
        }
    }
}

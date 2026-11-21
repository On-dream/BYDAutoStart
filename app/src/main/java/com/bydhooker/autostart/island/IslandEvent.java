package com.bydhooker.autostart.island;

import android.os.Bundle;

import com.bydhooker.autostart.bridge.ConfigStore;

/**
 * 超级岛事件协议（架构 C · 乙-B）。
 *
 * 【通道】执行进程 → SystemUI：{@code content://miui.statusbar.notification.public}
 * 的自定义 method {@link #METHOD}；SystemUI 侧 {@code IslandBusHook} 前置短路接收。
 * 之所以走 Provider：项目实证「Greezer 冻结后台应用后广播直接丢弃，Provider 在
 * 冻结/强停下都能穿透」；且 SystemUI 是常驻系统进程，不存在被冻结的接收方。
 *
 * 【只有展示链】本类不参与任何判据/下发，事件丢失最多是没岛，绝不影响上电。
 */
public final class IslandEvent {

    /** SystemUI 的公开通知 Provider（本机 dumpsys package 实测）。 */
    public static final String URI = "content://miui.statusbar.notification.public";
    /** 自定义 method；非本 method 一律原样放行，不改系统行为。 */
    public static final String METHOD = "bydhk_island";

    // ---- 事件类型（对应 docs/37 §8.3 四态状态机）----
    /** S0 开窗（仅 M1）。 */
    public static final String TYPE_OPEN = "OPEN";
    /** S0 窗口顺延，只更新 deadline。 */
    public static final String TYPE_REFRESH = "REFRESH";
    /** S1 起点：上电指令已被受理（CommandSender.sendStart 成功）。 */
    public static final String TYPE_SEND = "SEND";
    /** S3：上电指令下发失败。 */
    public static final String TYPE_FAIL = "FAIL";
    /** S2：复核确认车已上电。 */
    public static final String TYPE_VERIFY_OK = "VERIFY_OK";
    /** S3：复核到上限仍未确认（不是失败、不重试）。 */
    public static final String TYPE_VERIFY_PENDING = "VERIFY_PENDING";
    /** 窗口关闭/未命中，立即收岛。 */
    public static final String TYPE_CLOSE = "CLOSE";

    public static final String K_TYPE = "type";
    public static final String K_TS = "ts";
    public static final String K_DEADLINE = "deadline";
    public static final String K_SOURCE = "source";
    public static final String K_MODE = "mode";
    public static final String K_SEQ = "seq";
    public static final String K_ELAPSED = "elapsed";
    public static final String K_WINDOW = "windowSeconds";

    private IslandEvent() {
    }

    /**
     * 构造事件 Bundle。{@code mode} 与 {@code windowSeconds} 从本进程的 ConfigStore 读
     * （三源各自持有同一份配置：官方 App/tsmclient 走 remote prefs，system_server 走 config.txt），
     * 这样调用点不需要额外传配置，也不新增配置同步通道。
     */
    public static Bundle bundle(String type, long ts, long deadline, String source,
                                long seq, long elapsed) {
        Bundle b = new Bundle();
        b.putString(K_TYPE, type);
        b.putLong(K_TS, ts);
        b.putLong(K_DEADLINE, deadline);
        b.putString(K_SOURCE, source == null ? "" : source);
        b.putString(K_MODE, safeMode());
        b.putLong(K_SEQ, seq);
        b.putLong(K_ELAPSED, elapsed);
        b.putInt(K_WINDOW, safeWindow());
        return b;
    }

    private static String safeMode() {
        try {
            return ConfigStore.INSTANCE.getMode();
        } catch (Throwable t) {
            return "M1";
        }
    }

    private static int safeWindow() {
        try {
            return ConfigStore.INSTANCE.getWindowSeconds();
        } catch (Throwable t) {
            return 60;
        }
    }
}

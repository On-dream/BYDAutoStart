package com.bydhooker.autostart.wake;

import android.net.Uri;

/**
 * 模块 App 与小米智能卡（tsmclient）之间**仅存的**跨进程通道常量。
 *
 * 【架构 C 第三期清理后】这个 Provider 只干两件事：
 *   ① `KIND_HOOK`  —— 把 tsmclient 侧 Hook 的 L1/L2/L3 三态送进模块私有 SP
 *   ② `KIND_CARDS` —— 把 tsmclient 侧采集到的车钥匙卡列表送进模块私有 SP
 * 两者都是**设置页 UI 的数据源**（①-N 卡是否显示、NFC 触发卡选择弹窗），
 * 与车控执行**完全无关** —— 架构 C 下三条触发链都在各自进程就地执行，
 * 不再有任何「刷卡 / 解锁 / 三击」事件经过这里。
 *
 * 【为什么还用 Provider 而不是广播】
 * HyperOS 的 Greezer 冻结后台应用后广播会被直接丢弃（`BroadcastQueue: Greezer Denial`），
 * 而 `ContentResolver.call()` 在冻结 / force-stop 两种状态都能穿透并冷启动接收方。
 * 这条结论对 UI 数据同样成立，故保留。
 *
 * 【已删除】`METHOD_WINDOW`（保活守护）、`METHOD_LAST_EVENT`（NfcEventSink 补读）、
 * `METHOD_SET_EXEC`（exec.location 双轨）、`METHOD_APP_UNLOCK` / `METHOD_MODULE_WAKE`
 * （跨进程唤醒执行层）、`METHOD_PING`、`KIND_SWIPE` / `KIND_PREWARM`（刷卡转发与预热）、
 * `MAX_EVENT_AGE_MS`（新鲜度校验，随事件转发一起失效）。
 */
public final class NfcChannel {

    public static final String AUTHORITY = "com.bydhooker.autostart.wake";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    /** 唯一保留的 method：承载 `EXTRA_KIND` 分流。 */
    public static final String METHOD_NFC = "nfc";

    public static final String RESULT_OK = "ok";
    public static final String EXTRA_KIND = "kind";

    public static final String KIND_HOOK = "hook";
    public static final String KIND_CARDS = "cards";

    public static final String EXTRA_L1 = "l1";
    public static final String EXTRA_L2 = "l2";
    public static final String EXTRA_L3 = "l3";
    public static final String EXTRA_CARDS = "cards";

    private NfcChannel() {
    }
}

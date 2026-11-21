package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.ConfigStore
import com.bydhooker.autostart.island.IslandEvent
import com.bydhooker.autostart.island.IslandReporter
import com.bydhooker.autostart.model.UnlockEvent

/**
 * 触发源（`15` §3.2 ②；B3：Hook 回调 → UnlockEvent → WindowManager）。
 * 仅回调壳，零逻辑；NFC 扩展位 = 追加 source 常量（V2，`15` §14）。
 */
object TriggerSource {

    /**
     * 解锁指令入口（Hook 回调线程调用，**绝不做网络**）。
     *
     * 判定顺序（T05）：总开关 → UNLOCK 事件 → 模式分流（`15` §2 / §4.1 / §4.2）。
     * - 总开关关闭：只落 GATE_SKIP，不产事件（`18` §7.1 文案）
     * - M1：原窗口逻辑，**一行不动**
     * - M2：无窗口、无门开判定，直接「解锁即上电」（M2Runner）
     */
    fun onUnlockCommand(source: String, ts: Long) {
        if (!ConfigStore.enabled) {
            EventLog.debug("GATE_SKIP", "未满足条件：总开关关闭")
            return
        }
        // 2026-11-12 23:4x：新增三击来源后，文案不能再写死「App 内解锁」
        val srcText = when (source) {
            "triple" -> "电源键三击"
            "nfc" -> "NFC 卡片"
            else -> "App 内解锁"
        }
        EventLog.info("UNLOCK", "检测到解锁（来源：$srcText）")
        val event = UnlockEvent(source, ts)
        if (ConfigStore.mode.equals("M2", ignoreCase = true)) {
            M2Runner.onUnlock(event)
        } else {
            WindowManager.onUnlock(event)
        }
    }

    /**
     * NFC 刷卡入口（`docs/30` §5.4；2026-11-11 22:5x）。
     *
     * 与 APP 解锁定共用同一套 M1 窗口（D10：同一 `T0`/`alreadySent`/状态机），
     * 所以这里只做「NFC 专属门控」然后交给同一个 `WindowManager.onUnlock`。
     *
     * 与 [onUnlockCommand] 的差别：
     * - 不检查 APP 方案的 `enabled`（那是 APP 卡自己的开关），只看 `nfcEnabled`
     * - 事件来源标 `nfc`，日志里能区分是刷卡还是 App 内解锁
     *
     * TODO(P2+): D9「已上电不开窗」需要 `StatusCache`（最近一帧车况）才能判，
     * 本轮先不做 —— 下发层面 Gate 的「已上电跳过」仍在兜底，多开一次窗只是多轮询，
     * 不会误下发。
     */
    fun onNfcSwipe(ts: Long) {
        if (!ConfigStore.nfcEnabled) {
            EventLog.debug("GATE_SKIP", "未满足条件：NFC 开关关闭")
            return
        }
        if (ConfigStore.nfcSelectedAid.isEmpty()) {
            // R-AID-7：没选卡就不该走到这里（模块侧已拦），留一道防线
            EventLog.debug("NFC_NO_CARD", "未选择触发卡，忽略本次刷卡")
            return
        }
        if (ConfigStore.nfcMode.equals("M2", ignoreCase = true)) {
            NfcM2Runner.onSwipe(UnlockEvent("nfc", ts))
            return
        }
        WindowManager.onUnlock(UnlockEvent("nfc", ts))
    }
}

/**
 * 窗口管理（`15` §3.2 ③ / §4.1 ③ / §13.4 Q4）。
 *
 * 内存态（B4 纯内存，进程被杀即清零；不落盘）：
 * windowStartTs / windowDeadlineTs / alreadySent / lastLf / staleStreak /
 * awakeCalledThisWindow。**刷新窗口只更新 deadline，其余不变**（防绕过 G-7）。
 */
object WindowManager {

    @Volatile private var windowDeadlineTs = 0L
    @Volatile var alreadySent = false
        private set
    @Volatile var lastLf: Int? = null
        private set
    @Volatile var staleStreak = 0
        private set
    @Volatile var awakeCalledThisWindow = false
        private set

    fun onUnlock(event: com.bydhooker.autostart.model.UnlockEvent) {
        val now = event.ts
        if (isWindowOpen(now)) {
            // Q4 裁定：同窗重复解锁 → 刷新截止时间；**不是新窗口**——
            // alreadySent / lastLf / staleStreak / awakeCalled 不重置
            windowDeadlineTs = now + ConfigStore.windowSeconds * 1000L
            EventLog.info(
                "WINDOW_REFRESH",
                "再次解锁或刷卡，等待窗口顺延 ${ConfigStore.windowSeconds} 秒（不是新窗口、不会重复下发）",
            )
            IslandReporter.report(
                IslandEvent.TYPE_REFRESH, now, windowDeadlineTs, event.source, 0L,
            )
            // 自愈：若开窗那一下 `StatusPoller.start()` 因数据源未注入而失败，
            // 顺延时补一次（start 本身幂等，已有在跑的 future 不会重复起线程）。
            // 否则会出现「窗口开着、一帧车况都不拉」，用户再击一次也救不回来。
            StatusPoller.start()
            return
        }
        // 新窗口
        windowDeadlineTs = now + ConfigStore.windowSeconds * 1000L
        alreadySent = false
        lastLf = null
        staleStreak = 0
        awakeCalledThisWindow = false
        EventLog.info("WINDOW_OPEN", "开始 ${ConfigStore.windowSeconds} 秒等待，寻找开门动作")
        IslandReporter.report(
            IslandEvent.TYPE_OPEN, now, windowDeadlineTs, event.source, 0L,
        )

        StatusPoller.start()
    }

    fun isWindowOpen(now: Long = System.currentTimeMillis()): Boolean =
        windowDeadlineTs > now

    /**
     * 窗口剩余毫秒（关窗时返回 0）。
     *
     * 供 M1 轮询给「取一帧车况」设预算：单帧最坏 3 次 HTTP，旧实现下单帧可达 39 s，
     * 若不限预算，一次卡顿就能把整个窗口（默认 60 s）拖掉大半。
     */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (windowDeadlineTs - now).coerceAtLeast(0L)

    /**
     * 窗口关闭（超时/复核结束/数据陈旧）：状态清零 + 释放窗口期线程池（零常驻）。
     *
     * 架构 C 第三期：原 `WindowWakeLock.release` / `HostWindowKeeper.close` 已随
     * 整套 B 架构保活删除。窗口现在只靠进程内定时器跑，执行者本身就在触发现场。
     */
    fun closeWindow(reason: String) {
        // 必须在重置前读：已下发时岛由 VERIFY_OK / VERIFY_PENDING 终态收尾，
        // 若这里再发 CLOSE，会把刚切到 S2 的勾动画立刻 cancel（docs/37 §8.10）。
        val sent = alreadySent
        windowDeadlineTs = 0L
        alreadySent = false
        lastLf = null
        staleStreak = 0
        awakeCalledThisWindow = false
        StatusPoller.stop()
        // 15 §3.4：线程池只在窗口期存在。用优雅关池（不打断在途复核）
        NetExecutor.shutdown()
        EventLog.info("WINDOW_CLOSE", reason)
        if (!sent) {
            // 未下发就关窗（窗口超时 / 数据陈旧 / 未开门）：切 S3「上电未确认」，
            // 短展示后退出。用户定稿 S3 要覆盖 SEND_FAIL / VERIFY_PENDING / 窗口未命中。
            IslandReporter.report(
                IslandEvent.TYPE_FAIL, System.currentTimeMillis(), 0L, "", 0L,
            )
        }
    }

    /** Gate 下发成功后置位（G-7；由执行器在 T05 接入时调用）。 */
    fun markSent() {
        alreadySent = true
    }

    fun markAwakeCalled() {
        awakeCalledThisWindow = true
    }

    fun onLfObserved(lf: Int) {
        lastLf = lf
    }

    fun onStaleFrame() {
        staleStreak++
    }

    fun onFreshFrame() {
        staleStreak = 0
    }
}


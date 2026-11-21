package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.ConfigStore
import com.bydhooker.autostart.model.UnlockEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NFC-M2 执行器（`docs/30` §5.6）—— **解锁上电**模式下的刷卡判定。
 *
 * 【为什么不复用 [M2Runner]】
 * APP 版 M2 是「解锁即下发」，不判车况；而 NFC 版必须判**门锁边沿**（见下），
 * 且 APP 版里有 `AWAIT_UNLOCK_TIMEOUT_MS = 8000` 的「超时兜底仍下发」——
 * 那条兜底在 NFC 路径**绝对不能用**：刷卡可能是上锁动作，兜底下发会把已上锁的车直接点着。
 *
 * 【判据：`leftFrontDoorLock` 的 2 → 1 边沿】
 * - `2` = 已锁、`1` = 未锁（E 路线 rt-result 实测）
 * - **必须真的看到 2 再变 1**。首帧就已经是 1（车本来就没锁）⇒ 无法证明这是本次刷卡造成的
 *   解锁 ⇒ **不触发**，只记 `NFC_M2_NO_EDGE`。这是保守设计，宁可漏也不误（`28` §3.3）
 *
 * 【时间参数】
 * - 窗口 **固定 15 s**，不可配、**不顺延**（`28` §1.3 ⑤：任何事件都不刷新 T0）
 * - 无回执、无超时兜底（`15` §4.2 ③ 的 8 s 兜底不适用于 NFC）
 *
 * 【线程】
 * 独立单线程调度器（不复用 [NetExecutor]，避免 M1 收尾关池时掐断本流程），用完即关。
 */
object NfcM2Runner {

    /** 固定窗口 15 s（不可配、不顺延）。 */
    private const val WINDOW_MS = 15_000L

    /** 门锁字段取值：已锁 / 未锁。 */
    private const val DOOR_LOCK_LOCKED = 2
    private const val DOOR_LOCK_UNLOCKED = 1

    /** 窗口截止时间（0 = 无窗口）。 */
    @Volatile
    private var deadline = 0L

    /** 上一帧观察到的门锁值（null = 还没采到）。 */
    @Volatile
    private var lastLock: Int? = null

    /** 是否已经见过「已锁」——没有它就不能判 2→1 边沿。 */
    @Volatile
    private var seenLocked = false

    private val inFlight = AtomicBoolean(false)

    /**
     * 本窗口内的 tick 序号（诊断用）。
     *
     * 【为什么需要】2026-11-12 18:41 的真车日志里，`NFC_M2_OPEN` 之后**一行都没有** ——
     * 既没有 `NFC_M2_STATUS`（拿到车况才会写），也没有 `NET_TIMEOUT`（拉取失败会写），
     * 更没有 `NFC_M2_TIMEOUT`（15 s 到点必写）。说明**轮询线程根本没跑**（宿主进程被冻住）。
     * 但光看日志无法区分「没跑」与「跑了但没数据」，所以每 tick 先记一条心跳。
     * 判读：有 TICK 无 STATUS ⇒ 网络/车况问题；连 TICK 都没有 ⇒ 进程停摆（保活失效）。
     */
    @Volatile
    private var tickNo = 0

    @Volatile
    private var exec: ScheduledExecutorService? = null

    /** 是否正在 15 s 窗口内。 */
    fun isOpen(now: Long = System.currentTimeMillis()): Boolean = deadline > now

    /**
     * 刷卡入口（在宿主进程的 Hook/事件线程调用，**不做网络**）。
     *
     * 窗口内重复刷卡：**直接丢弃，不顺延**（`28` §1.3 ⑤）。
     */
    fun onSwipe(e: UnlockEvent) {
        val now = System.currentTimeMillis()
        if (isOpen(now)) {
            EventLog.info("NFC_M2_IGNORE", "15 秒窗口内重复刷卡，不顺延")
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            EventLog.info("NFC_M2_IGNORE", "上一次判定仍在进行，忽略本次")
            return
        }

        deadline = now + WINDOW_MS
        lastLock = null
        seenLocked = false
        tickNo = 0
        EventLog.info("NFC_M2_OPEN", "开始 15 秒窗口，等待主驾门锁由已锁变为未锁")

        try {
            val ex = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "BYDHK-NFC-M2").apply { isDaemon = true }
            }
            exec = ex
            val interval = ConfigStore.pollIntervalMs.toLong().coerceAtLeast(500L)
            ex.scheduleWithFixedDelay({ tick(ex) }, 0L, interval, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            EventLog.error("NFC_M2_ERR", "调度失败：" + t.javaClass.simpleName)
            finish("调度失败")
        }
    }

    private fun tick(ex: ScheduledExecutorService) {
        try {
            val n = ++tickNo
            // 心跳（先写，再做任何可能阻塞的事）：用来区分「轮询没跑」与「跑了但没数据」
            EventLog.debug(
                "NFC_M2_TICK",
                "n=$n，剩余 ${(deadline - System.currentTimeMillis()).coerceAtLeast(0L)}ms",
            )
            if (System.currentTimeMillis() > deadline) {
                EventLog.info("NFC_M2_TIMEOUT", "15 秒内未观察到门锁变化，放弃本次")
                finish("超时")
                return
            }
            val cred = CredentialVault.current() ?: run {
                EventLog.warn("BRIDGE_UNAVAILABLE", "无法连接业务层：请先打开一次比亚迪官方 App")
                finish("无凭据")
                return
            }
            // 预算：本帧最多占 6 s，且不超过窗口剩余。
            // M2 窗口只有 15 s，而单帧最坏 3 次 HTTP（旧实现 connect5+read8 ⇒ 39 s）——
            // 不设预算的话，「网络稍慢」会表现成「15 秒内没看到门锁变化」，与判据失败无法区分。
            val budget = minOf((deadline - System.currentTimeMillis()).coerceAtLeast(0L), 6_000L)
            val frame = StatusPoller.source?.fetch(cred, budget) ?: run {
                // 单次失败不重试（G-9 精神），下一 tick 再来
                EventLog.warn("NET_TIMEOUT", "网络超时，本帧不参与判定")
                return
            }
            val lock = frame.doorLockFL ?: return
            EventLog.debug(
                "NFC_M2_STATUS",
                "age=${System.currentTimeMillis() / 1000 - frame.timeSec}s，doorLock=$lock，" +
                        "vehicleState=${frame.vehicleState}",
            )

            if (lock == DOOR_LOCK_LOCKED) {
                // 见到「已锁」是后续能判边沿的前提
                lastLock = lock
                if (!seenLocked) {
                    seenLocked = true
                }
                return
            }

            if (lock == DOOR_LOCK_UNLOCKED) {
                if (!seenLocked) {
                    // 首帧就未锁：无法确认这是本次刷卡造成的，宁可不触发
                    EventLog.warn("NFC_M2_NO_EDGE", "开窗前车辆已解锁，无法确认门锁变化，本次不触发")
                    finish("无变化沿")
                    return
                }
                EventLog.info("NFC_M2_HIT", "检测到主驾门锁由已锁变为未锁，条件成立")
                if (CommandSender.sendStart(cred)) {
                    CommandSender.verify(cred)
                }
                finish("复核完成")
            }
            // 其它取值（null / 未知 / 上锁中的 2）：不动，等下一帧
        } catch (t: Throwable) {
            EventLog.warn("NFC_M2_ERR", "判定异常：" + t.javaClass.simpleName)
        }
    }

    /** 收尾：清状态 + 关池。幂等。 */
    @Synchronized
    private fun finish(reason: String) {
        deadline = 0L
        lastLock = null
        seenLocked = false
        try {
            exec?.shutdown()
        } catch (_: Throwable) {
        } finally {
            exec = null
        }
        inFlight.set(false)
        EventLog.info("NFC_M2_CLOSE", reason)
    }

    // 架构 C 第三期：删除了窗口状态落盘 / 新进程 restore 恢复。
    // 那是「模块 App 在后台被 LMK 杀掉后靠 root 守护进程拉起」的 B 架构韧性措施；
    // 现在执行者就是 tsmclient 自己，窗口随进程生死，裸跑观察即可。
}

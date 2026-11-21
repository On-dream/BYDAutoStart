package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.crypto.WatchRequest
import com.bydhooker.autostart.model.Credential
import com.bydhooker.autostart.model.UnlockEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M2 执行链（`15` §2：**解锁即上电**）。
 *
 * 与 M1 的差别只在判定条件：
 * - **无 60 s 窗口**、**无门开判定**（不依赖 StatusPoller / Gate）
 * - **并行版（2026-11-09 用户拍板，取代 §4.2 的 A8 车况前置）**：
 *   解锁瞬间**直接下发**，不查车况、不额外唤醒，让启动请求与解锁请求几乎同时离开手机。
 *   代价是失去 G-6「已上电跳过」，已知并接受。
 *
 * 线程约束（A6）：
 * - [onUnlock] 运行在 **Hook 回调线程** —— 只做去重 + 投递，**绝不发网络**
 * - 网络与复核在 M2 专属单线程池（`BYDHK-M2`）串行，结束即 `shutdown`（零常驻）
 * - **不复用** NetExecutor：避免 M2 收尾关池时掐断 M1 窗口内的轮询
 *
 * 一次性约束（G-9）：一次解锁最多下发 1 次，**任何情况不重试**。
 */
object M2Runner {

    /** 事件级去重窗口（ms）：同一解锁的重复回调 / 多重重载命中。 */
    private const val EVENT_DEDUPE_MS = 2000L

    /** 等待解锁完成的上限（超时兜底下发，不会比旧行为差）。 */
    private const val AWAIT_UNLOCK_TIMEOUT_MS = 8000L

    /** 门锁字段「已锁」取值（E 路线 rt-result 实测；解锁后变 1）。 */
    private const val DOOR_LOCK_LOCKED = 2

    /** 信号轮询粒度（ms）：等待期间以 100 ms 检查回执，回执到达后近乎立即下发。 */
    private const val SIGNAL_POLL_MS = 100L

    /**
     * 前 N ms 只等回执、不查车况（车况一次约 1 s，会拖慢对回执的响应）。
     *
     * 【2026-11-13 16:2x：1500 → 300（架构 C 实测后调整）】
     * 真机日志（system_server 侧就地执行）：解锁→上电用了 **4863 ms**，其中
     * 前 1500 ms 是**纯浪费** —— `ReceiptSignal` 靠官方 App 的 `mqttRemoteResult` 钩子置位，
     * 而 system_server 里根本没有那个宿主进程 ⇒ **回执永远等不到**，只能白等。
     * 剩下的 3300 ms 才是「车端解锁 → 云端可见 doorLock 2→1」的物理延迟（压不动）。
     *
     * 【为什么降到 300 而不是 0】宿主侧（官方 App 进程）回执约 1 s 到达，
     * 降太快会错过它。但判据本身是 doorLock 变化，回执只是"提前量"，
     * 而 `awaitUnlockDone` 在每次车况查询之后都会补查一次回执 ⇒ 不会漏。
     * 实际上**更依赖 doorLock 反而更安全**：回执只说明云端受理，不代表车已解锁。
     */
    private const val RECEIPT_ONLY_MS = 300L

    /** 上一条解锁事件时间戳（Hook 线程写、Hook 线程读；防抖用）。 */
    @Volatile private var lastEventTs = 0L

    /** 并发闸门：一次只允许一条 M2 流程在跑。 */
    private val inFlight = AtomicBoolean(false)

    fun onUnlock(e: UnlockEvent) {
        // 只在 CAS 成功（本线程真正持有闸门）后才允许在异常里释放，
        // 否则异常发生在 CAS 之前会误清他人正在进行的流程状态。
        var acquired = false
        try {
            val last = lastEventTs
            lastEventTs = e.ts
            if (e.ts - last < EVENT_DEDUPE_MS) {
                EventLog.debug("GATE_SKIP", "未满足条件：重复解锁事件")
                return
            }
            if (!inFlight.compareAndSet(false, true)) {
                EventLog.debug("GATE_SKIP", "未满足条件：本次已在处理")
                return
            }
            acquired = true
            // M2 用**独立单线程池**，不复用 NetExecutor：
            // M2 流程结束要关池，若与 M1 共用会在 `shutdownNow` 时掐断 M1 窗口内
            // 正在跑的轮询（2026-11-09 07:48 真机实测：M1 开窗期间切到 M2，
            // M1 的 STATUS 轮询自此静默，窗口一直挂到 60 s 超时）。
            val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "BYDHK-M2").apply { isDaemon = true }
            }
            val submitted = try {
                ex.submit {
                    try {
                        runFlow(e)
                    } catch (t: Throwable) {
                        EventLog.error("SEND_FAIL", "下发流程异常：" + t.javaClass.simpleName)
                    } finally {
                        inFlight.set(false)
                        // 用完即关（零常驻）；从任务内 shutdown 不会打断自己
                        ex.shutdown()
                    }
                }
                true
            } catch (_: Throwable) {
                inFlight.set(false)
                false
            }
            if (!submitted) {
                // 投递失败（池异常）：释放闸门，避免后续解锁被永久挡住
                inFlight.set(false)
                EventLog.warn("NET_TIMEOUT", "网络超时，本次不下发")
            }
        } catch (t: Throwable) {
            if (acquired) {
                inFlight.set(false)
            }
            EventLog.error("SEND_FAIL", "下发流程异常：" + t.javaClass.simpleName)
        }
    }

    /**
     * 网络线程内串行执行：**立即下发** → 复核（A4）。
     *
     * 并行版（2026-11-09 用户拍板，取代 A8 的车况前置）：
     * - 不再 `awake` + `fetchFreshStatus` 做前置（原占 ~1 s：awake 0.3 s + rt 两步 0.9 s），
     *   解锁瞬间直接发 `RUNENGINENODRIVE`，让启动请求尽可能早出。
     * - **代价（已知并接受）**：失去 G-6「已上电跳过」保护——车已上电时再解锁会
     *   重复下发一次启动指令（E 路线未测该场景后果；实际点「解锁」时车通常锁着且未上电）。
     * - 复核仍在（[CommandSender.verify]）：只影响「确认上电」时间，不影响下发时刻。
     */
    private fun runFlow(e: UnlockEvent) {
        val cred = CredentialVault.current() ?: run {
            EventLog.warn("BRIDGE_UNAVAILABLE", "无法连接业务层：请先打开一次比亚迪官方 App")
            return
        }
        // ① 等「解锁真正完成」——两个信号取先到（2026-11-09 09:00 真机实测驱动）：
        //    并行版「解锁瞬间立即下发」在车机休眠时被云端受理但车端未执行
        //    （20 s 内 powerGear 恒为 1），必须先让解锁动作把车机唤醒。
        //    信号 A：`mqttRemoteResult` 回执（`code=0`，实测解锁后约 1 s 到达）
        //    信号 B：车况 `doorLock != 2`（2=已锁；实测解锁后 2→1，约 4~5 s）
        //    超时 8 s 仍未等到 → 兜底下发（不比旧行为差）。
        val t0 = System.currentTimeMillis()
        val ready = awaitUnlockDone(cred, t0)
        val waitMs = System.currentTimeMillis() - e.ts
        if (ready) {
            // M2 补充文案（`18` §7.1 的 GATE_HIT 是 M1「主驾门开」版）
            EventLog.info("GATE_HIT", "条件成立：解锁上电（已确认解锁完成，解锁→下发 ${waitMs} ms）")
        } else {
            EventLog.warn("STATUS", "未等到解锁完成（${waitMs} ms），兜底下发启动指令")
        }
        // 只看受理（code == "0"），不看执行；受理失败即终止，不重试（G-9）
        if (!CommandSender.sendStart(cred)) return
        CommandSender.verify(cred)
    }

    /**
     * 等待解锁真正完成（本事件最多 [AWAIT_UNLOCK_TIMEOUT_MS]）。
     *
     * @param startTs 本次解锁的起点（回执到达时间 >= 它即视为「解锁回执」）
     * @return true = 已确认解锁完成；false = 超时（调用方兜底下发）
     */
    private fun awaitUnlockDone(cred: Credential, startTs: Long): Boolean {
        val deadline = startTs + AWAIT_UNLOCK_TIMEOUT_MS
        val doorFallbackAt = startTs + RECEIPT_ONLY_MS
        while (System.currentTimeMillis() < deadline) {
            // ① 回执信号：100 ms 粒度检查（回执实测约 1 s 到达，是主判据）
            if (ReceiptSignal.receivedAfter(startTs)) {
                EventLog.debug("STATUS", "解锁回执已到（code=${ReceiptSignal.lastCode}）")
                return true
            }
            val now = System.currentTimeMillis()
            if (now < doorFallbackAt) {
                // 前 4 s 只等信号：车况查询单次约 1 s，若此时去查会把回执响应推迟约 1 s
                try {
                    Thread.sleep(SIGNAL_POLL_MS)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            // ② 回执迟迟不来 → 用门锁状态兜底（实测解锁后 2→1，约 4~5 s）
            val f = try {
                WatchRequest.fetchFreshStatus(
                    cred, BridgeContract.M2_STATUS_MAX_AGE_SEC, pollMs = 200L, maxTries = 2)
            } catch (_: Throwable) {
                null
            }
            if (f != null) {
                val age = System.currentTimeMillis() / 1000 - f.timeSec
                EventLog.debug(
                    "STATUS",
                    "等待解锁：age=${age}s，doorLock=${f.doorLockFL}，vehicleState=${f.vehicleState}，powerGear=${f.powerGear}",
                )
                if (f.doorLockFL != null && f.doorLockFL != DOOR_LOCK_LOCKED) {
                    return true
                }
            }
            // 查询期间回执可能已到，立刻补一次检查
            if (ReceiptSignal.receivedAfter(startTs)) {
                EventLog.debug("STATUS", "解锁回执已到（code=${ReceiptSignal.lastCode}）")
                return true
            }
        }
        return false
    }
}

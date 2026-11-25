package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.bridge.ConfigStore
import com.bydhooker.autostart.model.Credential
import java.util.concurrent.ScheduledFuture
import com.bydhooker.autostart.model.StatusFrame

/**
 * 车况轮询（`15` §4.1 ④ / §5 G-2..G-5d）。
 *
 * 数据来源：[StatusSource]（watch 通道 `vehicleRealTimeRequest/Result`；
 * 网络构造在 WatchRequest，T03 随 NetExecutor 集成注入——接口先行使状态机
 * 可独立单测）。
 *
 * 流程（每个 tick）：
 * 1. 取帧（网络失败 → WARN + 不计陈旧；G-9 精神：不重试单次失败）
 * 2. G-3 新鲜度：now - time > 30s → 陈旧帧**整帧丢弃**、staleStreak++、
 *    **不更新 lastLf**（G-5d 解耦）；连续 3 轮且本窗未唤醒 → 一次
 *    remoteAwakeRequest（G-4+A7，本窗最多 1 次；有效性【不知道】不默认）
 * 3. 新鲜帧 → Gate.evaluate → HIT/SKIP（GATE_HIT 带 initial/edge 分类）
 * 4. 窗口超时 → closeWindow("timeout")
 */
object StatusPoller {

    /** 网络数据源（实现见 WatchRequest；T03 骨架注入）。 */
    interface StatusSource {
        /**
         * 同步取一次车况；失败返回 null（调用线程 = NetExecutor）。
         *
         * @param budgetMs 本次取帧的时间预算（毫秒）；0 = 不设限。
         *   窗口执行器应传「窗口剩余时间」，避免一帧耗光整个窗口
         *   （单帧最坏 3 次 HTTP，旧实现下可达 39 s，而 NFC-M2 窗口只有 15 s）。
         */
        fun fetch(cred: Credential?, budgetMs: Long = 0L): StatusFrame?
    }

    @Volatile var source: StatusSource? = null

    @Volatile private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (future != null && future?.isDone == false) return
        if (source == null) {
            EventLog.error("POLL", "StatusSource 未注入（WatchRequest 未就绪）")
            return
        }
        val interval = ConfigStore.pollIntervalMs.toLong().coerceAtLeast(500)
        future = NetExecutor.scheduleWithDelay({ tick() }, 0, interval)
        EventLog.debug("POLL", "轮询启动：interval=${interval}ms")
    }

    @Synchronized
    fun stop() {
        try {
            future?.cancel(false)
        } catch (_: Throwable) {
        } finally {
            future = null
        }
    }

    private fun tick() {
        try {
            val now = System.currentTimeMillis()
            if (!WindowManager.isWindowOpen(now)) {
                // 60 s 窗口到期：走统一关窗（清内存态 + 释放线程池 + WINDOW_CLOSE 日志）
                WindowManager.closeWindow("超时")
                return
            }
            val cred = CredentialVault.current()
            // 预算：本帧最多占 12 s，且不超过窗口剩余 —— 单帧最坏 3 次 HTTP，
            // 不限预算时一次卡顿就能拖掉大半个 60 s 窗口（详见 fetchFreshStatus 注释）
            val budget = minOf(WindowManager.remainingMs(now), 12_000L)
            val frame = source?.fetch(cred, budget) ?: run {
                EventLog.warn("NET_TIMEOUT", "网络超时，本次不下发")
                return // 网络失败不计陈旧（G-9 单次失败不重试）
            }
            val ageLimit = ConfigStore.dataAgeLimitSec.toLong()
            val age = now / 1000 - frame.timeSec
            if (age > ageLimit) {
                WindowManager.onStaleFrame()
                EventLog.debug("STATUS", "数据陈旧：age=${age}s（丢弃，不更新边沿）")
                if (WindowManager.staleStreak >= 3 && !WindowManager.awakeCalledThisWindow) {
                    WindowManager.markAwakeCalled()
                    WatchClient.awake(cred)
                }
                if (WindowManager.staleStreak >= 5) {
                    WindowManager.closeWindow("数据持续陈旧")
                }
                return
            }
            WindowManager.onFreshFrame()
            // 边沿判定需要「上一帧」的门值，先取旧值再更新（原实现传 null → 永远 initial）
            val prevLf = WindowManager.lastLf
            WindowManager.onLfObserved(frame.leftFrontDoor ?: -1)
            // 门锁仅供「解锁未锁车提醒」使用（docs/45）：只在新鲜帧更新，陈旧帧不动它
            WindowManager.onLockObserved(frame.doorLockFL, now)
            // STATUS 观测日志：M1 门开取值（doorOpenValue=1）至今**无实测样本**，
            // 每次轮询落一行，供真车核对「关门/开门时 leftFrontDoor 到底是几」
            EventLog.debug(
                "STATUS",
                "车况：age=${age}s，门=${frame.leftFrontDoor}，锁=${frame.doorLockFL}" +
                    "，powerGear=${frame.powerGear}，已上电=${frame.powerGear == 3}",
            )
            val v = Gate.evaluate(
                frame,
                lastLf = prevLf,
                alreadySent = WindowManager.alreadySent,
                doorOpenValue = ConfigStore.doorOpenValue,
            )
            if (v.hit) {
                // `18` §7.1：GATE_HIT 只显示命中项中文（不列 initial/edge 模板值）
                val kindText = if (v.rule.endsWith("initial")) "首帧即开" else "由关变开"
                EventLog.info("GATE_HIT", "检测到主驾门开，条件成立（$kindText）")
                // G-7：先置位，防止复核期间窗口内重复命中
                WindowManager.markSent()
                // ★ T05/M1 执行器接入（此前仅 markSent、从不下发）
                //   同一 NetExecutor 线程内串行：下发 → 复核（A4），结束即关窗
                if (cred == null) {
                    EventLog.warn(
                        "BRIDGE_UNAVAILABLE",
                        "无法连接业务层：请先打开一次比亚迪官方 App",
                    )
                } else if (CommandSender.sendStart(cred)) {
                    CommandSender.verify(cred)
                }
                WindowManager.closeWindow("复核完成")
            } else {
                EventLog.debug("GATE_SKIP", "未满足条件：${v.reason}")
            }
        } catch (t: Throwable) {
            EventLog.warn("POLL_ERR", "轮询内部异常：${t.javaClass.simpleName}")
        }
    }

    /** watch 客户端动作（remoteAwakeRequest / 轮询取帧；实现在 WatchRequest）。 */
    object WatchClient {
        fun awake(cred: Credential?) {
            // remoteAwakeRequest（本窗最多 1 次，G-4+A7）；实现随 WatchRequest
            EventLog.debug("AWAKE", "remoteAwakeRequest（实现随 WatchRequest 集成）")
        }
    }
}

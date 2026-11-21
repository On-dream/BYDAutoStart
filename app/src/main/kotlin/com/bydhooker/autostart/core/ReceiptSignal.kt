package com.bydhooker.autostart.core

/**
 * 解锁回执信号（2026-11-09 真机实测驱动）。
 *
 * 背景：M2 并行版（解锁瞬间立即下发）在**车机休眠**时无效——
 * 09:00 实测 `SEND` 后 20 s 内 `powerGear` 恒为 1（车未上电），
 * 但 `mqttRemoteResult` 回执在解锁后 **1 s** 就到了（`code=0`）。
 * 说明云端「受理」不等于车端执行；必须先让解锁动作把车机唤醒。
 *
 * 职责：`ReceiptHooker`（宿主 Hook 线程）置位 → `M2Runner`（网络线程）轮询消费。
 * 纯内存、跨线程可见，**不落盘**。
 */
object ReceiptSignal {

    /** 最近一次回执到达时间（ms）；0 = 本次进程内尚未收到任何回执。 */
    @Volatile var lastTs: Long = 0L
        private set

    /** 最近一次回执的 code（`0` = 受理成功）；仅供日志。 */
    @Volatile var lastCode: String? = null
        private set

    fun onReceipt(code: String?) {
        lastCode = code
        lastTs = System.currentTimeMillis()
    }

    /** 是否有「本毫秒之后」到达的回执（用于等待解锁完成）。 */
    fun receivedAfter(ts: Long): Boolean = lastTs >= ts
}

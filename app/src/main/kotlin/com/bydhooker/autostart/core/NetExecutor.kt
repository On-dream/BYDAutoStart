package com.bydhooker.autostart.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 网络执行器（`15` §3.4 / §7.3）：单线程串行，窗口期创建、关窗 shutdownNow。
 * **Hook 回调线程只投递任务，绝不直接发网络**（A6）。
 * 串行保证「同窗只发一次」判定无并发歧义（G-7）。
 */
object NetExecutor {

    @Volatile private var pool: ScheduledExecutorService? = null

    /** 窗口开启时调用（幂等；重复调用复用同一池）。 */
    @Synchronized
    fun ensurePool(): ScheduledExecutorService {
        if (pool == null || (pool?.isShutdown ?: true)) {
            pool = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "BYDHK-Net").apply { isDaemon = true }
            }
        }
        return pool!!
    }

    /** 提交任务（串行执行）。窗口关闭后提交会被拒绝——调用方自行判断窗口态。 */
    fun submit(task: Runnable): Boolean = try {
        val p = ensurePool()
        p.submit(task)
        true
    } catch (_: Throwable) {
        false
    }

    /** 轮询调度（固定延迟；间隔 = ConfigStore.pollIntervalMs）。 */
    fun scheduleWithDelay(task: Runnable, initialDelayMs: Long, intervalMs: Long): ScheduledFuture<*>? =
        try {
            ensurePool().scheduleWithFixedDelay(task, initialDelayMs, intervalMs,
                TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
    }

    /** 关窗（WindowManager.closeWindow 调用；shutdownNow 打断在途轮询）。 */
    @Synchronized
    fun shutdownNow() {
        try {
            pool?.shutdownNow()
        } catch (_: Throwable) {
        } finally {
            pool = null
        }
    }

    /**
     * 优雅关池：**不打断在途任务**，已提交任务跑完后线程自行退出。
     *
     * M1 用：`StatusPoller.tick` 在池线程内命中 → 同步下发 + 复核（可能 20 s），
     * 复核结束调 `WindowManager.closeWindow` → 本方法。若此处用 [shutdownNow]，
     * 会 interrupt 正在执行复核的**自己**（可能打断日志写入/查询）。
     */
    @Synchronized
    fun shutdown() {
        try {
            pool?.shutdown()
        } catch (_: Throwable) {
        } finally {
            pool = null
        }
    }
}

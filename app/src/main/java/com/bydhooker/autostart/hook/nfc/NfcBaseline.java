package com.bydhooker.autostart.hook.nfc;

import com.bydhooker.autostart.core.CredentialVault;
import com.bydhooker.autostart.core.EventLog;
import com.bydhooker.autostart.core.StatusPoller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NFC-M2 基线轮询（v3.0.3，2026-11-13 用户拍板）。
 *
 * 【解决什么】M2（解锁上电）原来要等云端 `doorLock` 2→1 边沿，真车实测
 * 「刷卡 → 云端可见解锁」有 ~5 s 链路延迟，上电指令要刷卡后 ~5 s 才发出。
 *
 * 【方案】用户打开刷卡界面（DoubleClickActivity.onResume）即启动轮询，
 * 每 2 s 拉一帧车况、持续刷新基线 —— 刷卡那一刻的基线几乎与真实门锁同步，
 * 于是**刷卡后不再需要等云端确认**：
 *
 *   基线 = 2（已锁）  ⇒ 本次刷卡 = 解锁 ⇒ 延迟 1 s 直接下发上电（调用方）
 *   基线 = 1（已解锁）⇒ 本次刷卡 = 上锁 ⇒ 零动作、不开窗（调用方）
 *   基线 = null       ⇒ 未采到任何有效帧 ⇒ 调用方回退 15 s 边沿判定
 *
 * 【用户拍板的三条】
 *  · 基线轮询生命周期**硬上限 30 s**（到点自停，不刷新续期）
 *  · 间隔 2 s（打开刷卡页期间持续打云端，间隔太密有 429 风险）
 *  · **不做基线时效判断**：新鲜度由「持续轮询」保证；轮询失败时保留上一帧
 *    成功值（它仍比 null 好）。残余风险 = 云端滞后窗口内门锁被他人改变导致
 *    方向判反 → 误上电（锁态收到上电指令）。概率极低，用户明确接受此取舍。
 *
 * 【线程】独立单线程池，与 NfcM2Runner 的兜底轮询互不相干；
 * 调用方在任何分支消费基线前都会先 [stop]，保证不会双循环同时打云端。
 */
public final class NfcBaseline {

    /** 硬上限：从启动起最多跑 30 s。 */
    private static final long MAX_LIFETIME_MS = 30_000L;

    /** 轮询间隔 2 s（用户拍板）。 */
    private static final long INTERVAL_MS = 2_000L;

    /** 单帧预算：基线用途不需要深轮询，5 s 足够一帧新鲜车况。 */
    private static final long FRAME_BUDGET_MS = 5_000L;

    private static volatile ScheduledExecutorService exec;

    /** 本轮启动时刻（30 s 生命周期计时基准）。 */
    private static volatile long startTs = 0L;

    /**
     * 最新有效帧的主驾门锁值。
     * `null` = 还没采到任何有效帧；`1` = 已解锁；`2` = 已锁。
     * 失败帧/未知值不更新（保留上一个成功值）。
     */
    private static volatile Integer baselineLock = null;

    private NfcBaseline() {
    }

    /** 启动基线轮询。已在跑则忽略（30 s 硬上限内不刷新续期）。幂等。 */
    public static synchronized void start() {
        if (exec != null) {
            return;
        }
        startTs = System.currentTimeMillis();
        baselineLock = null;
        try {
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BYDHK-NFC-BASELINE");
                t.setDaemon(true);
                return t;
            });
            exec = ex;
            ex.scheduleWithFixedDelay(NfcBaseline::tick, 0L, INTERVAL_MS, TimeUnit.MILLISECONDS);
            EventLog.INSTANCE.info("NFC_BASELINE", "基线轮询启动：2s 一帧，最长 30s，等待刷卡");
        } catch (Throwable t) {
            EventLog.INSTANCE.error("NFC_BASELINE_ERR", t.getClass().getSimpleName());
        }
    }

    /** 停止轮询并清空基线。调用方在消费基线前必须先调用（防双循环打云端）。 */
    public static synchronized void stop(String reason) {
        ScheduledExecutorService ex = exec;
        exec = null;
        baselineLock = null;
        if (ex != null) {
            try {
                ex.shutdownNow();
            } catch (Throwable ignored) {
            }
            EventLog.INSTANCE.debug("NFC_BASELINE_STOP", reason == null ? "" : reason);
        }
    }

    /** 最新基线：null = 无有效帧；1 = 已解锁；2 = 已锁。 */
    public static Integer current() {
        return baselineLock;
    }

    private static void tick() {
        try {
            if (System.currentTimeMillis() - startTs > MAX_LIFETIME_MS) {
                stop("30s 生命周期到");
                return;
            }
            com.bydhooker.autostart.model.Credential cred = CredentialVault.INSTANCE.current();
            if (cred == null) {
                return;
            }
            StatusPoller.StatusSource src = StatusPoller.INSTANCE.getSource();
            if (src == null) {
                return;
            }
            com.bydhooker.autostart.model.StatusFrame frame = src.fetch(cred, FRAME_BUDGET_MS);
            if (frame == null) {
                return; // 失败帧不更新基线（保留上一个成功值）
            }
            Integer lock = frame.getDoorLockFL();
            if (lock != null && (lock == 1 || lock == 2)) {
                Integer prev = baselineLock;
                baselineLock = lock;
                if (!lock.equals(prev)) {
                    EventLog.INSTANCE.info("NFC_BASELINE", "基线更新：doorLock=" + lock);
                }
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("NFC_BASELINE_ERR", t.getClass().getSimpleName());
        }
    }
}

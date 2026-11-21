package com.bydhooker.autostart.island;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 岛事件发射端（跑在各执行进程内）。
 *
 * 【硬约束】展示链绝不能影响上电：
 * · ContentResolver.call 是同步 binder ⇒ 一律丢到 daemon 单线程
 * · 全链路 try/catch 全吞，不重试、不落盘、不抛回业务线程
 * · Context 拿不到就静默丢弃
 *
 * 【seq】必须**跨进程、跨时间单调**，不能是各进程从 1 数起的计数器：
 * 三个执行进程各自维护自己的计数器时，SystemUI 侧只保留一个 lastSeq，
 * 后到的进程事件会因为「seq 更小」被当成乱序丢弃；而且任何外部注入（adb
 * 调试、探针）都能把水位推到很高，导致之后真实事件被长期挡掉
 * （2026-11-14 00:0x 实测踩到：合成事件 seq=111 之后真实触发全部无岛）。
 * ⇒ 以 {@link SystemClock#elapsedRealtime()} 为基线的微秒级单调值。
 */
public final class IslandReporter {

    /** 基线 = 开机毫秒数 × 1000，保证同机各进程的 seq 处在同一量级且单调。 */
    private static final AtomicLong SEQ =
            new AtomicLong(SystemClock.elapsedRealtime() * 1000L);

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bydhk-island");
        t.setDaemon(true);
        return t;
    });

    private IslandReporter() {
    }

    public static void report(final String type, final long ts, final long deadline,
                              final String source, final long elapsed) {
        try {
            final long seq = SEQ.incrementAndGet();
            EXEC.execute(() -> {
                try {
                    Context ctx = IslandContext.get();
                    if (ctx == null) {
                        // 【已知风险】官方 App / tsmclient 进程拿不到 Context 时事件根本发不出，
                        // 以前是完全静默的；这里只落一条 logcat，不影响业务线程。
                        android.util.Log.w("BYDHK", "ISLAND_NO_CTX|type=" + type);
                        return;
                    }
                    Bundle b = IslandEvent.bundle(type, ts, deadline, source, seq, elapsed);
                    ctx.getContentResolver().call(
                            Uri.parse(IslandEvent.URI), IslandEvent.METHOD, null, b);
                    android.util.Log.i("BYDHK", "ISLAND_SENT|type=" + type + "|seq=" + seq);
                } catch (Throwable t) {
                    android.util.Log.w("BYDHK", "ISLAND_SEND_FAIL|type=" + type
                            + "|" + t.getClass().getSimpleName());
                }
            });
        } catch (Throwable ignored) {
            // 线程池拒绝也静默
        }
    }
}

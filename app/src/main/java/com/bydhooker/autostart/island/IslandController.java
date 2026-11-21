package com.bydhooker.autostart.island;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * SystemUI 进程内的超级岛状态机（乙-B 升级版，单实例）。
 *
 * 【四态】S0 等待开门（仅 M1，倒计时）→ S1 上电中（严格按复核时长）→
 * S2 上电成功（带动画的勾，停留 1 s）→ 退出；失败/未确认 → S3。
 * M2 没有窗口期，直接由 SEND 事件进 S1。
 *
 * 【S1 严格按复核时长】S1 的结束完全由 VERIFY_OK / VERIFY_PENDING 事件驱动；
 * 这里设的 90 s 只是「事件丢失」的 watchdog，正常路径会被终态事件提前 cancel。
 *
 * 【归属包名】本通知由 SystemUI 自己 notify ⇒ 归属 com.android.systemui，
 * 由 FocusWhitelistHook 放行 canShowFocusState*。
 *
 * 【小图标】SystemUI 进程拿不到模块 APK 的资源 id，所以纯代码生成 alpha 白点，
 * 用 Icon.createWithBitmap 传入，不依赖任何模块资源。
 */
public final class IslandController {

    /**
     * 成功/失败动画时长已由真机实测校准（原估 700 ms 偏短，勾只显示 0.7 s 就被 cancel）：
     * `res/5X7.json`（face_recognition_success）与 `res/J40.json`（face_recognition_failed）
     * 均为 fr=60 / op=60 ⇒ 恰好 1.00 s。成功态再叠加用户拍板的 1 s 停留。
     */
    private static final long SUCCESS_ANIM_MS = 1000L;
    private static final long SUCCESS_HOLD_MS = 1000L;
    private static final long FAIL_HOLD_MS = 1800L;      // 1.0 s 动画播完 + 0.8 s 停留
    private static final long S1_WATCHDOG_MS = 90_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());

    /**
     * 去重水位：用**事件时间戳 ts**（各进程都取 System.currentTimeMillis()，可直接比较），
     * 不用 seq。原因（2026-11-14 00:3x 真机踩到）：
     * `seq` 是各进程内的自增计数器，只要有一个进程还在跑旧代码（进程级 ClassLoader
     * 在装包前就加载完），它发来的 seq 就必然小于水位 ⇒ 真实事件被长期静默丢弃，
     * 表现为「业务照跑、完全没有岛」，极难排查。ts 不受进程重启/代码版本影响。
     */
    private static volatile long sLastTs = -1L;
    /** 上一次事件是否真被接受（false = 被判为过期丢弃），仅用于 logcat 诊断。 */
    public static volatile boolean lastAccepted;
    private static volatile Context sCtx;
    private static volatile boolean sChannelReady;

    private static final Runnable CANCEL = () -> {
        try {
            Context c = sCtx;
            if (c != null) {
                NotificationManager nm =
                        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(IslandPayload.NOTIF_ID);
                }
            }
        } catch (Throwable ignored) {
        }
    };

    private IslandController() {
    }

    /** 由 IslandBusHook 在 SystemUI 进程的 binder 线程调用。 */
    public static synchronized void onEvent(Context ctx, Bundle b) {
        if (ctx == null || b == null) {
            return;
        }
        try {
            long ts = b.getLong(IslandEvent.K_TS, System.currentTimeMillis());
            // 只丢「比上一条更旧」的事件；同毫秒重复照收（展示链幂等，最多刷新一次）
            if (ts < sLastTs) {
                lastAccepted = false;
                return;
            }
            sLastTs = ts;
            lastAccepted = true;
            sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

            H.removeCallbacks(CANCEL);

            String type = b.getString(IslandEvent.K_TYPE, "");
            if (IslandEvent.TYPE_CLOSE.equals(type)) {
                CANCEL.run();
                return;
            }

            long deadline = b.getLong(IslandEvent.K_DEADLINE, 0L);
            long elapsed = b.getLong(IslandEvent.K_ELAPSED, 0L);
            int window = b.getInt(IslandEvent.K_WINDOW, 60);

            String json = IslandPayload.build(type, ts, deadline, elapsed, window);
            Notification n = buildNotification(ctx, json, type);

            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            ensureChannel(nm);
            nm.notify(IslandPayload.NOTIF_ID, n);

            // 终态由事件驱动提前 cancel；这里的定时只是兜底
            if (IslandEvent.TYPE_VERIFY_OK.equals(type)) {
                H.postDelayed(CANCEL, SUCCESS_ANIM_MS + SUCCESS_HOLD_MS);
            } else if (IslandEvent.TYPE_SEND.equals(type)) {
                H.postDelayed(CANCEL, S1_WATCHDOG_MS);
            } else if (IslandEvent.TYPE_OPEN.equals(type) || IslandEvent.TYPE_REFRESH.equals(type)) {
                H.postDelayed(CANCEL, window * 1000L + 3000L);
            } else {
                H.postDelayed(CANCEL, FAIL_HOLD_MS);
            }
        } catch (Throwable ignored) {
            // 展示链失败静默：SystemUI 绝不能被岛通知拖崩
            lastAccepted = false;
        }
    }

    private static Notification buildNotification(Context ctx, String json, String type) {
        Notification.Builder builder = new Notification.Builder(ctx, IslandPayload.CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(dot()))
                .setContentTitle(titleOf(type))
                .setContentText("比亚迪解锁自动上电")
                .setOngoing(false);
        Notification n = builder.build();
        n.extras.putString("miui.focus.param", json);
        return n;
    }

    private static String titleOf(String type) {
        if (IslandEvent.TYPE_VERIFY_OK.equals(type)) {
            return "车辆已上电";
        }
        if (IslandEvent.TYPE_SEND.equals(type)) {
            return "正在上电";
        }
        if (IslandEvent.TYPE_OPEN.equals(type) || IslandEvent.TYPE_REFRESH.equals(type)) {
            return "等待开门上电";
        }
        return "上电未确认";
    }

    private static void ensureChannel(NotificationManager nm) {
        if (sChannelReady) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                IslandPayload.CHANNEL_ID, "车辆上电状态", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("解锁自动上电的实时状态");
        nm.createNotificationChannel(ch);
        sChannelReady = true;
    }

    /** 24x24 alpha 白点；状态栏小图标必须是纯 alpha 形状。 */
    private static Bitmap dot() {
        Bitmap bm = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(12f, 12f, 10f, p);
        return bm;
    }
}

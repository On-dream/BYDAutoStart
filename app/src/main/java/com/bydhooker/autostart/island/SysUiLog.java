package com.bydhooker.autostart.island;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SystemUI 进程侧日志（v3.3.2）。
 *
 * 【为什么单独造一个】超级岛这条链整段跑在 `com.android.systemui` 里，
 * 而 `EventLog` 写的是**本进程**的 `files/bydhooker/events.log`；SystemUI 是独立 uid
 * （`android.uid.systemui`），它的日志既不在导出脚本覆盖的 4 段里，也读不到
 * `/data/system`（那是 `system:system 0771`）。结果就是：岛不显示时**一份证据都没有**，
 * 只能让用户去抓 logcat。
 *
 * 【落哪】SystemUI 自己的 **DE 存储**（`/data/user_de/0/com.android.systemui/files/bydhooker/sysui.log`）：
 * · 未解锁也能写（岛恰恰多在锁屏前/锁屏时出现）；
 * · root 一定能读 ⇒ `SystemLogReader` 用 `su -c cat` 拉进导出日志，不用再麻烦用户。
 *
 * 【不变式】只做展示链诊断：写失败、超过 256 KB 都是「丢掉这行」，绝不抛出、绝不阻塞。
 */
public final class SysUiLog {

    /** logcat tag：与 `IslandBusHook` 原有 tag 保持一致，抓 logcat 时一条命令覆盖全部。 */
    private static final String TAG = "BYDHK";

    private static final String SUB_DIR = "bydhooker";
    private static final String FILE_NAME = "sysui.log";
    private static final long MAX_BYTES = 256 * 1024L;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 单线程串行写盘：Provider.call 在 binder 线程上，不能在这里做文件 IO。 */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bydhk-sysui-log");
        t.setDaemon(true);
        return t;
    });

    private static volatile File sFile;

    private SysUiLog() {
    }

    /** 用 SystemUI 的 Context 初始化落盘路径（幂等）。 */
    public static void init(Context ctx) {
        if (sFile != null || ctx == null) {
            return;
        }
        try {
            Context de = ctx.createDeviceProtectedStorageContext();
            File dir = new File(de.getFilesDir(), SUB_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            sFile = new File(dir, FILE_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "SYSLOG_INIT_ERR|" + t.getClass().getSimpleName());
        }
    }

    /** 尽力从 ActivityThread 取应用 Context（Provider 还没走到时的补初始化）。 */
    public static void initFromActivityThread() {
        if (sFile != null) {
            return;
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                init((Context) app);
            }
        } catch (Throwable ignored) {
            // SystemUI 早期 currentApplication 常为 null，等 IslandBusHook 再补
        }
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    private static void write(String level, String tag, String msg) {
        // logcat 永远先打：即使文件没初始化成功，adb 也还能看到
        Log.i(TAG, tag + "|" + msg);
        final File f = sFile;
        if (f == null) {
            return;
        }
        final String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + "|" + level + "|" + tag + "|" + msg;
        }
        try {
            EXEC.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (f.exists() && f.length() > MAX_BYTES) {
                            FileOutputStream clear = new FileOutputStream(f, false);
                            try {
                                clear.write("[日志超过 256 KB，更早的内容已截断]\n".getBytes(UTF8));
                                clear.flush();
                            } finally {
                                clear.close();
                            }
                        }
                        FileOutputStream fos = new FileOutputStream(f, true);
                        try {
                            fos.write((line + "\n").getBytes(UTF8));
                            fos.flush();
                        } finally {
                            fos.close();
                        }
                    } catch (Throwable ignored) {
                        // 落盘失败绝不影响 SystemUI
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}

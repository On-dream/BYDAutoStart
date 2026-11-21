package com.bydhooker.autostart.wake;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.bydhooker.autostart.hook.nfc.TsmNfcLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tsmclient → 模块 App 的 **UI 数据投递端**（架构 C 第三期清理后）。
 *
 * 【只剩两个能力】
 *   · [sendHookState] —— Hook 三态 L1/L2/L3，决定设置页 ①-N 卡是否显示
 *   · [sendCards]     —— 车钥匙卡列表 JSON，决定「NFC 触发卡」弹窗有哪些选项
 * 这两条必须留在 tsmclient 采集（`CardInfoManager` 只在该进程 ClassLoader 里），
 * 且设置页是模块 App 进程 ⇒ 仍需一次跨进程投递。
 *
 * 【已删除】`sendSwipe`（刷卡事件转发）、`sendAppUnlock`（APP 解锁通知）、
 * `sendPrewarm`（提前拉起宿主）、`sendModuleWake`（保活预热）、
 * `APP_UNLOCK_SENT` 三次重试与落盘。架构 C 下这些事件都在**本地**消化，
 * 再转发出去只会制造第二次执行。
 *
 * 【线程】`ContentResolver.call()` 是同步 binder，必须放子线程，
 * 否则会拖慢 tsmclient 的 NFC 回调。单线程池保证两条数据按顺序写入。
 */
public final class NfcWakeClient {

    private static final String TAG = "BYDHOOKER_NFC";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bydhooker-ui-data");
        t.setDaemon(true);
        return t;
    });

    private NfcWakeClient() {
    }

    /** Hook 三态（L1/L2/L3）；节流由调用方控制。 */
    public static void sendHookState(final Context ctx, final boolean l1,
                                     final boolean l2, final boolean l3) {
        submit(ctx, NfcChannel.KIND_HOOK, b -> {
            b.putString(NfcChannel.EXTRA_L1, l1 ? "1" : "0");
            b.putString(NfcChannel.EXTRA_L2, l2 ? "1" : "0");
            b.putString(NfcChannel.EXTRA_L3, l3 ? "1" : "0");
        });
    }

    /** 卡列表（JSON）；采集必须在 tsmclient 进程做。 */
    public static void sendCards(final Context ctx, final String cardsJson) {
        if (ctx == null || cardsJson == null) {
            return;
        }
        submit(ctx, NfcChannel.KIND_CARDS,
                b -> b.putString(NfcChannel.EXTRA_CARDS, cardsJson));
    }

    private interface Filler {
        void fill(Bundle b);
    }

    private static void submit(final Context ctx, final String kind, final Filler filler) {
        if (ctx == null) {
            return;
        }
        try {
            EXEC.execute(() -> {
                try {
                    Bundle in = new Bundle();
                    in.putString(NfcChannel.EXTRA_KIND, kind);
                    filler.fill(in);
                    Bundle out = ctx.getContentResolver()
                            .call(NfcChannel.URI, NfcChannel.METHOD_NFC, null, in);
                    boolean ok = out != null && out.getBoolean(NfcChannel.RESULT_OK, false);
                    Log.i(TAG, "SEND|kind=" + kind + "|ok=" + ok);
                    TsmNfcLog.log(ctx, "UI_DATA_SEND", "kind=" + kind + "|ok=" + ok);
                } catch (Throwable t) {
                    // 模块没装 / 被杀 / provider 崩：只影响 UI 数据，绝不影响刷卡
                    Log.w(TAG, "SEND_ERR|kind=" + kind + "|" + t.getClass().getSimpleName());
                    TsmNfcLog.log(ctx, "UI_DATA_SEND_ERR",
                            "kind=" + kind + "|err=" + t.getClass().getSimpleName());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "SEND_SUBMIT_ERR|" + t.getClass().getSimpleName());
        }
    }
}

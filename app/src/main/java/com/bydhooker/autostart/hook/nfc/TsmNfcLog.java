package com.bydhooker.autostart.hook.nfc;

import android.content.Context;
import android.util.Log;

import com.bydhooker.autostart.core.EventLog;

import java.io.File;

/**
 * tsmclient 侧刷卡事件日志（2026-11-13 v3.0 起合并）。
 *
 * 【变更】原先自己落盘 `files/bydhooker-nfc/tsm-nfc.log`（256 KB 滚动 `.1`），
 * 与就地执行层的 `files/bydhooker/events.log` 两份并存 —— 用户反馈日志份数太多。
 * 现在改为**委托 [EventLog]**：同一份 `events.log`、同一个 512 KB 上限与裁剪策略，
 * 事件名加 `TSM_` 前缀区分来源，logcat Tag 不变。
 *
 * 【为什么安全】
 *  · `EventLog.mask` 只按字段名（token= / vin= / uuid: …）掩码，
 *    `aid=` 不在名单 ⇒ AID 仍明码（裁定 E：日志可明码、仅限本地）。
 *  · [EventLog#ensureInit] 幂等：Hook 可能早于 `NfcLocalExec.init` 触发
 *    （用户开机后第一次刷卡），不兜底的话那几条只进内存 buffer、不落盘。
 *
 * 【签名不变】调用方（NfcSwipeHook / NfcPrewarmHook / NfcLocalExec / NfcWakeClient）
 * 一行都不用改。
 */
public final class TsmNfcLog {

    private static final String TAG = "BYDHOOKER_NFC";

    private TsmNfcLog() {
    }

    /** 写一条：logcat + 委托 EventLog 落盘（`TSM_` 前缀）。 */
    public static void log(Context ctx, String tag, String fields) {
        String line = tag + (fields == null || fields.isEmpty() ? "" : "|" + fields);
        Log.i(TAG, line);
        try {
            if (ctx != null) {
                EventLog.INSTANCE.ensureInit(ctx.getFilesDir());
            }
            EventLog.INSTANCE.logExternal("INFO", "TSM_" + tag, flat(line));
        } catch (Throwable t) {
            Log.w(TAG, "TSM_LOG_ERR|" + t.getClass().getSimpleName());
        }
    }

    /** 单行化：EventLog 的落盘格式是一行一条，summary 里不能有换行。 */
    private static String flat(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}

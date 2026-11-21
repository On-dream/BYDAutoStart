package com.bydhooker.autostart.wake;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import com.bydhooker.autostart.core.EventLog;

import org.json.JSONArray;

/**
 * 架构 C 第三期清理后：模块 App 侧**只收 UI 数据**的 ContentProvider。
 *
 * 【保留的两个 kind】
 *   · `KIND_HOOK`  → `NfcLocalState.setHookLevel()`（设置页 ①-N 卡显示判据）
 *   · `KIND_CARDS` → `NfcLocalState.setCards()`（NFC 触发卡选择弹窗）
 * 两者写模块私有 SP，设置页同进程直读，**不进 remote prefs、不触发任何车控**。
 *
 * 【删掉了什么】原方案 B 枢纽的 7 个 method：`ping` / `window` / `last_event` /
 * `set_exec` / `app_unlock` / `module_wake` / `triple_click`，以及 `KIND_SWIPE` /
 * `KIND_PREWARM` 分流、5 个 daemon 执行线程、`fresh(ts)` 新鲜度校验、
 * `exec.location` 双轨、`HostLauncher` 拉起宿主 / 加白名单、`WindowKeeper` 守护进程。
 * 架构 C 下三条触发链（三击 → system_server、APP → 官方 App、NFC → tsmclient）
 * 各自就地执行，这个 Provider 不再承载任何执行事件。
 *
 * 【安全】exported=true 仍保留（tsmclient 与模块不同签名，signature 级权限会拒绝它）。
 * 现在即便被任意应用调用，最坏结果也只是**写两条 UI 数据**，不会下发车控指令。
 */
public class NfcWakeProvider extends ContentProvider {

    private static volatile Context sCtx;

    @Override
    public boolean onCreate() {
        sCtx = getContext();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = sCtx != null ? sCtx : getContext();
        Bundle out = new Bundle();
        out.putLong("pid", Process.myPid());
        out.putLong("uid", Process.myUid());

        boolean ok = false;
        if (!NfcChannel.METHOD_NFC.equals(method) || extras == null || ctx == null) {
            out.putBoolean(NfcChannel.RESULT_OK, false);
            return out;
        }

        String kind = extras.getString(NfcChannel.EXTRA_KIND);
        switch (kind == null ? "" : kind) {
            case NfcChannel.KIND_HOOK: {
                String l1 = extras.getString(NfcChannel.EXTRA_L1);
                String l2 = extras.getString(NfcChannel.EXTRA_L2);
                String l3 = extras.getString(NfcChannel.EXTRA_L3);
                NfcLocalState.setHookLevel(ctx, l1, l2, l3);
                log("HOOK_STATE", "l1=" + l1 + "|l2=" + l2 + "|l3=" + l3);
                ok = true;
                break;
            }
            case NfcChannel.KIND_CARDS: {
                String json = extras.getString(NfcChannel.EXTRA_CARDS);
                int count = -1;
                try {
                    if (json != null) {
                        count = new JSONArray(json).length();
                    }
                } catch (Throwable t) {
                    log("CARDS_BAD_JSON", "err=" + t.getClass().getSimpleName());
                    ok = false;
                    break;
                }
                if (count < 0) {
                    log("DROP_NO_CARDS", "");
                    ok = false;
                    break;
                }
                NfcLocalState.setCards(ctx, json);
                log("CARDS", "count=" + count);
                // v3.1.6 默认选卡（方案甲）：重装 App 后 remote prefs 被清空、
                // selectedAid 变空 ⇒ 刷卡被 R-AID-7 直接忽略（用户体感「NFC 失效」）。
                // 卡列表恰好只有一张时自动选中它（写三键原子提交），免去手动选卡；
                // 多卡保持手动（同产品多卡无法自动判定哪张，见 docs/29 Q3）。
                if (count == 1) {
                    autoSelectSingleCard(ctx, json);
                }
                ok = true;
                break;
            }
            default:
                break;
        }

        out.putBoolean(NfcChannel.RESULT_OK, ok);
        return out;
    }

    /** 落进模块 App 自己的 events.log（512 KB 滚动，见 EventLog）。 */
    private static void log(String tag, String fields) {
        try {
            EventLog.INSTANCE.logExternal("INFO", tag, fields);
        } catch (Throwable ignored) {
        }
    }

    // ---- ContentProvider 必需方法（本通道只用 call()）----

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    /**
     * 默认选卡（方案甲，v3.1.6）：未选卡且卡列表恰好一张时自动选中。
     *
     * 【为什么不阻塞 binder 线程】`XposedServiceGate.awaitPrefs` 可能等桥绑定最多 1.5 s，
     * 而 `call()` 跑在 Provider 的 binder 线程上 —— _TX 阻塞会让 tsmclient 侧卡住。
     * 故丢到后台单线程；重复调用会被「selectedAid 已非空」短路，无害。
     */
    private static void autoSelectSingleCard(final Context ctx, final String json) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.SharedPreferences sp =
                            com.bydhooker.autostart.data.XposedServiceGate.INSTANCE.awaitPrefs(2000L);
                    if (sp == null) {
                        log("CARD_AUTOPICK_SKIP", "桥未绑定");
                        return;
                    }
                    String sel = sp.getString(
                            com.bydhooker.autostart.bridge.BridgeContract.KEY_NFC_SELECTED_AID, "");
                    if (sel != null && !sel.isEmpty()) {
                        return;  // 用户已手动选过，绝不覆盖
                    }
                    JSONArray arr = new JSONArray(json);
                    if (arr.length() != 1) {
                        return;  // 多卡/零卡：保持手动
                    }
                    org.json.JSONObject c = arr.getJSONObject(0);
                    String aid = c.optString("aid", "");
                    if (aid.isEmpty()) {
                        return;
                    }
                    com.bydhooker.autostart.data.XposedServiceGate.INSTANCE.writeNfcCard(
                            aid, c.optString("cid", ""), c.optString("name", ""));
                    log("CARD_AUTOPICK", "aid=" + aid + "|单卡自动选中");
                    EventLog.INSTANCE.info("NFC_CARD", "单卡自动选中（重装后免手动选卡）|aid=" + aid);
                } catch (Throwable t) {
                    log("CARD_AUTOPICK_ERR", t.getClass().getSimpleName());
                }
            }
        }, "bydhk-card-autopick").start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

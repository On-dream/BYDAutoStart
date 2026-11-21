package com.bydhooker.autostart.hook.nfc;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 刷卡事件 Hook（运行在 `com.miui.tsmclient` 进程内）—— `docs/30` §5.1。
 *
 * 【落点依据】探针 v0.3.0 真机实证（`XpProbeEntry.ZHooker`）：
 * `com.miui.tsmclient.service.NfcEventMonitorService#z(Intent)` 是 TRANSACTION 与
 * RF_ON 的共同入口，且**必须打方法入口**——BYD 的 AID 没有内置 handler，
 * 会在这条链路下游提前 return（`26` §5.2）。
 *
 * 【判据（缺一不可）】
 *   ① 进程名必须是 `com.miui.tsmclient`（`:daemon` 子进程也会被注入，必须排除）
 *   ② extras 里必须有 `com.miui.nfc.extras.AID` —— `RF_ON` 那 11 次也会进 z()，
 *      但没有 AID（`26` C10），不排除会把"亮屏/唤醒"当成刷卡
 *   ③ **架构 C 第二期起，AID 比对前移到本进程**（`NfcLocalExec`）：
 *      tsmclient 与普通被 Hook 应用一样能调 `getRemotePreferences`，
 *      选中卡从快照读，比对通过就**就地执行**，不再唤醒模块 App。
 *      第三期已删掉 B 架构转发链：装配未就绪时只记日志并放弃，不再回落。
 *
 * 【不做的事】
 *   · 不判方向（解锁 vs 上锁）：`26` C9 实测两次事件的 DATA / event_source 完全相同
 *   · 不写被 Hook 侧私有存储（P0-NFC-7 已证写不了）；配置只读 remote prefs
 *   · 不阻塞：本地执行与跨进程投递都在后台线程
 */
public final class NfcSwipeHook implements XposedInterface.Hooker {

    private static final String TAG = "BYDHOOKER_NFC";

    /** 目标类（OTA 漂移时只改这里；方法按名字 + 单 Intent 参数匹配，不认混淆序号）。 */
    public static final String TARGET_CLASS = "com.miui.tsmclient.service.NfcEventMonitorService";
    public static final String TARGET_METHOD = "z";

    private static volatile Context sCtx;

    /** L4 心跳时间戳（每次「带 AID 的事件」，**先于**任何匹配逻辑）。 */
    private static volatile long sLastAidTs = 0L;

    /** 累计转发次数（仅日志/诊断）。 */
    private static volatile int sSwipeCount = 0;

    /** 无 AID 事件的累计次数（RF_ON 等；落盘日志里每分钟记一次）。 */
    private static volatile int sNoAidCount = 0;
    private static volatile long sLastNoAidLogTs = 0L;

    /**
     * AID 候选键。`com.miui.nfc.extras.AID` 是真机实测的那个；
     * 另两个是防御性兜底（不同 ROM / NFC 服务版本可能换键）。
     */
    private static final String[] AID_KEYS = {
            "com.miui.nfc.extras.AID",
            "android.nfc.extra.AID",
            "aid",
    };

    public static long lastAidTs() {
        return sLastAidTs;
    }

    public static int swipeCount() {
        return sSwipeCount;
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        try {
            Object thiz = chain.getThisObject();
            if (thiz instanceof Context) {
                // NfcEventMonitorService 本身就是 Context，直接留用（与探针同一做法）
                if (sCtx == null) {
                    // 首次拿到 Context = 落盘日志可用（落盘点在 tsmclient 私有目录）
                    TsmNfcLog.log((Context) thiz, "CTX_OK",
                            "class=" + thiz.getClass().getName());
                }
                sCtx = (Context) thiz;
                // 首次拿到 Context 时补发一次三态（装配阶段 Application 可能还没就绪）
                NfcHookProbe.attach(sCtx);
            }
            Object arg0 = chain.getArg(0);
            Context ctx = sCtx;
            if (arg0 instanceof Intent && ctx != null) {
                handle(ctx, (Intent) arg0);
            }
        } catch (Throwable t) {
            // 绝不影响宿主的刷卡流程
            Log.w(TAG, "SWIPE_HOOK_ERR|" + t.getClass().getSimpleName());
        }
        return chain.proceed();
    }

    private static void handle(Context ctx, Intent intent) {
        Bundle extras = intent.getExtras();
        String aid = extractAid(extras);
        if (aid == null) {
            // RF_ON / 无 AID 的入口：这是正常现象，logcat 不记避免刷屏；
            // 但**落盘日志每分钟记一次计数** —— 用来区分「没刷卡」与「刷卡了但事件没带 AID」
            // （`26` C10：11 次 RF_ON 也会进 z()）。
            sNoAidCount++;
            long now = System.currentTimeMillis();
            if (now - sLastNoAidLogTs > 60_000L) {
                sLastNoAidLogTs = now;
                TsmNfcLog.log(ctx, "NO_AID_EVENT",
                        "action=" + intent.getAction() + "|count=" + sNoAidCount);
            }
            return;
        }
        sLastAidTs = System.currentTimeMillis();
        sSwipeCount++;
        Log.i(TAG, "SWIPE|aid=" + aid + "|action=" + intent.getAction() + "|n=" + sSwipeCount);
        // 落盘：车里连不上 ADB 时，这是唯一能事后取回的「刷卡原始证据」
        TsmNfcLog.log(ctx, "SWIPE",
                "aid=" + aid + "|action=" + intent.getAction() + "|n=" + sSwipeCount);
        // 架构 C：就地执行（tsmclient 自己发 HTTPS），不再有任何跨进程执行转发。
        // 未就绪 / 类加载失败都只记日志放弃 —— 绝不「静默」，也绝不回落 B 架构。
        boolean handled = false;
        try {
            handled = NfcLocalExec.onSwipe(ctx, aid, System.currentTimeMillis());
        } catch (Throwable t) {
            TsmNfcLog.log(ctx, "LOCAL_CLASS_ERR", t.getClass().getName() + "|" + t.getMessage());
        }
        if (!handled) {
            TsmNfcLog.log(ctx, "LOCAL_NOT_READY", "就地执行层未就绪，本次刷卡放弃（aid=" + aid + "）");
        }
    }

    private static String extractAid(Bundle b) {
        if (b == null) {
            return null;
        }
        for (String key : AID_KEYS) {
            Object v = b.get(key);
            if (v instanceof byte[]) {
                return hex((byte[]) v);
            }
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        }
        return null;
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte x : data) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}

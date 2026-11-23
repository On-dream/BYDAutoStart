package com.bydhooker.autostart.hook.nfc;

import android.content.Context;
import android.util.Log;

import com.bydhooker.autostart.wake.NfcWakeClient;

/**
 * Hook 四层状态的 L1~L3 回传（运行在 `com.miui.tsmclient` 进程）—— `docs/30` §5.7。
 *
 * 【为什么状态要从这里发】
 * 设置页要判断「NFC 卡片该不该显示」，依据是 tsmclient 里的 Hook 有没有装上。
 * 这个信息只有本进程知道，而本进程写不了 remote prefs（P0-NFC-7）⇒ 走 ContentProvider
 * 通知模块，模块写本地 SP，设置页同进程直读。
 *
 * 【节流】
 * `onPackageLoaded` 可能因多 dex / 重载多次触发，且延迟补挂会反复尝试。
 * 状态没变就不发，避免无谓唤醒模块（唤醒一次 0.3~1 s）。
 *
 * 【L4 不在这里】
 * L4 =「收到过带 AID 的事件」的心跳，记在 {@link NfcSwipeHook#lastAidTs()}，
 * 只进日志、**不作为卡片显示门槛**（`29` §5.1 Q2=乙）。
 */
public final class NfcHookProbe {

    private static final String TAG = "BYDHOOKER_NFC";

    private static volatile boolean l1 = false;
    private static volatile boolean l2 = false;
    private static volatile boolean l3 = false;

    /** 上次上报的三态位组合，用于节流。 */
    private static volatile int lastState = -1;

    /** 缓存的 Context（首次上报时 Application 往往还没 attach 完，需要补发）。 */
    private static volatile Context sCtx;

    private NfcHookProbe() {
    }

    /**
     * 补发状态。
     *
     * `onPackageLoaded` 阶段 `ActivityThread.currentApplication()` 还是 null
     * （真机实测 `ctx=false`），此时三态已变更但发不出去。等 Application 就绪后调本方法重发。
     *
     * @return 是否成功发出
     */
    public static boolean retryReport() {
        Context ctx = sCtx != null ? sCtx : hostContext();
        if (ctx == null) {
            return false;
        }
        sCtx = ctx;
        int s = (l1 ? 1 : 0) | (l2 ? 2 : 0) | (l3 ? 4 : 0);
        lastState = s;
        Log.i(TAG, "HOOK_STATE_RESEND|l1=" + l1 + "|l2=" + l2 + "|l3=" + l3);
        NfcWakeClient.sendHookState(ctx, l1, l2, l3);
        // 2026-11-23：三态上报成功时**顺带采集一次卡列表**。
        //
        // 原先只有「打开刷卡页 onResume」才采集（`NfcPrewarmHook.intercept`），
        // 用户不打开刷卡页 ⇒ 设置页「NFC 触发卡」永远没有数据；而且**重启手机也不会触发**
        // （重启只会让 tsmclient 重新注入、三态回来）。这正是用户反馈的
        // 「首页有 NFC 卡、设置页没有触发卡、重启也没用」的完整成因。
        //
        // 放在 retryReport 而不是 attach：attach 里有 `sCtx == null` 前置条件，
        // 实测 sCtx 常已被 hostContext() 提前赋值 ⇒ 整块跳过、采集不跑（本次排查踩到）。
        // 采集侧有 30s 节流，重复调用安全。
        NfcPrewarmHook.collectOnAttach(ctx);
        return true;
    }

    /** 供 Hook 侧把拿到的 Service Context 交给本类（比反射取 Application 更可靠）。 */
    public static void attach(Context ctx) {
        if (ctx != null && sCtx == null) {
            sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            retryReport();
        }
    }

    /** L1：模块已加载到本进程。 */
    public static void markL1() {
        l1 = true;
        report();
    }

    /** L2：目标类与方法都找到了。 */
    public static void markL2() {
        l2 = true;
        report();
    }

    /** L3：Hook 实际挂上了（`hook(m).intercept()` 未抛）。 */
    public static void markL3() {
        l3 = true;
        report();
    }

    public static boolean level1() {
        return l1;
    }

    public static boolean level2() {
        return l2;
    }

    public static boolean level3() {
        return l3;
    }

    private static void report() {
        int s = (l1 ? 1 : 0) | (l2 ? 2 : 0) | (l3 ? 4 : 0);
        if (s == lastState) {
            return;
        }
        lastState = s;
        Context ctx = hostContext();
        Log.i(TAG, "HOOK_STATE|l1=" + l1 + "|l2=" + l2 + "|l3=" + l3 + "|ctx=" + (ctx != null));
        if (ctx != null) {
            NfcWakeClient.sendHookState(ctx, l1, l2, l3);
        }
    }

    /** tsmclient 进程的 Application（本进程没有 Activity，只能走这条路）。 */
    private static Context hostContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}

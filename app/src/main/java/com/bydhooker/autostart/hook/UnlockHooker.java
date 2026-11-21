package com.bydhooker.autostart.hook;

import com.bydhooker.autostart.bridge.BridgeContract;
import com.bydhooker.autostart.core.EventLog;
import com.bydhooker.autostart.core.TriggerSource;

import io.github.libxposed.api.XposedInterface;

import androidx.annotation.NonNull;

/**
 * 解锁指令捕获（`15` §4.1 B3 起点：**指令即起点**，不等回执）。
 *
 * Hook 目标（探针 v109 / 02 报告【实测】；**OTA 类名可能漂移**，漂移时此处
 * 常量 + `api102_signatures.md` 同步更新）：
 * `com.byd.base.control.ControlImpl.sendRemoteControl(IControl)` —— 宿主
 * 全部车控指令的单一收口（上锁/解锁两轮【实测】均走此方法）。
 *
 * 判定：反射读取 IControl 实参的 `commandType`（**未验证链路 Q20/A3** ——
 * {@link CommandTypeDump} 首次命中时零触发 dump 闭合）；`OPENDOOR` 即开窗。
 * 误开窗由严格门控兜底（不会误下发）。
 *
 * 签名依据（裁定 I，javap 实测 `offline/api102_signatures.md`）：
 * `XposedInterface.Hooker`（非泛型）的
 * `public abstract Object intercept(XposedInterface$Chain) throws Throwable`。
 * Chain：getExecutable()/getThisObject()/getArgs()/getArg(int)/proceed()。
 */
public final class UnlockHooker implements XposedInterface.Hooker {

    public static final UnlockHooker INSTANCE = new UnlockHooker();

    /** Hook 目标（宿主类名常量；漂移时仅改这里）。 */
    public static final String TARGET_CLASS =
            "com.byd.base.control.ControlImpl";
    public static final String TARGET_METHOD = "sendRemoteControl";
    /** 实参类型（探针 v4/v109【实测】：ControlInputParams 实现 IControl）。 */
    public static final String PARAM_CLASS = "com.byd.base.control.listener.IControl";

    private UnlockHooker() {
    }

    /**
     * 同一解锁命令的去重窗口（T05）：ControlImpl 多个 sendRemoteControl 重载可能互调，
     * 若都被 Hook，一次解锁会产多条 UNLOCK。1.5 s 内同一 OPENDOOR 只认第一条。
     * （worker 侧 M2 还有 2 s 事件级去重，两层互不依赖。）
     */
    private static final long DEDUPE_WINDOW_MS = 1500L;
    private static final Object DEDUPE_LOCK = new Object();
    private static long lastUnlockTs = 0L;

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        // 前置检查（指令即起点）；任何异常只记日志，**绝不影响宿主原调用**
        String cmd = null;
        try {
            Object control = chain.getArg(0);
            cmd = CommandTypeDump.readCommandType(control);
            if (cmd != null) {
                if (BridgeContract.CMD_UNLOCK.equals(cmd)) {
                    boolean first = false;
                    synchronized (DEDUPE_LOCK) {
                        long now = System.currentTimeMillis();
                        if (now - lastUnlockTs >= DEDUPE_WINDOW_MS) {
                            lastUnlockTs = now;
                            first = true;
                        }
                    }
                    if (first) {
                        long ts = System.currentTimeMillis();
                        // 架构 C（2026-11-13）：**就地执行**。
                        //
                        // 原先这里按 `exec.location` 分流：执行层在模块时，本进程只负责
                        // 「通知模块」—— 而模块是个纯后台 App，被冻结/被杀就没人执行，
                        // 这正是「APP 解锁必须把模块挂在后台」的根源。
                        // 现在执行者**就在解锁现场**（官方 App 进程），自己发 HTTPS，
                        // 与模块是否活着完全无关。
                        //
                        // 宿主侧的前置条件（都已在装配时就绪）：
                        // · 凭据 —— `RemotePrefs.init` 的启动快照把 CRED_KEYS 喂给宿主 vault
                        // · so   —— `ModuleBridgeHost.ensureWhitebox` 走「解到 code_cache 再 load」
                        // · 配置 —— `ConfigStore` 由 remote prefs listener 持续同步
                        TriggerSource.INSTANCE.onUnlockCommand("app", ts);
                    } else {
                        EventLog.INSTANCE.debug("HOOK", "重复解锁回调已忽略（多重重载）");
                    }
                }
                // 其他 commandType 仅 DEBUG 记录（commandType 取值链路 Q20 的实证来源）
                EventLog.INSTANCE.debug("UNLOCK_SCAN", "cmd=" + cmd);
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("HOOK_ERR", "UnlockHooker 内部异常："
                    + t.getClass().getName());
        }
        // ★ 回执探针（2026-11-09）：先执行原调用，再**只读**观测返回值。
        //   目的：确认 sendRemoteControl 是否同步返回 ControlResultBean（res/msg），
        //   为「等解锁成功再上电」确定可观测判据（Q3 至今未闭合）。
        //   绝不修改返回值、绝不影响原调用（异常全吞）。
        Object result = chain.proceed();
        if (BridgeContract.CMD_UNLOCK.equals(cmd)) {
            try {
                EventLog.INSTANCE.debug("UNLOCK_RET",
                        "proceed 返回=" + (result == null ? "null"
                                : result.getClass().getName() + " "
                                + CommandTypeDump.describeFields(result)));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /** 宿主进程的 Application（Hook 回调线程里没有现成 Context）。 */
    private static android.content.Context currentApplication() {
        try {
            return (android.content.Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}

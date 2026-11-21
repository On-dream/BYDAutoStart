package com.bydhooker.autostart.hook;

import com.bydhooker.autostart.core.EventLog;

import io.github.libxposed.api.XposedInterface;

import androidx.annotation.NonNull;

/**
 * 远控回执探针（2026-11-09）——「等解锁成功再上电」的可观测判据来源。
 *
 * Hook 目标：`com.byd.base.control.ControlImpl.mqttRemoteResult(CloudResult)`
 * ——【实测·方法签名】见 `research/官方App逆向/evidence/runtime/probe/probe_run_v100~v103 的 methods_extra.txt`：
 * ```
 * M public  void mqttRemoteResult(CloudResult)
 * M private void lambda$mqttRemoteResult$9(String, ControlResultBean)
 * M public  void controlRequestSuccess(String)
 * M public  void disposeControlSuccess(boolean, String, ControlResultBean, IControl)
 * ```
 * 02 报告 §4 实测回执链（上锁样本）：
 * `... → controlStart → CloudResult(code='0') → controlRequestSuccess(uuid)
 *  → mqttRemoteResult → disposeControlSuccess → controlSuccess → controlFinish`，
 * **T+2.8 s** 收到 MQTT 回执 `respondData={res=2}`、`successLocaMsg=车门上锁成功`。
 *
 * 本类当前只做**只读观测**：把 `CloudResult` 的字段（code / respondData / message…）
 * 落 EventLog，用于确认「解锁成功」的判据形态（res==2 与文案），
 * **不下发任何指令、不改变宿主行为**。
 *
 * 注册方式：按**方法名 + 单参数**匹配（不硬编码 `CloudResult` 类名，
 * 该类位于业务 dex、包名未经确认）。
 */
public final class ReceiptHooker implements XposedInterface.Hooker {

    public static final ReceiptHooker INSTANCE = new ReceiptHooker();

    /** Hook 目标方法名（`ControlImpl` 内唯一）。 */
    public static final String TARGET_METHOD = "mqttRemoteResult";

    private ReceiptHooker() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        try {
            Object cloudResult = chain.getArg(0);
            String desc = cloudResult == null
                    ? "null"
                    : cloudResult.getClass().getName() + " "
                    + CommandTypeDump.describeFields(cloudResult);
            EventLog.INSTANCE.debug("RECEIPT", "mqttRemoteResult 回执：" + desc);
            // 置位「解锁已完成」信号（M2 等待它再下发启动指令）。
            // 只认 code=0（受理成功）；非 0 也记录 code 供排障，但不置成功信号。
            String code = CommandTypeDump.readStringField(cloudResult, "code");
            if (code != null) {
                if ("0".equals(code)) {
                    com.bydhooker.autostart.core.ReceiptSignal.INSTANCE.onReceipt(code);
                } else {
                    EventLog.INSTANCE.debug("RECEIPT", "回执 code=" + code + "（非成功，不触发上电）");
                    com.bydhooker.autostart.core.ReceiptSignal.INSTANCE.onReceipt(code);
                }
            }
        } catch (Throwable t) {
            EventLog.INSTANCE.warn("HOOK_ERR", "ReceiptHooker 内部异常："
                    + t.getClass().getName());
        }
        return chain.proceed();
    }
}

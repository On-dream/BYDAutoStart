package com.bydhooker.autostart.core

/**
 * 严格门控（`15` §3.2 ④ / §5 G-1..G-7、G-9、G-5d）——**纯判定，无副作用**。
 *
 * ★ P3① 结构隔离（阶段隔离替代 dryRun）：本文件**不得引用 CommandSender**；
 *   执行器接入发生在 T05（`01` §6 修正）。
 * 输入由 StatusPoller 喂入；输出 Verdict 由调用方处理：
 *   HIT → （T05 起）执行器下发；SKIP → 记 GATE_SKIP 继续轮询。
 */
object Gate {

    /** 判定结论（日志落 GATE_HIT / GATE_SKIP + reason）。 */
    data class Verdict(val hit: Boolean, val rule: String, val reason: String)

    /**
     * @param frame        当前车况帧（新鲜度已由 StatusPoller 校验；陈旧帧不会到达）
     * @param lastLf       窗口内上一次观察到的主驾门值（null = 窗口首帧）
     * @param alreadySent  本窗已下发标记（G-7）
     * @param doorOpenValue 门开取值（固定 1，`15` §6.2；不做一键校准 N-7）
     */
    fun evaluate(
        frame: com.bydhooker.autostart.model.StatusFrame,
        lastLf: Int?,
        alreadySent: Boolean,
        doorOpenValue: Int,
    ): Verdict {
        // G-6：已上电跳过（【实测】powerGear 3=已上电；0/1 语义【不知道】只比较 3）
        if (frame.powerGear != null && frame.powerGear == 3) {
            return Verdict(false, "G-6", "已上电")
        }
        // G-7：本窗已下发
        if (alreadySent) {
            return Verdict(false, "G-7", "本窗已下发")
        }
        // G-5：门值三态判定（1=开 / 0=关 / 其他含 2 与 null=未知不触发）
        val lf = frame.leftFrontDoor
        val doorKnownOpen = lf == doorOpenValue
        val doorUnknown = lf == null || (lf != 0 && lf != doorOpenValue)
        // G-5b：首帧即开（lastLf==null 时不要求先见"关"）
        // G-5d：陈旧帧已在 StatusPoller 丢弃且不更新 lastLf（解耦保证）
        when {
            doorKnownOpen -> {
                val kind = if (lastLf == null) "initial" else "edge"
                return Verdict(true, "G-5b/$kind", "检测到主驾门开，条件成立")
            }
            doorUnknown -> return Verdict(false, "G-5", "门值未知（不触发）")
        }
        // lf==0：门关，继续等
        return Verdict(false, "G-5", "门未开")
    }
}

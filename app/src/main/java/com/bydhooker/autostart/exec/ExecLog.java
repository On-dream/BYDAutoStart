package com.bydhooker.autostart.exec;

import com.bydhooker.autostart.core.EventLog;

/**
 * system_server 侧执行日志（2026-11-13 v3.0 起合并）。
 *
 * 【变更】原先自己落盘 `/data/system/bydhk-exec.log`（256 KB 触顶清空），
 * 与业务日志 `/data/system/bydhk-exec/bydhooker/events.log`（EventLog，512 KB）
 * 两份并存 —— 用户反馈日志份数太多。现在改为**委托 [EventLog]**：
 * 同一份 `events.log`、同一个 512 KB 上限与裁剪策略，system 侧只剩两份日志
 * （events.log + 三击 Hook 的 bydhk-triple.log）。
 *
 * 【为什么安全】
 *  · 调用序：`InjectableExec.init()` 先 `EventLog.init`（①）再加载 so（②，
 *    SO_* 日志从这里开始）⇒ 委托时 EventLog 必已就绪。
 *  · EventLog 未 init 时写入只进内存 buffer（500 条），不丢不崩。
 *
 * 【签名不变】ExecConfig / InjectableSoLoader / LocalCredStore / InjectableExec
 * 的调用点一行都不用改；`init(path)` 已无调用方，随之删除。
 */
public final class ExecLog {

    private ExecLog() {
    }

    public static void info(String event, String msg) {
        write("INFO", event, msg);
    }

    public static void warn(String event, String msg) {
        write("WARN", event, msg);
    }

    public static void error(String event, String msg) {
        write("ERROR", event, msg);
    }

    public static void debug(String event, String msg) {
        write("DEBUG", event, msg);
    }

    private static void write(String level, String event, String msg) {
        try {
            EventLog.INSTANCE.logExternal(level, event, flat(msg));
        } catch (Throwable ignored) {
            // 日志失败绝不影响执行链
        }
    }

    /** 单行化：EventLog 的落盘格式是一行一条，summary 里不能有换行。 */
    private static String flat(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}

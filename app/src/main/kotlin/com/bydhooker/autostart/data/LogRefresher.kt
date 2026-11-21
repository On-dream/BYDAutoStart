package com.bydhooker.autostart.data

import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.ui.SettingsState

/**
 * 日志刷新收口（2026-11-09；**2026-11-10 改为只读模块端**）。
 *
 * 此前刷新循环只写在 `LogTab` 的 `LaunchedEffect` 里 —— 停在首页时循环根本没启动，
 * 于是首页状态卡的「最近事件」永远是占位的「暂无事件」。现在由 `SettingsActivity.Root()`
 * 常驻驱动，两个 Tab 共用同一份 `SettingsState.logEntries`。
 *
 * **2026-11-10 用户拍板：模块日志页放弃读取宿主端日志。**
 * 原因（实测）：设置页（不同 UID）读宿主 `events.log` 只能靠 root，而 App 进程里的
 * su 处在受限 mount namespace，多形态尝试仍时好时坏 —— 读到的可能是**旧的/不完整的**
 * 内容，直接被当成「当前日志」展示，观感与事实不符。宁可只显示本进程可信的事件，
 * 也不展示疑似过期的数据。
 *
 * 宿主端日志并没有丢：它仍在宿主进程写（≤512 KB），**导出时**与模块端合并成一份文件
 * （见 [LogExporter]）—— 排查问题看导出件，看页面看模块端。
 */
object LogRefresher {

    @Volatile private var refreshing = false

    /** 读一次日志（只读**本进程** events.log 尾部 50 行）。 */
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            SettingsState.logEntries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    EventLog.tail(50)
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        } finally {
            refreshing = false
        }
    }

    /**
     * 首页「最近事件」文案：`HH:mm` + 一句话（`18` §263：`02:31 已自动上电` 式）。
     * 取最近一条事件；空列表返回 null（UI 显示「暂无事件」）。
     */
    fun recentEventText(): String? {
        val e = SettingsState.logEntries.lastOrNull() ?: return null
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date(e.ts))
        return hhmm + " " + briefOf(e.event, e.summary)
    }

    /** 事件 → 一句话（`18` §315 文案池口径；未收录的事件回退事件名）。 */
    private fun briefOf(event: String, summary: String): String = when (event) {
        "UNLOCK" -> "检测到解锁"
        "WINDOW_OPEN" -> "等待开门"
        "WINDOW_REFRESH" -> "等待时间顺延"
        "WINDOW_CLOSE" -> "等待结束"
        "GATE_HIT" -> "条件成立，已下发"
        "SEND" -> "已下发启动指令"
        "VERIFY_OK" -> "已自动上电"
        "VERIFY_PENDING" -> "未确认上电"
        "SEND_FAIL" -> "下发失败"
        "NET_TIMEOUT" -> "网络超时"
        "BRIDGE_UNAVAILABLE" -> "未连接业务层"
        "WHITEBOX_FAIL" -> "加密组件不可用"
        else -> summary.ifBlank { event }
    }
}

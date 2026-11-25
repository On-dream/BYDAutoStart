package com.bydhooker.autostart.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.bydhooker.autostart.BuildConfig
import com.bydhooker.autostart.core.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出（**2026-11-10 用户需求**）：
 * 「点击导出时，先合并宿主端日志和模块端日志，随后拉起文件管理器让用户选择导出到哪个位置」。
 *
 * ## 与旧方案的区别
 * 旧方案（`18` §7.4 T-16）是**宿主进程**写文件再调系统分享面板，模块 App 侧不管；
 * 且按钮一直是禁用态（T05 未落地）。现在改为：
 *  1. 由模块端（设置页进程）**自己**读两份日志并合并 —— 宿主端靠 root `cat`
 *     （见 [HostLogReader]），模块端直接读自己的 `events.log`（[EventLog.rawText]）；
 *  2. 用 SAF（`ACTION_CREATE_DOCUMENT`）让用户**自己选保存位置**，不写死目录、
 *     不需要存储权限，也不经过分享面板（用户明确要"选位置"而不是"分享给谁"）。
 *
 * ## 内容保真
 * 两份日志都是**原始文本**（不解析、不过滤、不排序）：导出的用途是留证，
 * 任何加工都可能丢线索。宿主端读取失败时**不假装成功**，而是把失败原因写进文件，
 * 让看日志的人知道这一段为什么是空的。
 *
 * ## 隐私
 * 裁定 E（2026-11-08）允许日志原始值（VIN/token 等）落盘；但**仅限本机**，
 * 禁止上传网盘 / 分享 evidence 目录 / 提交公开仓库。导出位置由用户自己选，等同于
 * 用户主动决定这份文件去哪 —— 界面不额外提示，符合「UI 不写警示文案」的定稿口径。
 */
object LogExporter {

    /**
     * 导出结果：文件内容 + **宿主段的状态**（界面据此弹不同提示，`18` §7.4）。
     *
     * v0.9.57 新增 `hostStatus`：没授权 root 时导出**照样成功**（降级只写模块端），
     * 但必须让用户知道「宿主段为什么是空的、去哪里授权」，而不是一律「已导出」。
     */
    class ExportResult(
        val text: String,
        val hostStatus: HostLogReader.Status,
        val hostDetail: String,
    )

    /** 建议文件名：`BYDAutoStart-log-20261110-202015.txt`（SAF 里用户可改）。 */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "BYDAutoStart-log-$stamp.txt"
    }

    /**
     * 生成合并文本（**阻塞**，含 root 读取，必须在 IO 线程调用）。
     *
     * @return 完整文件内容 + 宿主段状态；宿主端失败也返回（原因写在段内）。
     */
    fun build(ctx: Context? = null): ExportResult {
        // ---- 宿主端（官方 App 进程；root 读，失败如实写原因）----
        var hostRes = HostLogReader.Result(null, HostLogReader.Status.FAILED, "读取异常")
        try {
            hostRes = HostLogReader.readRaw()
        } catch (t: Throwable) {
            hostRes = HostLogReader.Result(
                null, HostLogReader.Status.FAILED,
                "读取异常：" + t.javaClass.simpleName,
            )
        }
        val hostText = hostRes.text

        // ---- 模块端（本进程；直接读文件）----
        val moduleText = try {
            EventLog.rawText()
        } catch (_: Throwable) {
            ""
        }

        // ---- ③ 小米智能卡侧刷卡日志（tsmclient 进程写的；需 root）----
        // ⚠️ 模块侧 NFC 链路日志已于 2026-11-12 08:2x **并入模块自身 events.log**
        // （同一个文件、同一套格式），不再单独成段。
        var tsmRes = NfcLogReader.Result(
            null, NfcLogReader.Status.NO_FILE, "未读取（缺少 Context）",
        )
        if (ctx != null) {
            try {
                tsmRes = NfcLogReader.readTsmSide(ctx.cacheDir)
            } catch (t: Throwable) {
                tsmRes = NfcLogReader.Result(
                    null, NfcLogReader.Status.FAILED, "读取异常：" + t.javaClass.simpleName,
                )
            }
        }

        val sb = StringBuilder(1024 * 64)
        sb.append("BYDAutoStart 日志导出\n")
        sb.append("导出时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append('\n')
        sb.append("应用版本：").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("说明：本文件由模块日志页「导出」生成，拼接了 5 段原始日志：")
            .append("① 宿主端（官方 App 进程 events.log）；")
            .append("② 模块端（模块 App 进程 events.log，含模块自身事件与 NFC 链路事件）；")
            .append("③ 小米智能卡侧（com.miui.tsmclient 的 events.log：TSM_* 刷卡原始事件 + 就地执行层）；")
            .append("④ **system 侧（system_server 的执行日志 / 三击 Hook 日志，架构 C 起新增）；**")
            .append("⑤ **超级岛链路（SystemUI 进程的装配 / 事件收发，v3.3.2 起新增）。**")
            .append("各单文件均不超过 512 KB，超出时更早的内容会被截断并留下标记行。\n")
        if (hostText == null) {
            sb.append("注意：本次**没有读到宿主端日志**，只有模块端部分（原因见下方分段）。\n")
        }
        sb.append('\n')

        // ---- 宿主端 ----
        sb.append("=".repeat(56)).append('\n')
        sb.append("宿主端日志（官方 App 进程 com.byd.aeri.caranywhere）\n")
        sb.append("=".repeat(56)).append('\n')
        if (hostText == null) {
            sb.append("【未导出】").append(hostFailReason(hostRes.status)).append('\n')
            sb.append("原因：").append(hostRes.detail.ifBlank { "未知" }).append('\n')
            sb.append(hostHint(hostRes.status)).append('\n')
        } else if (hostText.isBlank()) {
            sb.append("【空】宿主日志文件存在但没有任何内容（模块可能尚未在官方 App 内运行）。\n")
        } else {
            sb.append(hostText)
            if (!hostText.endsWith("\n")) sb.append('\n')
        }
        sb.append('\n')

        // ---- 模块端 ----
        sb.append("=".repeat(56)).append('\n')
        sb.append("模块端日志（模块 App 进程 com.bydhooker.autostart，含 NFC 链路事件）\n")
        sb.append("=".repeat(56)).append('\n')
        if (moduleText.isBlank()) {
            sb.append("【空】本进程还没有记录任何事件。\n")
        } else {
            sb.append(moduleText)
            if (!moduleText.endsWith("\n")) sb.append('\n')
        }
        sb.append('\n')

        // ---- ③ 小米智能卡侧 ----
        sb.append("=".repeat(56)).append('\n')
        sb.append("小米智能卡侧日志（com.miui.tsmclient：events.log，TSM_* 为刷卡原始事件）\n")
        sb.append("=".repeat(56)).append('\n')
        val tsmText = tsmRes.text
        if (tsmText == null) {
            sb.append("【未导出】").append(tsmFailReason(tsmRes.status)).append('\n')
            sb.append("原因：").append(tsmRes.detail.ifBlank { "未知" }).append('\n')
            sb.append(tsmHint(tsmRes.status)).append('\n')
        } else if (tsmText.isBlank()) {
            sb.append("【空】文件存在但没有内容（小米智能卡还没触发过 NFC 事件）。\n")
        } else {
            sb.append(tsmText)
            if (!tsmText.endsWith("\n")) sb.append('\n')
        }
        sb.append('\n')

        // ---- ④ system 侧（架构 C：system_server 里的执行日志；需 root）----
        sb.append("=".repeat(56)).append('\n')
        sb.append("system 侧日志（system_server：执行层 + 电源键三击 Hook）\n")
        sb.append("=".repeat(56)).append('\n')
        var sysText: String? = null
        var sysDetail = ""
        if (ctx != null) {
            try {
                val r = SystemLogReader.read(ctx)
                sysText = r.text
                sysDetail = r.detail
            } catch (t: Throwable) {
                sysText = null
                sysDetail = "读取异常：" + t.javaClass.simpleName
            }
        } else {
            sysDetail = "未读取（缺少 Context）"
        }
        if (sysText == null) {
            sb.append("【未导出】读不到 system 侧日志（需要 root，或该段尚未产生）\n")
            sb.append("原因：").append(sysDetail.ifBlank { "未知" }).append('\n')
            sb.append("提示：system 侧日志在 /data/system/ 下（架构 C 的三击执行链跑在 system_server 里）。\n")
        } else {
            sb.append(sysText)
            if (!sysText.endsWith("\n")) sb.append('\n')
        }
        return ExportResult(sb.toString(), hostRes.status, hostRes.detail)
    }

    /** 小米智能卡侧缺失原因（一眼看出为什么这段是空的）。 */
    private fun tsmFailReason(status: NfcLogReader.Status): String = when (status) {
        NfcLogReader.Status.NO_ROOT -> "读不到小米智能卡侧日志（需要 root）"
        NfcLogReader.Status.NO_FILE -> "小米智能卡侧日志文件还不存在"
        NfcLogReader.Status.FAILED -> "读取失败"
        else -> "未知原因"
    }

    /** 文件里的自助指引（跟界面 toast 一个口径）。 */
    private fun tsmHint(status: NfcLogReader.Status): String = when (status) {
        NfcLogReader.Status.NO_ROOT ->
            "处理：在 KernelSU（或 Magisk）管理器的「超级用户」里允许 BYDAutoStart，" +
                "然后重新导出即可带上这一段。"
        NfcLogReader.Status.NO_FILE ->
            "处理：先在小米智能卡里打开一次刷卡界面（触发 NFC 事件），再重新导出。"
        else ->
            "处理：可先确认小米智能卡已被 LSPosed 注入（模块作用域里勾选了「小米智能卡」）。"
    }


    /** 分段标题里的短结论（一眼看出这一段为什么没内容）。 */
    private fun hostFailReason(status: HostLogReader.Status): String = when (status) {
        HostLogReader.Status.NO_ROOT -> "宿主端日志读不到"
        HostLogReader.Status.TIMEOUT -> "读取宿主端日志超时"
        HostLogReader.Status.NO_HOST_FILE -> "宿主端日志文件还不存在"
        HostLogReader.Status.EMPTY -> "宿主端日志为空"
        else -> "读取失败"
    }

    /** 文件里的自助指引（跟界面 toast 一个口径，不写敏感信息）。 */
    private fun hostHint(status: HostLogReader.Status): String = when (status) {
        HostLogReader.Status.NO_ROOT ->
            "处理：在 KernelSU（或 Magisk）管理器的「超级用户」里允许 BYDAutoStart，" +
                "然后回日志页重新点「导出」即可带上宿主端。"
        HostLogReader.Status.TIMEOUT ->
            "处理：可能在等 root 授权弹窗 —— 授权后重新点「导出」；" +
                "若从未看到弹窗，请到 KernelSU「超级用户」确认 BYDAutoStart 是否已允许。"
        HostLogReader.Status.NO_HOST_FILE ->
            "处理：先在官方 App 里正常使用一次（模块加载后会写 events.log），再重新导出。"
        else ->
            "处理：可先确认官方 App 内模块已生效；详细原因见上方「原因」行。"
    }

    /**
     * 把文本写入用户选定的 URI（SAF）。**阻塞**，IO 线程调用。
     *
     * 用 `"wt"` 打开：truncate 模式 —— 若用户在 SAF 里覆盖了一个已有同名文件，
     * 必须整份替换，不能把新内容接在旧内容后面。
     *
     * @return true = 写入成功
     */
    fun write(ctx: Context, uri: Uri, text: String): Boolean = try {
        val os = ctx.contentResolver.openOutputStream(uri, "wt") ?: return false
        os.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
        true
    } catch (_: Throwable) {
        false
    }
}

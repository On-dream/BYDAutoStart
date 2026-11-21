package com.bydhooker.autostart.data

import java.io.File

/**
 * NFC 链路日志读取（2026-11-11 23:5x）——**为「车里连不上 ADB」这件事补的取证能力**。
 *
 * 刷卡发生在车里，手机连不上电脑 ⇒ 那一刻的现场证据只能靠「先落盘、事后取回」。
 * 本类负责把散在三处的 NFC 相关日志读出来，供 [LogExporter] 合并成一份文件：
 *
 * | 段 | 文件 | 所在进程 | 读取方式 |
 * |---|---|---|---|
 * | tsmclient 全量 | `bydhooker/events.log` | `com.miui.tsmclient` | **root `cat`** |
 *
 * > v3.0 合并：刷卡原始事件（TSM_* 前缀）与就地执行层在同一份文件里；
 * > 旧的 `bydhooker-nfc/tsm-nfc.log(.1)` 不再生成（设备上如有残留可手动删）。
 *
 * 小米智能卡侧那份尤其关键：它记着「`NfcEventMonitorService#z(Intent)` 有没有被调到、
 * 事件带没带 AID、AID 是多少、投递到模块成功没有」—— 这几条只进 logcat 的话，
 * 车里那一刷就**没有任何可回溯的痕迹**。
 *
 * 读不到时**如实返回原因**，绝不假装有数据（与宿主端日志同一口径）。
 */
object NfcLogReader {

    private const val TSM_PKG = "com.miui.tsmclient"
    /**
     * tsmclient 侧唯一日志：`files/bydhooker/events.log`。
     *
     * v3.0 合并：`TsmNfcLog` 不再自落盘 `bydhooker-nfc/tsm-nfc.log(.1)`，
     * 刷卡原始事件（`TSM_SWIPE` / `TSM_NO_AID_EVENT` / `TSM_LOCAL_*` / `TSM_UI_DATA_*`）
     * 与就地执行层的装配 / AID 比对 / 门控 / 车况 / 下发复核，全部在这一份里。
     */
    private const val TSM_EXEC_REL = "files/bydhooker/events.log"

    /** 读取结果：文本为 null 时看 [status]/[detail]。 */
    class Result(val text: String?, val status: Status, val detail: String)

    enum class Status {
        /** 读到了。 */
        OK,
        /** 没有 root（读不了别的 App 的私有目录）。 */
        NO_ROOT,
        /** 文件还不存在（tsmclient 侧还没写过日志）。 */
        NO_FILE,
        /** 其它失败。 */
        FAILED,
    }

    /**
     * 读小米智能卡侧的 `events.log`（需要 root；v3.0 起这是该进程唯一日志）。
     *
     * 读法与 [HostLogReader] 同款：**重定向到临时文件再读**，不把 `cat` 的 stdout 走管道 ——
     * 管道在日志较大时会把子进程写阻塞（实测 64 KB 管道上限）。
     */
    fun readTsmSide(cacheDir: File): Result {
        val err = File(cacheDir, "tsm-nfc-snapshot.err")
        val parts = StringBuilder()
        val hits = ArrayList<String>()
        try {
            // 同一文件的两种路径写法（/data/user/0 与 /data/data）命中一个即可。
            var i = 0
            for (rel in listOf(TSM_EXEC_REL)) {
                for (base in listOf("/data/user/0/$TSM_PKG/", "/data/data/$TSM_PKG/")) {
                    val src = base + rel
                    val dst = File(cacheDir, "tsm-nfc-snapshot-$i.log")
                    i++
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete()
                    val ok = RootShell.execOk(
                        "cat '$src' > '${dst.absolutePath}' 2> '${err.absolutePath}'",
                        timeoutSec = 8,
                    )
                    if (ok && dst.exists() && dst.length() > 0L) {
                        parts.append("---- ").append(src).append(" ----\n")
                        parts.append(dst.readText())
                        if (!parts.endsWith("\n")) parts.append('\n')
                        parts.append('\n')
                        hits.add(src)
                        break
                    }
                }
            }
            if (hits.isNotEmpty()) {
                return Result(parts.toString(), Status.OK, hits.joinToString(" | "))
            }
            // 分不清「没文件」还是「没权限」：看 err 内容与 su 是否可用
            val errText = try {
                if (err.exists()) err.readText() else ""
            } catch (_: Throwable) {
                ""
            }
            val denied = errText.contains("Permission denied", true) ||
                    errText.contains("denied", true)
            return Result(
                null,
                if (denied) Status.NO_ROOT else Status.NO_FILE,
                if (errText.isBlank()) "文件不存在或读不到" else errText.take(200).trim(),
            )
        } catch (t: Throwable) {
            return Result(null, Status.FAILED, "读取异常：" + t.javaClass.simpleName)
        }
    }
}

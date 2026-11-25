package com.bydhooker.autostart.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 读取 **system_server 侧**的执行日志（架构 C，2026-11-13）。
 *
 * 【为什么单独一段】架构 C 之后，三击这条链完全跑在 system_server 里，
 * 它的日志不再落在任何 App 的私有目录，而在 `/data/system/` 下：
 *
 * | 文件 | 内容 |
 * |---|---|
 * | `/data/system/bydhk-exec/bydhooker/events.log` | system 侧业务日志（复用 `EventLog`：SEND / GATE_HIT / VERIFY_OK …）|
 * | （v3.0 已并入 events.log） | 旧的 `bydhk-exec.log` 不再生成（ExecLog 委托 EventLog）|
 * | `/data/system/bydhk-triple.log` | 三击 Hook 自身日志（命中 / 去重 / 震动 / 执行入口调用）|
 *
 * 这三个文件都在 DE 存储 ⇒ **锁屏/未解锁时也能读**，这正是「开机未解锁时
 * 三击到底有没有被识别」这类问题唯一能留下证据的地方。
 *
 * 【为什么要 root】`/data/system` 是 `system:system 0771`，模块 App 不在 system 组 ⇒ 读不了。
 * 用 `su -c "cat ... > 临时文件"`：输出走文件不走管道（避免 64 KB 管道写满导致
 * 子进程永不退出，这是 `HostLogReader` 踩过的坑）。
 */
object SystemLogReader {

    private data class Src(val title: String, val path: String)

    private val SOURCES = listOf(
        // v3.0：ExecLog 已并入 EventLog（同一份 events.log），bydhk-exec.log 不再生成
        Src("system 侧日志（system_server：业务 + so/配置引导）", "/data/system/bydhk-exec/bydhooker/events.log"),
        Src("电源键三击 Hook 日志（system_server）", "/data/system/bydhk-triple.log"),
        // v3.3.2：超级岛链路在 com.android.systemui（独立 uid），日志落在它自己的 DE 存储；
        // 导出时用 root 读回来，否则「岛不显示」这类问题完全没有证据可查。
        Src("超级岛链路日志（SystemUI 进程：装配 / 事件收发 / 白名单）",
            "/data/user_de/0/com.android.systemui/files/bydhooker/sysui.log"),
    )

    /** 读取结果：`text` 为 null 表示读不到（root 不可用或超时）。 */
    class Result(val text: String?, val detail: String)

    /**
     * 读三段并拼好（**阻塞**，含 su 调用，必须在 IO 线程）。
     */
    fun read(ctx: Context): Result {
        val tmpDir = ctx.cacheDir
        val parts = ArrayList<String>()
        var anyOk = false
        val diags = ArrayList<String>()

        for ((i, s) in SOURCES.withIndex()) {
            val dst = File(tmpDir, "sys_snapshot_$i.log")
            // `; true` 保证最后一个文件不存在时退出码也是 0（cat 的退出码取自最后一个参数）
            val cmd = "cat '${s.path}' > '${dst.absolutePath}' 2>/dev/null; true"
            val ok = runSu(cmd)
            if (!ok) {
                diags.add(s.path + "：su 执行失败")
                continue
            }
            val text = try {
                dst.readText()
            } catch (t: Throwable) {
                ""
            }
            if (text.isBlank()) {
                diags.add(s.path + "：文件不存在或为空")
                continue
            }
            anyOk = true
            parts.add("-".repeat(56))
            parts.add(s.title)
            parts.add(s.path)
            parts.add("-".repeat(56))
            parts.add(text.trimEnd())
            parts.add("")
        }

        if (!anyOk) {
            return Result(null, diags.joinToString("；").ifBlank { "没有可读内容" })
        }
        val out = StringBuilder()
        out.append(parts.joinToString("\n"))
        if (diags.isNotEmpty()) {
            out.append("\n（本次未读到的部分：").append(diags.joinToString("；")).append("）\n")
        }
        return Result(out.toString(), "")
    }

    /** 执行一条 root 命令；返回是否成功（超时 8 秒）。 */
    private fun runSu(cmd: String): Boolean {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val done = p.waitFor(8, TimeUnit.SECONDS)
            if (!done) {
                false
            } else {
                p.exitValue() == 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            try {
                p?.inputStream?.close()
                p?.errorStream?.close()
                p?.outputStream?.close()
            } catch (_: Throwable) {
            }
            p?.destroy()
        }
    }
}

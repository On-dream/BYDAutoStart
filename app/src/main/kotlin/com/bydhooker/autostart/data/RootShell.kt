package com.bydhooker.autostart.data

import java.util.concurrent.TimeUnit

/**
 * 设置页侧的 root 命令执行（2026-11-09，「快速授权」用）。
 *
 * 与 [HostLogReader] 同因：KernelSU 下从 App 进程起的 `su -c` 会继承 App 的受限
 * mount namespace，看不到 `/data/data`，必须加 `-M`（mount master）切到全局 namespace。
 * 本机已实测 `su -M -c` 有效，故把它放在第一位，其余作为兼容回退。
 *
 * 用途仅两件：① 把二维码 payload 写进宿主私有目录；② `am start` 拉起官方 App 的授权页。
 */
object RootShell {

    private val PREFIXES: List<Array<String>> = listOf(
        arrayOf("su", "-M", "-c"),
        arrayOf("su", "-c"),
        arrayOf("su", "--mount-master", "-c"),
    )

    @Volatile private var goodVariant = 0

    /** 执行命令；成功（exit 0）返回 stdout，失败返回 null。 */
    fun exec(cmd: String, timeoutSec: Long = 8): String? {
        val order = (listOf(goodVariant) + PREFIXES.indices.toList()).distinct()
        for (i in order) {
            val r = runOne(i, cmd, timeoutSec)
            if (r != null) {
                goodVariant = i
                return r
            }
        }
        return null
    }

    fun execOk(cmd: String, timeoutSec: Long = 8): Boolean = exec(cmd, timeoutSec) != null

    private fun runOne(index: Int, cmd: String, timeoutSec: Long): String? = try {
        val p = Runtime.getRuntime().exec(PREFIXES[index] + cmd)
        // 先等进程（su 首次授权会阻塞），再读流
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() != 0) {
            try {
                p.errorStream.close()
            } catch (_: Throwable) {
            }
            null
        } else {
            try {
                p.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
        }
    } catch (_: Throwable) {
        null
    }
}

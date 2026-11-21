package com.bydhooker.autostart.core

import android.content.Context
import android.provider.Settings

/**
 * 电源键三击开关（`docs/32` §2.4 / §4.5）。
 *
 * 【双存储，任一为 1 即启用】
 * - `Settings.Global bydhk_triple_on`：**读**不需要任何权限（三击判断优先用它）
 * - `/data/system/bydhk_triple_on`：文件兜底（root 写、system_server 读，避开
 *   SettingsProvider 的跨进程缓存与延迟）
 *
 * 【为什么写要 root】`Settings.Global.putInt` 需要 `WRITE_SECURE_SETTINGS`（系统签名权限），
 * 普通 App 拿不到。模块有 KernelSU root，用 `su -c settings put …` 写；文件用 `su -c 'echo … > …'`。
 * **两个都写**，任一条失败不影响另一条。
 *
 * 【默认关】开关从未写过 ⇒ 两个来源都读不到 ⇒ 返回 false。首次注入绝不误触发车控。
 */
object TripleSwitch {

    /** Settings.Global 键名（与 `docs/32` 一致，勿改）。 */
    private const val GLOBAL_KEY = "bydhk_triple_on"

    /** 文件兜底路径（root 写、system_server 读）。 */
    private const val SWITCH_FILE = "/data/system/bydhk_triple_on"

    /** system_server 侧 Hook 全部挂上后写的标记（供设置页显示「是否就绪」）。 */
    private const val READY_FILE = "/data/system/bydhk-triple.ready"

    /**
     * 开关是否开启。
     *
     * 读 Settings 即可（无需 root、毫秒级）；文件是给 system_server 用的第二判据，
     * 模块侧不必每次 `su` 去读它（会慢几百毫秒）。
     *
     * @param ctx 任意 Context（模块 App 进程）；null 时直接返回 false
     */
    fun isEnabled(ctx: Context?): Boolean {
        if (ctx == null) {
            return false
        }
        return try {
            Settings.Global.getInt(ctx.contentResolver, GLOBAL_KEY, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 写入开关（同步阻塞，**必须放子线程**）。
     *
     * @return true = 至少有一个存储写成功
     */
    fun setEnabled(on: Boolean): Boolean {
        val v = if (on) "1" else "0"
        val okSettings = execSu("settings put global $GLOBAL_KEY $v")
        val okFile = execSu("echo $v > $SWITCH_FILE")
        EventLog.info(
            "TRIPLE_SWITCH",
            (if (on) "开启" else "关闭") + "三击电源键解锁" +
                "（settings=$okSettings file=$okFile）",
        )
        return okSettings || okFile
    }

    /**
     * system_server 侧三击 Hook 是否已就绪。
     *
     * 跨进程查不到 Hook 状态，只能看 system_server 写的标记文件。
     * 需要 root 读 `/data/system`；读不到一律按「未就绪」处理（保守，不承诺能力）。
     */
    fun isHookReady(): Boolean {
        val out = execSuWithOutput("cat $READY_FILE 2>/dev/null")
        return out != null && out.trim() == "1"
    }

    /** 读文件判据（诊断用；正常判断走 [isEnabled]）。 */
    fun readFileFlag(): Boolean? {
        val out = execSuWithOutput("cat $SWITCH_FILE 2>/dev/null")
        return when {
            out == null -> null          // 读不到（无 root / 文件不存在）
            out.trim() == "1" -> true
            else -> false
        }
    }

    private fun execSu(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        try {
            p.outputStream.close()
        } catch (_: Throwable) {
        }
        p.waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    private fun execSuWithOutput(cmd: String): String? {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            try {
                p.outputStream.close()
            } catch (_: Throwable) {
            }
            val s = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            s
        } catch (_: Throwable) {
            null
        } finally {
            try {
                p?.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}

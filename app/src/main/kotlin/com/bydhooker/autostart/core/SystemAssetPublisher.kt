package com.bydhooker.autostart.core

import android.content.Context
import com.bydhooker.autostart.bridge.ConfigStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 把 system 侧执行层需要的**全部资产**发布到 `/data/system/bydhk/`（架构 C，`docs/34` §5 N1/N4）。
 *
 * 【为什么必须有这一环】
 * · **so**：真机实测（ArchCProbe v3，2026-11-13 14:57），system_server 里 `System.load`
 *   一个 `/data/system/` 下的 so 会报 `dlopen ... Permission denied` ——
 *   根因是 **SELinux 上下文**：该目录默认 `system_data_file`，而 `system_server` 对它没有
 *   `file:execute`。改成 `system_file`（`/system/lib64` 那些库的上下文）后**同一个文件立刻能加载**，
 *   且**重启后仍然保持**（`restorecon` 不扫这个目录）。
 *   但 `chcon` 要 root，而 **system_server 没有 root** ⇒ 只能由模块 App（KSU root）发布。
 * · **凭据**：模块侧的 `cred.bin` / `cred.salt` 在自己的 `filesDir`，system_server 读不到。
 *   复制过去后 `chown 1000:1000 && chmod 600` —— 只让 system_server 读得到，其他 App 读不到。
 * · **配置**：模块进程靠 remote prefs 推送，system 侧拿不到；改走文件 `config.txt`。
 *
 * 【权限设计】
 * | 文件 | 属主/权限 | 理由 |
 * |---|---|---|
 * | `cred.bin` / `cred.salt` | `1000:1000 0600` | 只有 system_server（uid 1000）可读 |
 * | `config.txt` | `0644` | 不含敏感信息（三击开关在 Settings.Global，不走这里） |
 * | `libwbsk_crypto_tool.so` | `0755` + `system_file` 上下文 | 要能被 `dlopen` |
 *
 * 【幂等】每次调用都重做一遍（so/凭据升级后必须重新 chcon / 复制）。
 * 耗时几百毫秒（含一次 su），**必须在后台线程调用**。
 */
object SystemAssetPublisher {

    /** system 侧工作目录（DE）。 */
    const val DEST_DIR = "/data/system/bydhk"

    const val SO_NAME = "libwbsk_crypto_tool.so"

    private const val SO_ENTRY = "lib/arm64-v8a/$SO_NAME"

    /** 自己的临时中转目录（模块 App 进程可写；su 只能读普通权限文件）。 */
    private const val TMP_DIR = "bydhooker-publish"

    @Volatile
    var lastResult: String = "未执行"

    /**
     * 发布资产（so + 凭据 + 配置）。**在后台线程调用**。
     */
    fun publish(ctx: Context) {
        try {
            val tmpDir = File(ctx.filesDir, TMP_DIR).apply { mkdirs() }
            val steps = ArrayList<String>()

            // ① 配置快照（先写临时文件，再由 root 复制）
            val cfg = File(tmpDir, "config.txt")
            cfg.writeText(buildConfigText(), Charsets.UTF_8)
            steps.add("rm -f $DEST_DIR/config.txt")
            steps.add("cp ${cfg.absolutePath} $DEST_DIR/config.txt")

            // ② 白盒 so
            val apk = moduleApkPath(ctx)
            val so = File(tmpDir, SO_NAME)
            if (apk != null && extract(apk, so)) {
                // ⚠️ **必须先 rm 再 cp**：目标文件上一次已被 chcon 成 `system_file`，
                // 而该类型对写是不可写的（SELinux 只给 system_file:read/execute）。
                // `cp -f` 打开它会被拒 → 兜底 unlink 重建 → 新 inode 继承目录默认的
                // `system_data_file` → 若随后的 chcon 再失败，so 就再也加载不了。
                // 先删后拷能绕开「打开一个不可写的旧 inode」这一步。
                steps.add("rm -f $DEST_DIR/so/$SO_NAME")
                steps.add("cp ${so.absolutePath} $DEST_DIR/so/$SO_NAME")
            } else {
                steps.add("echo SKIP_SO")
            }

            // ③ 凭据（模块自己的那一份）—— **无条件先 rm 旧文件**（v3.0.4）：
            //    旧行为在「模块侧没有凭据」时只 echo SKIP_CRED，不清 system 侧残留 ⇒
            //    用户清除凭据后 /data/system/bydhk/cred.bin 还在，system_server
            //    重启后又会把残留凭据装回来，NFC/三击照样能上电。
            steps.addAll(credentialSteps(ctx))

            // ⚠️ chcon / chmod **不能屏蔽 stderr**：上一版把错误丢了，
            // 结果 system 侧只看到「dlopen Permission denied」，而真正的原因
            // （chcon 没生效）压根没进日志（2026-11-13 15:55 踩过）。
            val cmd = buildString {
                append("mkdir -p $DEST_DIR $DEST_DIR/so; ")
                append(steps.joinToString("; "))
                append("; chmod 644 $DEST_DIR/config.txt; ")
                append("chmod 755 $DEST_DIR/so/$SO_NAME; ")
                append("chcon u:object_r:system_file:s0 $DEST_DIR/so/$SO_NAME; ")
                // 自检要看 **so 所在的那个目录**：v2.0.47 把 so 隔离到 `so/` 子目录，
                // 而这里原来只 `ls $DEST_DIR`（父目录）⇒ 输出里根本没有 so 那一行，
                // `contains(SO_NAME)` 恒 false ⇒ 每次发布都误报「未见 system_file 上下文」，
                // 把真正的问题（发布失败）淹没掉。父目录也一起列，便于看凭据/config 的权限。
                append("echo '--- ls -lZ ---'; ls -lZ $DEST_DIR; ls -lZ $DEST_DIR/so; ")
            }
            val out = execSu(cmd)
            lastResult = if (out.ok) "已发布|${out.text.trim()}" else "失败|${out.text.trim()}"
            // 自检：so 的上下文必须是 system_file，否则 system_server 一定加载不了
            val ctxOk = out.text.contains("system_file") && out.text.contains(SO_NAME)
            if (out.ok && ctxOk) {
                EventLog.info("SYS_PUBLISH", "system 侧资产已就位（so 上下文已确认 system_file）")
            } else if (out.ok) {
                EventLog.error("SYS_PUBLISH",
                    "发布命令成功但**未见 system_file 上下文**，system_server 会加载失败|${out.text.trim().takeLast(400)}")
            } else {
                EventLog.error("SYS_PUBLISH", "发布失败：${out.text.trim().takeLast(400)}")
            }
        } catch (t: Throwable) {
            lastResult = t.javaClass.simpleName + ":" + t.message
            EventLog.error("SYS_PUBLISH_ERR", lastResult)
        }
    }

    /** 配置文本：键名必须与 `BridgeContract` 一致，`ConfigStore.syncFromSnapshot` 才认。 */
    private fun buildConfigText(): String = buildString {
        append("enabled=").append(ConfigStore.enabled).append("\n")
        append("mode=").append(ConfigStore.mode).append("\n")
        append("windowSeconds=").append(ConfigStore.windowSeconds).append("\n")
        append("pollIntervalMs=").append(ConfigStore.pollIntervalMs).append("\n")
    }

    /**
     * 凭据发布步骤：**无条件先 rm system 侧旧文件**（v3.0.4）。
     *
     * 【为什么 rm 不能放在 if 里】模块侧没有凭据时（用户已清除）也必须清掉
     * `/data/system/bydhk/cred.bin` 残留，否则 system_server 重启后会把残留
     * 凭据装回来 —— 「清除凭据」就只清了一半（与 2026-11-09 宿主那次同型）。
     */
    private fun credentialSteps(ctx: Context): List<String> {
        val credBin = File(ctx.filesDir, "cred.bin")
        val credSalt = File(ctx.filesDir, "cred.salt")
        val steps = ArrayList<String>()
        steps.add("rm -f $DEST_DIR/cred.bin $DEST_DIR/cred.salt")
        if (credBin.exists() && credSalt.exists()) {
            steps.add("cp ${credBin.absolutePath} $DEST_DIR/cred.bin")
            steps.add("cp ${credSalt.absolutePath} $DEST_DIR/cred.salt")
            steps.add("chown 1000:1000 $DEST_DIR/cred.bin $DEST_DIR/cred.salt")
            steps.add("chmod 600 $DEST_DIR/cred.bin $DEST_DIR/cred.salt")
        } else {
            steps.add("echo SKIP_CRED")
        }
        return steps
    }

    /**
     * 只同步凭据两份文件到 system 侧（v3.0.4；**后台线程调用**）。
     *
     * 调用点：授权流程 `persistCredentials` 末尾（新 token 立即下发，不再等
     * 「下次模块 App 启动 + 重启手机」）、`clearCredentials` 末尾（残留立即清掉）。
     * 失败只记日志 —— 还有「下次 publish」和 system 侧 `reloadIfFileChanged` 兜底。
     */
    fun publishCredentialsOnly(ctx: Context) {
        try {
            val cmd = "mkdir -p $DEST_DIR; " + credentialSteps(ctx).joinToString("; ")
            val out = execSu(cmd)
            if (out.ok) {
                EventLog.info("SYS_PUBLISH_CRED", "system 侧凭据已同步（rm+cp+chown）")
            } else {
                EventLog.error("SYS_PUBLISH_CRED", "同步失败：${out.text.trim().takeLast(200)}")
            }
        } catch (t: Throwable) {
            EventLog.error("SYS_PUBLISH_CRED_ERR", t.javaClass.simpleName)
        }
    }

    /** 本模块 APK 的绝对路径。 */
    private fun moduleApkPath(ctx: Context): String? = try {
        ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir
    } catch (t: Throwable) {
        null
    }

    /** 从 APK 解出 so，返回是否成功。 */
    private fun extract(apkPath: String, out: File): Boolean = try {
        java.util.zip.ZipFile(apkPath).use { zf ->
            val e = zf.getEntry(SO_ENTRY)
            if (e == null) {
                false
            } else {
                zf.getInputStream(e).use { ins ->
                    FileOutputStream(out).use { fos -> ins.copyTo(fos) }
                }
                true
            }
        }
    } catch (t: Throwable) {
        false
    }

    private class SuResult(val ok: Boolean, val text: String)

    /** 执行一条 root 命令，最多等 8 秒。 */
    private fun execSu(cmd: String): SuResult {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val done = p.waitFor(8, TimeUnit.SECONDS)
            if (!done) {
                SuResult(false, "su 超时")
            } else {
                val out = try {
                    p.inputStream.bufferedReader().readText()
                } catch (t: Throwable) {
                    ""
                }
                SuResult(p.exitValue() == 0, out)
            }
        } catch (t: Throwable) {
            SuResult(false, t.javaClass.simpleName + ":" + t.message)
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

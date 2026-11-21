package com.bydhooker.autostart.data

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 通过 root 读取宿主进程的事件日志 —— **仅供导出合并使用**（2026-11-10 重构）。
 *
 * ## 为什么日志页不再用它
 * 用户 2026-11-10 拍板「模块日志页放弃读取宿主端日志」，理由是实测**不可靠**：
 * 设置页与官方 App 不同 UID，宿主 `events.log` 在官方 App 私有目录，只能靠 root 读；
 * 而 App 进程里的 `su` 处在受限 mount namespace，读取时好时坏 → 日志页可能展示
 * **旧的/不完整**的内容，看起来像"当前日志"，与事实不符。
 *
 * 结论：**页面只显示本进程可信的事件；宿主端只在导出时合并**（见 [LogExporter]），
 * 导出件带来源分段与读取失败原因，用户自己判断可信度。
 *
 * ## v0.9.55 修复的两个「必然失败」
 *
 * ### 错误 1：管道死锁（必现，与 root 授权无关）
 * 旧实现 `exec(...)` 后**先 `waitFor(5 s)` 再读输出**。宿主日志实测 **1,032,605 B**，
 * 而管道缓冲区只有 ~64 KB：`cat` 写满管道就阻塞，子进程永不退出 → `waitFor` 超时
 * → 被误判成「root 不可用 / 未授权」。**日志越大越必然失败**，与权限无关。
 *
 * 现在改为**重定向到临时文件**：`su -c "cat <宿主> > <本 App cache>/host_snapshot.log"`。
 * 命令的标准输出只走文件、不进管道，天然不会撑爆；App 只在命令结束后**普通读文件**。
 *
 * ### 错误 2：`su` 路径不确定
 * `Runtime.exec(["su", …])` 靠 `PATH` 命中，可能落到 ROM 自带的假 `su`
 * （执行命令但**不提权** → `cat` 报 Permission denied）。现在按绝对路径优先：
 * KernelSU(`/data/adb/ksu/bin/su`) → Magisk(`/debug_ramdisk/su`、`/sbin/su`)
 * → 系统(`/system/bin/su`、`/system/xbin/su`)，全不存在才退回 `"su"`。
 *
 * ## v0.9.57：把「没授 root」和「其它失败」分开（用户要求降级 + 提示授权）
 *
 * 旧实现所有失败都吞成一句 `lastError`，界面只能提示「已导出」，用户不知道
 * 宿主段为什么是空的。现在 [readRaw] 返回 [Result]，用 [Status] 明确区分：
 *
 * | Status | 含义 | 界面动作 |
 * |---|---|---|
 * | `OK` | 读到宿主日志 | 正常 |
 * | `EMPTY` | 文件存在但 0 字节 | 正常 + 说明 |
 * | `NO_ROOT` | **没授权 root**（su 拒绝 / 找不到 su） | **降级只导模块端 + toast 引导授权** |
 * | `TIMEOUT` | su 卡住（常见于等授权弹窗） | 同上（提示里带「若弹出授权请允许」）|
 * | `NO_HOST_FILE` | root 可用但宿主日志还没生成 | 降级 + 说明 |
 * | `FAILED` | 其它失败 | 降级 + 说明 |
 *
 * **降级的语义**：宿主端读不到**不算导出失败** —— 模块端日志照样完整写出，
 * 只是宿主段替换成原因说明；导出件的可信度由分段与原因行自己交代，不假装成功。
 */
object HostLogReader {

    /** 宿主端读取结果的分类（界面按此给不同提示）。 */
    enum class Status {
        /** 读到内容。 */
        OK,

        /** 宿主日志文件存在但为空（模块还没在官方 App 内跑过）。 */
        EMPTY,

        /** 没有可用的 root：su 拒绝执行（未授权）或设备上根本没有 su。 */
        NO_ROOT,

        /** su 卡住超时 —— 常见于 KernelSU/LSPosed 的授权弹窗还没点。 */
        TIMEOUT,

        /** root 可用，但宿主日志文件不存在（还没写过）。 */
        NO_HOST_FILE,

        /** 其它失败。 */
        FAILED,
    }

    /** [readRaw] 的返回值：`text` 为 null 时看 [status] 与 [detail]。 */
    class Result(val text: String?, val status: Status, val detail: String)

    private const val HOST_PKG = "com.byd.aeri.caranywhere"

    /** 宿主日志候选路径（`/data/user/0` 与 `/data/data`）。 */
    private val CANDIDATES = listOf(
        "/data/user/0/$HOST_PKG/files/bydhooker/events.log",
        "/data/data/$HOST_PKG/files/bydhooker/events.log",
    )

    /**
     * su 绝对路径候选（按「App UID 最可能真正跑得起来」排序）。
     *
     * ## 排序依据（2026-11-10 真机实测，v1.0.1 修复）
     * 设备上 `/data/adb/ksu/bin/su` **并不存在**（那是 KernelSU 内部路径，且 `/data/adb`
     * 权限 `0700 root` → 普通 App UID 连目录都进不去，`execve` 返回 **EACCES**，
     * Java 侧表现为 `IOException … error=13, Permission denied`）。
     * 而 `/system/bin/su` 是 `0755 root root`、App 可 exec、由 root 管理器决定是否放行，
     * 才是真正可用的入口。**把 `/data/adb` 下的 su 放在首位是 v1.0.0 误判「未授权」的根因**：
     * 第一个候选就 EACCES，又被当成「拒绝授权」立即返回，永远走不到 `/system/bin/su`。
     *
     * 故顺序改成：系统路径优先 → Magisk/KSU 专用路径 → `PATH` 兜底。
     */
    private val SU_BINS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "su",
    )

    /**
     * su 参数形态（`-c` 之前的前缀）。
     * 普通 `su -c` 是 KernelSU / Magisk 通用形态；`-M` / `--mount-master` / `-mm`
     * 用于「全局 mount namespace」——宿主私有目录在 App 自己的 namespace 里可能不可见。
     */
    private val SU_FLAGS: List<Array<String>> = listOf(
        emptyArray(),
        arrayOf("-M"),
        arrayOf("--mount-master"),
        arrayOf("-mm"),
    )

    /** 已确认可用的 (su 路径下标, flag 下标) 组合；-1 = 未确定。 */
    @Volatile private var goodBin = -1
    @Volatile private var goodFlag = -1

    private const val PREFS_NAME = "bydhooker_su"
    // 存**路径字符串**而不是数组下标：v1.0.1 调整过 SU_BINS 顺序，旧下标会指向错路径
    private const val KEY_BIN = "bin_path"
    private const val KEY_FLAG = "flag"

    @Volatile private var appCtx: android.content.Context? = null

    /** 单次 su 命令的时间预算（毫秒）。重定向后不再有死锁，1~2 s 正常就能完成。 */
    private const val SU_SINGLE_TIMEOUT_MS = 6_000L

    /** 全部尝试的总预算（毫秒）。 */
    private const val SU_TOTAL_BUDGET_MS = 15_000L

    /** 最近一次读取失败的简述（导出件里如实写出，不含敏感值）。 */
    @Volatile var lastError: String = ""
        private set

    /** 最近一次成功使用的 su 路径（诊断用）。 */
    @Volatile var lastSuPath: String = ""
        private set

    /** 注入 Context（`SettingsActivity.onCreate` 调用一次）。 */
    fun attach(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        if (goodBin < 0 || goodFlag !in SU_FLAGS.indices) {
            val p = loadStr(KEY_BIN, "")
            val i = SU_BINS.indexOf(p)
            val f = loadInt(KEY_FLAG, -1)
            if (i >= 0 && f in SU_FLAGS.indices) {
                goodBin = i
                goodFlag = f
            } else {
                goodBin = -1
                goodFlag = -1
            }
        }
    }

    private fun loadStr(key: String, def: String): String = try {
        appCtx?.getSharedPreferences(PREFS_NAME, 0)?.getString(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    private fun loadInt(key: String, def: Int): Int = try {
        appCtx?.getSharedPreferences(PREFS_NAME, 0)?.getInt(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    private fun save(key: String, v: String) {
        try {
            appCtx?.getSharedPreferences(PREFS_NAME, 0)?.edit()?.putString(key, v)?.apply()
        } catch (_: Throwable) {
        }
    }

    private fun save(key: String, v: Int) {
        try {
            appCtx?.getSharedPreferences(PREFS_NAME, 0)?.edit()?.putInt(key, v)?.apply()
        } catch (_: Throwable) {
        }
    }

    // ---- 单次尝试的分类 ----

    private enum class Kind { OK, EMPTY, DENIED, TIMEOUT, NO_SU, NO_FILE, OTHER }

    private class Attempt(val kind: Kind, val exit: Int, val err: String, val text: String?)

    /** `exec` 的原始结果。`ioError != null` 表示**进程没起来**（su 不存在 / 无执行权限）。 */
    private class SuRun(
        val exit: Int,
        val err: String,
        val timedOut: Boolean,
        val ioError: String?,
    )

    /**
     * 读宿主 `events.log` **全文原始文本**（不过滤、不截断），供导出合并。
     *
     * 失败时**不抛异常**：返回 [Result] 的 `text == null`，用 [Result.status] 说明原因，
     * 调用方据此决定要弹什么提示；导出件里也照此写原因行。
     */
    fun readRaw(): Result {
        val ctx = appCtx
            ?: return Result(null, Status.FAILED, "未初始化（HostLogReader.attach 未调用）")
        val dst = File(ctx.cacheDir, "host_snapshot.log")
        val errFile = File(ctx.cacheDir, "host_snapshot.err")
        lastError = ""

        val deadline = System.currentTimeMillis() + SU_TOTAL_BUDGET_MS
        // 只保留**第一条**失败信息（重复的 48 次尝试写进导出件只会让人读不下去）
        val firstDiag = StringBuilder()
        var attempts = 0
        var sawNoSu = 0
        var sawTimeout = 0
        var sawNoFile = 0
        var sawOther = 0

        // ① 优先复用上次成功的 (su, flag) 组合 —— 省掉一轮全量探测
        if (goodBin in SU_BINS.indices && goodFlag in SU_FLAGS.indices) {
            for (path in CANDIDATES) {
                val a = attempt(goodBin, goodFlag, path, dst, errFile)
                when (a.kind) {
                    Kind.OK -> return doneOk(a)
                    Kind.EMPTY -> return emptyResult()
                    Kind.DENIED -> return noRoot("${SU_BINS[goodBin]} 拒绝执行：${brief(a.err)}")
                    else -> {}
                }
            }
            // 记住的组合失效（换了 su / 升级了 manager）→ 重新探测
            goodBin = -1
            goodFlag = -1
        }

        // ② 逐 su 路径 × 逐 flag 形态 × 逐候选路径
        bin@ for (bi in SU_BINS.indices) {
            for (fi in SU_FLAGS.indices) {
                for (path in CANDIDATES) {
                    if (System.currentTimeMillis() > deadline) {
                        return budgetExhausted(sawNoSu, sawTimeout, sawNoFile, sawOther, attempts, firstDiag)
                    }
                    val a = attempt(bi, fi, path, dst, errFile)
                    attempts++
                    when (a.kind) {
                        Kind.OK -> {
                            goodBin = bi
                            goodFlag = fi
                            save(KEY_BIN, SU_BINS[bi])
                            save(KEY_FLAG, fi)
                            return doneOk(a)
                        }
                        Kind.EMPTY -> return emptyResult()
                        // 真·拒绝：su **跑起来了**、它自己说没权限 → 换路径/换 flag 也不会变
                        Kind.DENIED -> return noRoot("${SU_BINS[bi]} 拒绝执行：${brief(a.err)}")
                        // 这条路径上没有能跑的 su（不存在 / 父目录不可穿越）→ 直接换下一个 su 路径，
                        // 不必再试它的 3 种 flag 形态（flag 只影响 mount namespace，不影响能否 exec）
                        Kind.NO_SU -> {
                            sawNoSu++
                            if (firstDiag.length < 220) {
                                firstDiag.append(SU_BINS[bi]).append(" → ")
                                    .append(brief(a.err)).append("; ")
                            }
                            continue@bin
                        }
                        Kind.NO_FILE -> sawNoFile++
                        Kind.TIMEOUT -> sawTimeout++
                        Kind.OTHER -> sawOther++
                    }
                    if (firstDiag.isEmpty()) firstDiag.append(brief(a.err))
                }
            }
        }
        return budgetExhausted(sawNoSu, sawTimeout, sawNoFile, sawOther, attempts, firstDiag)
    }

    private fun doneOk(a: Attempt): Result {
        lastError = ""
        return Result(a.text ?: "", Status.OK, "")
    }

    private fun emptyResult(): Result {
        lastError = "宿主日志文件为空"
        return Result("", Status.EMPTY, lastError)
    }

    private fun noRoot(detail: String): Result {
        // 中文口吻的结论 + 第一条原始错误（诊断用，不含敏感值）
        lastError = "未获得 root 权限：$detail"
        return Result(null, Status.NO_ROOT, lastError)
    }

    /** 预算耗尽 / 全部候选走完仍失败 → 按「试过什么」归纳成用户能懂的结论。 */
    private fun budgetExhausted(
        noSu: Int,
        timeout: Int,
        noFile: Int,
        other: Int,
        attempts: Int,
        firstDiag: StringBuilder,
    ): Result {
        val d = firstDiag.toString().take(120).ifBlank { "无输出" }
        // 全是「进程起不来」= 本 App 的 UID 执行不了 su（未 root，或没被授权）
        val status = when {
            timeout > 0 -> Status.TIMEOUT
            noSu > 0 && noFile == 0 && other == 0 -> Status.NO_ROOT
            noFile > 0 -> Status.NO_HOST_FILE
            else -> Status.FAILED
        }
        lastError = when (status) {
            Status.NO_ROOT -> "未获得 root 权限：没有可执行的 su（$d；共试 $attempts 次）"
            Status.TIMEOUT -> "root 读取超时（可能在等待授权弹窗，或 su 被挂起；共试 $attempts 次）"
            Status.NO_HOST_FILE -> "宿主日志文件不存在（官方 App 内可能还没运行过模块）：$d"
            else -> "root 读取失败（$d；共试 $attempts 次）"
        }
        return Result(null, status, lastError)
    }

    private fun brief(s: String): String = s.replace("\n", " ").trim().take(90)

    /** 读 stderr 文件的原始文本（不含占位说明，供分类用）。 */
    private fun readErrRaw(f: File): String = try {
        if (f.exists()) f.readText().trim() else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 判断「su 跑起来了，但它说没权限」。
     *
     * **只对进程 stderr 用**（`ioError` 不走这里有 v1.0.1 的原因：exec 的
     * `error=13` 可能是父目录不可穿越，不代表授权状态，见 [attempt]）。
     */
    private fun looksDenied(e: String): Boolean {
        val s = e.lowercase()
        return s.contains("permission denied") || s.contains("not allowed") ||
            s.contains("denied") || s.contains("operation not permitted") ||
            s.contains("unauthorized")
    }

    private fun looksNoFile(e: String): Boolean {
        val s = e.lowercase()
        return s.contains("no such file") || s.contains("cannot open") ||
            s.contains("does not exist") || s.contains("not found")
    }

    /**
     * 用指定 (su 路径, flag 形态) 读一条候选路径，并把结果分类。
     *
     * 成功 = 临时文件非空 → [Kind.OK]；`cat` 成功但文件 0 字节 → [Kind.EMPTY]。
     */
    private fun attempt(bi: Int, fi: Int, src: String, dst: File, errFile: File): Attempt {
        try {
            dst.delete()
            errFile.delete()
        } catch (_: Throwable) {
        }
        // 关键点：**cat 的 stdout 直接进文件**，不进管道 → 1 MB 日志也不会撑爆管道。
        // 末尾 `chmod 666` 让本 App（与 root 不同 UID）能读回这两个文件。
        val cmd = "cat '$src' > '${dst.absolutePath}' 2> '${errFile.absolutePath}'; " +
            "chmod 666 '${dst.absolutePath}' '${errFile.absolutePath}' 2>/dev/null"
        val run = execSu(bi, fi, cmd)
        val rawErr = run.err.ifBlank { readErrRaw(errFile) }

        val len = try {
            if (dst.exists()) dst.length() else 0L
        } catch (_: Throwable) {
            0L
        }
        val text = if (len > 0) try {
            dst.readText()
        } catch (t: Throwable) {
            lastError = "读回临时文件失败：" + t.javaClass.simpleName
            null
        } else {
            null
        }
        // 注意：**不删 errFile** —— 下一次尝试在开头统一删除，因此不会读到上次残留。
        try {
            dst.delete()
        } catch (_: Throwable) {
        }

        return when {
            // 进程根本没起来 → **一律**当作「这个路径上没有可用的 su」，继续试下一个候选。
            //
            // v1.0.1 修正：以前把 `error=13`（EACCES）判成「拒绝授权」并立即返回，
            // 这是**错的**——exec 的 errno 分不清「文件不存在」和「父目录不可穿越」：
            // `/data/adb` 是 0700 root，App UID exec 里面的任何东西都拿 EACCES，
            // 与是否被授予 root 无关。真正的「拒绝」只能来自**跑起来的** su 的 stderr。
            run.ioError != null -> Attempt(Kind.NO_SU, -1, run.ioError, null)
            text != null && text.isNotBlank() -> {
                lastSuPath = SU_BINS[bi] + SU_FLAGS[fi].joinToString("")
                Attempt(Kind.OK, run.exit, rawErr, text)
            }
            run.timedOut -> Attempt(Kind.TIMEOUT, -1, rawErr.ifBlank { "超时" }, null)
            looksDenied(rawErr) -> Attempt(Kind.DENIED, run.exit, rawErr, null)
            looksNoFile(rawErr) -> Attempt(Kind.NO_FILE, run.exit, rawErr, null)
            len == 0L && run.exit == 0 -> Attempt(Kind.EMPTY, 0, rawErr, null)
            else -> Attempt(Kind.OTHER, run.exit, rawErr.ifBlank { "exit=${run.exit}" }, null)
        }
    }

    // ---- su 执行 ----

    /**
     * 执行一条 su 命令。**先起线程排空 stdout/stderr，再 waitFor** —— 顺序反了会在
     * 输出超过管道缓冲（~64 KB）时死锁（v0.9.54 及之前的真实 bug，见文件头）。
     */
    private fun execSu(bi: Int, fi: Int, cmd: String): SuRun {
        val argv = arrayOf(SU_BINS[bi]) + SU_FLAGS[fi] + arrayOf("-c", cmd)
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(argv)
            val proc = p
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread {
                try {
                    out.append(proc.inputStream.bufferedReader().readText())
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    err.append(proc.errorStream.bufferedReader().readText())
                } catch (_: Throwable) {
                }
            }
            tOut.isDaemon = true
            tErr.isDaemon = true
            tOut.start()
            tErr.start()

            val finished = proc.waitFor(SU_SINGLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                SuRun(-1, err.toString(), timedOut = true, ioError = null)
            } else {
                tOut.join(500)
                tErr.join(500)
                SuRun(proc.exitValue(), err.toString(), timedOut = false, ioError = null)
            }
        } catch (t: Throwable) {
            try {
                p?.destroyForcibly()
            } catch (_: Throwable) {
            }
            // 进程根本没起来：su 不存在（未 root）或本 UID 不许执行（未授权，见 kind 判定）
            SuRun(
                -1, "", timedOut = false,
                ioError = t.javaClass.simpleName + " " + (t.message ?: "").take(120),
            )
        }
    }
}

package com.bydhooker.autostart.core

import android.content.Context
import com.bydhooker.autostart.model.LogEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 事件日志（`15` §6.3 / §8）—— **宿主进程**写自己的 `files/bydhooker/events.log`，
 * 设置页进程写自己的一份，两端互不可读（不同 UID）。
 *
 * - 单文件上限 **512 KB**（2026-11-10 用户指定），触顶裁到尾部约 256 KB（见 [MAX_FILE_BYTES]）
 * - 内存缓冲上限 500 条（`18` §7.2 滚动表现）
 * - 敏感字段写入前统一掩码（13 类字段，含 DEBUG 级；裁定 E：统计仍须如实）
 * - **导出（2026-11-10 改）**：设置页点「导出」时由 [com.bydhooker.autostart.data.LogExporter]
 *   合并两端原始文本 → SAF 由用户选保存位置。宿主端靠 root `cat`（[com.bydhooker.autostart.data.HostLogReader]），
 *   模块端直接 [rawText]。旧方案「宿主经系统分享面板」已废弃（按钮长期禁用）。
 * - 日志页**只读本进程**（`LogRefresher` → [tail]），不再尝试读宿主（实测 root 不可靠）。
 */
object EventLog {

    private const val MAX_ENTRIES = 500
    private const val DIR_NAME = "bydhooker"
    private const val FILE_NAME = "events.log"
    private const val EXPORT_PREFIX = "bydautostart-log-"

    /**
     * 单文件字节上限 **512 KB**（2026-11-10 用户指定：「限定宿主端日志文件最大不超过 512KB」）。
     *
     * 为什么要限：宿主进程里 DEBUG 级 `STATUS` / `RECEIPT` 每秒一条，长时间挂机后
     * `events.log` 会无限增长（官方 App 私有目录，用户很难自己清），既占空间又让
     * 导出文件臃肿。且本模块的日志最终是给人看的，超出部分没人会读。
     *
     * 触顶后**保留尾部** [TRIM_KEEP_BYTES]（约 256 KB）：近期事件才是排查所需，
     * 砍掉最早的一半，比「清空」保留的信息多，也比「滚动成多个文件」简单
     * （多文件会让导出与 root 读取的路径处理都变复杂，收益不成正比）。
     */
    private const val MAX_FILE_BYTES = 512 * 1024

    /** 触顶后保留的尾部字节数（约一半）。按**行边界**切，不会切断多字节字符。 */
    private const val TRIM_KEEP_BYTES = 256 * 1024

    /** 截断标记行（保留在文件头，说明前面还有更早的内容已被丢弃）。 */
    private const val TRIM_MARK = "[日志超过 512 KB，更早的内容已截断]"

    /**
     * 当前文件字节数缓存（-1 = 未知，下次 append 时读 `length()` 重建）。
     * 用计数代替每次 `f.length()`：宿主进程 append 频率可达每秒数条。
     */
    @Volatile private var fileBytes = -1L

    /**
     * 行内时间格式。**必须带日期**（2026-11-09 修复）：原先只写 `HH:mm:ss`，
     * `tail()` 用 `SimpleDateFormat("HH:mm:ss")` 反解时缺失年份 → 落到 **1970-01-01**，
     * 日志页显示日期全错；且跨天后无法区分同一天的不同时刻。
     */
    private const val TS_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /**
     * 时间格式器：**线程局部复用**（R-02，2026-11-16）。
     *
     * `SimpleDateFormat` 非线程安全，不能做共享单例；但每次调用都 `new` 一个是纯浪费：
     * `appendFile` 每条日志一次、`parseTs` 每行 1~2 次，而 `tail(50)` 默认每 3 s 跑一轮
     * ⇒ 稳态下光构造 SDF 就占了刷新开销的大头。
     */
    private val TS_FMT: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(TS_PATTERN, Locale.US)
        }

    private fun tsFmt(): SimpleDateFormat = TS_FMT.get()!!

    /** 旧格式（仅 `HH:mm:ss`、无日期）的解析器，同样复用（R-02）。 */
    private val TIME_ONLY_FMT: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        }

    /**
     * 行解析正则：**只编译一次**（R-02）。
     *
     * 原实现把 `Regex(...)` 写在 `mapNotNull` 内部 —— 每解析一行就重新编译一次，
     * `tail(50)` 每轮 50 次编译，代价远超那一次读盘。
     */
    private val LINE_RE = Regex(
        """(?:(\d{4}-\d{2}-\d{2}) )?(\d{2}:\d{2}:\d{2}) \|\s*(\w+)\s*\|\s*(\S+)\s*\| (.*)""",
    )

    /**
     * `tail()` 反向扩窗的上限（R-02）。
     *
     * 从 8 KB 开始，行数不够就 ×4（8→32→128 KB），到达本上限仍不够就返回已有部分。
     * 日志单文件封顶 512 KB，而 50 行通常只要几 KB ⇒ 稳态只读几 KB，不再全文读盘。
     */
    private const val MAX_TAIL_WINDOW_BYTES = 128L * 1024L

    private val buffer = ArrayDeque<LogEntry>()

    /** 反向推送器（宿主装配时注入：把 JSON 写进 remote prefs）；null = 未装配。 */

    /** 上次推送时间（节流：日志写入频繁，最多每 1500 ms 推一次快照）。 */

    /**
     * 框架日志桥（2026-11-09）：宿主装配时注入 `XposedModule.log(priority, tag, msg)`。
     * 这是**官方日志通道** —— 输出进 LSPosed 管理器「日志」页，同时进 logcat，
     * 不需要任何跨进程共享（remote prefs 反向写实测无效）。
     */
    @Volatile private var frameworkLogger: ((Int, String, String) -> Unit)? = null

    /** remote prefs 反向推送实测失败后永久停用，避免反复尝试与刷日志。 */
    private var file: File? = null

    /** 宿主进程内初始化（onPackageLoaded 后调用一次；幂等）。 */
    fun init(filesDir: java.io.File) {
        if (file != null) return
        try {
            val dir = File(filesDir, DIR_NAME).apply { mkdirs() }
            val f = File(dir, FILE_NAME)
            // 预创建空文件（2026-11-09）：目录建好后若尚无任何事件，文件并不存在，
            // 外部读取方（设置页 root 直读）会拿到 ENOENT，被误判成「路径错误」。
            if (!f.exists()) {
                try {
                    f.createNewFile()
                } catch (_: Throwable) {
                }
            }
            file = f
            // 初始化字节计数：进程重启后第一次 append 就能正确判断是否触顶。
            fileBytes = try {
                f.length()
            } catch (_: Throwable) {
                -1L
            }
        } catch (_: Throwable) {
            file = null
        }
    }

    fun info(event: String, summary: String) = log("INFO", event, summary)
    fun debug(event: String, summary: String) = log("DEBUG", event, summary)
    fun warn(event: String, summary: String) = log("WARN", event, summary)
    fun error(event: String, summary: String) = log("ERROR", event, summary)

    /**
     * 幂等初始化（供 Java 侧 / Provider 冷启动路径调用）。
     *
     * 架构 C：四个进程（模块 App / 官方 App / tsmclient / system_server）各自
     * 调一次 `init(自己的 filesDir)`，各自落一份 `events.log`（单份上限 512 KB）。
     * Provider 冷启动、Hook 装配等入口可能比 Activity 更早，故由写入方自己兜底初始化。
     */
    fun ensureInit(filesDir: java.io.File) {
        init(filesDir)
    }

    /**
     * 供 Java 侧写入一条日志（级别 + 事件名 + 摘要）。
     *
     * 走的是与 Kotlin 侧**完全相同**的写入路径（同一 buffer、同一文件、同一掩码与裁剪），
     * 这样「模块自身事件」与「NFC 链路事件」合并后格式统一、也只有一个 512 KB 上限。
     */
    fun logExternal(level: String, event: String, summary: String) {
        log(level, event, summary)
    }

    private fun log(level: String, event: String, rawSummary: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, event, mask(rawSummary))
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        appendFile(entry)
        // 框架日志（LSPosed 管理器 + logcat）：**只发 INFO 及以上**。
        // DEBUG 级里有每秒一轮的 STATUS/RECEIPT 轮询，打进管理器会冲掉关键事件。
        if (level != "DEBUG") {
            try {
                frameworkLogger?.invoke(priorityOf(level), "BYDAutoStart", "$event | ${entry.summary}")
            } catch (_: Throwable) {
            }
        }
    }

    /** 日志级别 → android.util.Log 优先级（4=INFO / 5=WARN / 6=ERROR）。 */
    private fun priorityOf(level: String): Int = when (level) {
        "WARN" -> 5
        "ERROR" -> 6
        else -> 4
    }

    /** 注入框架日志桥（`XposedModule.log`）；宿主装配时调用一次。 */
    fun attachFrameworkLogger(logger: (Int, String, String) -> Unit) {
        frameworkLogger = logger
    }

    /** 读取当前缓冲（设置页经桥分页读取时使用；最新在最后的快照）。 */
    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        try {
            file?.writeText("")
            fileBytes = 0
        } catch (_: Throwable) {
            fileBytes = -1
        }
    }

    /**
     * 读本进程 `events.log` **全文原始文本**（导出用，不过滤不截断）。
     *
     * 与 [tail] 的区别：`tail` 会解析成 `LogEntry` 供日志页渲染；导出的诉求是
     * **保真留证**，所以原样给出（含被 UI 过滤掉的 DEBUG 轮询、含截断标记行）。
     */
    fun rawText(): String {
        val f = file ?: return ""
        return try {
            f.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    /** 当前文件字节数（导出头 / 诊断用；-1 表示未知）。 */
    fun fileSizeBytes(): Long = try {
        file?.length() ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    /**
     * 读本进程 events.log 尾部 n 行（设置页进程日志页数据源——模块 App 自己的
     * 授权流事件；宿主侧判定事件经 `18` §7.4 导出，两进程私有目录互不可读）。
     */
    fun tail(n: Int): List<LogEntry> {
        val f = file ?: return emptyList()
        return try {
            readTailLines(f, n).mapNotNull { line ->
                val m = LINE_RE.find(line) ?: return@mapNotNull null
                val dateStr = m.groupValues[1]
                val timeStr = m.groupValues[2]
                val ts = parseTs(dateStr, timeStr) ?: System.currentTimeMillis()
                LogEntry(ts, m.groupValues[3], m.groupValues[4], m.groupValues[5])
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 只读文件**尾部**若干非空行（R-02，2026-11-16）。
     *
     * 原实现 `f.readText()` —— 把整份日志（上限 512 KB）读进内存再 `takeLast(n)`，
     * 而设置页默认每 3 s 就跑一轮，属于纯浪费。
     *
     * 现在从尾部 8 KB 窗口开始，行数不够就 ×4 扩窗（上限 [MAX_TAIL_WINDOW_BYTES]）：
     *  - 窗口起点在文件中间时，**第一条可能是被截断的半行**（也可能切在 UTF-8 多字节
     *    字符中间），所以丢弃 `drop(1)`；
     *  - 只有读到的行数足够、或已经扩到文件开头、或到达上限才返回，
     *    因此不会因为「刚好切在行中间」而少给一行。
     */
    private fun readTailLines(f: File, maxLines: Int): List<String> {
        val raf = try {
            java.io.RandomAccessFile(f, "r")
        } catch (_: Throwable) {
            return emptyList()
        }
        try {
            val len = raf.length()
            if (len <= 0L) return emptyList()
            var window = 8L * 1024L
            var out: List<String>? = null
            while (out == null) {
                val start = maxOf(0L, len - window)
                val size = (len - start).toInt()
                val buf = ByteArray(size)
                raf.seek(start)
                raf.readFully(buf)
                val all = String(buf, StandardCharsets.UTF_8)
                    .split('\n')
                    .filter { it.isNotBlank() }
                // 起点不在文件头 ⇒ 首行可能是半行（或半个 UTF-8 字符），丢掉
                val usable = if (start > 0L && all.isNotEmpty()) all.drop(1) else all
                if (usable.size > maxLines || start == 0L || window >= MAX_TAIL_WINDOW_BYTES) {
                    out = usable.takeLast(maxLines)
                } else {
                    window *= 4
                }
            }
            return out
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            try {
                raf.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 解析行内时间戳：新格式（`yyyy-MM-dd HH:mm:ss`）直接解析；旧格式（仅 `HH:mm:ss`）
     * 用**当天日期**补齐（避免 1970-01-01，历史上日志页日期全错的根因）。
     */
    private fun parseTs(dateStr: String, timeStr: String): Long? = try {
        if (dateStr.isNotEmpty()) {
            tsFmt().parse("$dateStr $timeStr")?.time
        } else {
            val t = TIME_ONLY_FMT.get()!!.parse(timeStr)
            if (t == null) {
                null
            } else {
                val src = java.util.Calendar.getInstance().apply { time = t }
                java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, src.get(java.util.Calendar.HOUR_OF_DAY))
                    set(java.util.Calendar.MINUTE, src.get(java.util.Calendar.MINUTE))
                    set(java.util.Calendar.SECOND, src.get(java.util.Calendar.SECOND))
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 导出：把当前缓冲写为带时间戳文件名的 txt，返回文件（宿主私有目录内）。
     * 分享动作由 ModuleBridgeHost 触发（宿主进程内的系统分享面板），
     * 明码内容 = 裁定 E 允许；**禁止上传 / 分享到公开仓库**（06 §1.3）。
     */
    fun export(filesDir: java.io.File): File? {
        val dir = File(filesDir, DIR_NAME).apply { mkdirs() }
        val name = EXPORT_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val out = File(dir, name)
        return try {
            out.printWriter().use { w ->
                w.println("BYDAutoStart 事件日志（隐私字段已掩码）")
                snapshot().forEach { e ->
                    w.println("%s | %-5s | %-14s | %s".format(
                        tsFmt().format(Date(e.ts)), e.level, e.event, e.summary))
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun appendFile(entry: LogEntry) {
        val f = file ?: return
        try {
            val bytes = "%s | %-5s | %-14s | %s\n".format(
                tsFmt().format(Date(entry.ts)), entry.level, entry.event, entry.summary,
            ).toByteArray(StandardCharsets.UTF_8)

            // 512 KB 上限：先看写入后是否会越界，越界则先把文件裁到尾部 ~256 KB。
            // 计数字段失准（-1）时以真实 length 重建，避免一直不裁。
            if (fileBytes < 0) fileBytes = f.length()
            if (fileBytes + bytes.size > MAX_FILE_BYTES) {
                trimToTail(f)
            }
            f.appendBytes(bytes)
            fileBytes += bytes.size
        } catch (_: Throwable) {
            // 失败后让计数失效，下次重新用 length() 校准（否则可能一直卡在触顶分支）。
            fileBytes = -1
        }
    }

    /**
     * 把文件裁到尾部约 [TRIM_KEEP_BYTES]，并在头部留下 [TRIM_MARK]。
     *
     * 按**行**从尾部向前累加字节数，因此不会把一行/一个多字节字符切成半个
     * （直接按字节 seek 截断在 UTF-8 中文上会产生乱码尾巴）。
     */
    private fun trimToTail(f: File) {
        try {
            val lines = f.readText(StandardCharsets.UTF_8).split('\n')
            var total = 0
            var start = lines.size
            for (i in lines.indices.reversed()) {
                val size = lines[i].toByteArray(StandardCharsets.UTF_8).size + 1
                if (total + size > TRIM_KEEP_BYTES && total > 0) break
                total += size
                start = i
            }
            val kept = StringBuilder()
            kept.append(TRIM_MARK).append('\n')
            for (i in start until lines.size) {
                kept.append(lines[i])
                if (i != lines.size - 1) kept.append('\n')
            }
            f.writeText(kept.toString(), StandardCharsets.UTF_8)
            fileBytes = f.length()
        } catch (_: Throwable) {
            fileBytes = -1
        }
    }

    /**
     * 掩码（`15` §6.3 十三类字段 + `05` §10.4；裁定 E：明码授权后本函数仍保留，
     * 作为「白盒状态 / 诊断摘要」等场景的兜底；事件链路按裁定 E 走明码）。
     * 命中字段名（`k=...` / `k: ...` / JSON 形态）时值替换为 `***`。
     */
    private val SENSITIVE = Regex(
        "(?i)(token|signToken|encryToken|passWord|password|vin|autoPlate|watchImei|" +
            "requestSerial|uuid|X-Request-id|encryData|sign|objective|identifier|" +
            "phoneNumber|blueToothPassword|bluetoothMacAddress|commandPwd|dk)" +
        "(\\s*[=:]\\s*)(\"?)(?!\\*\\*)([^\"&\\s,}]{1,})(\"?)"
    )

    fun mask(s: String): String = SENSITIVE.replace(s) { m ->
        m.groupValues[1] + m.groupValues[2] + "***"
    }
}

package com.bydhooker.autostart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志 Tab（`18` §4.2）：① 最近一次判定 → ② 事件列表（两行式、最新在上）→ ③ 清空/导出。
 *
 * ## 数据源（2026-11-10 用户拍板：**放弃读取宿主端日志**）
 * 上一版靠 root `cat` 读官方 App 私有目录的 `events.log`，实测不可靠 ——
 * 设置页与宿主不同 UID，App 进程里的 `su` 处在受限 mount namespace，
 * 多形态试探时好时坏，读到的可能是**旧的/不完整**内容却被当成"当前日志"展示。
 * 现在本页只读**本进程** `events.log`（`LogRefresher.refresh()` → `EventLog.tail(50)`），
 * 数据 100% 来自用户眼前这个 App 自己写下的记录。
 *
 * 代价（如实记录）：宿主侧的判定事件（`UNLOCK` / `WINDOW_OPEN` / `GATE_HIT` / `SEND`…）
 * 不再出现在本页，因此上方「最近一次判定」卡通常不会出现（它的数据源就是这些事件）。
 * 需要看判定链路时用**导出**：导出会合并宿主端 + 模块端两份原始日志（见 [LogExporter]）。
 *
 * ## 导出（2026-11-10 用户需求）
 * 点击「导出」→ 后台合并两端日志 → 拉起系统文件管理器（SAF `CreateDocument`）
 * 由用户自己选保存位置。不再走宿主侧分享面板（旧 T05 方案，按钮长期禁用）。
 *
 * **2026-11-10 布局改造（用户指定）**：「日志页的卡片做成可滑动的列表，
 * 让按钮能始终显示在屏幕最底下」。
 *
 * 改造前：整个页面是**一个 LazyColumn**，判定卡 / 事件列表卡 / 按钮行都是它的 item
 * → 事件多时按钮被推到屏幕外，必须滚动到底才能看到。
 *
 * 改造后：**外层 Column**（判定卡固定在上 → 事件列表内部独立滚动 → 按钮行固定在底部）。
 *
 * **2026-11-10 第二次修复（v0.9.49，用户反馈「日志卡片无法继续向下滑动」）**：
 * 上一版把 `LazyColumn` 放在 **miuix `Card` 的 `Column` 里面**、并自建了一个
 * `MiuixScrollBehavior` 只挂在这页 —— 两处异常都是本页独有（首页/设置页的页根
 * 直接就是 `LazyColumn`，滚动正常）。实测证据（miuix 0.9.4 AAR 字节码）：
 *  - `Card` = `Box(squircleSurface) { Column(Modifier.padding(insideMargin)) { content } }`
 *    → 滚动容器被包在卡片的 `Column` 里，高度约束经两层中转后不再可靠地传递到
 *    `LazyColumn`（内容被视为已全部展开 → 无滚动余量，只在卡片边界被裁掉）；
 *  - `MiuixScrollBehavior()` 在本页**新建**一个实例，它的 `TopAppBarState` 没有
 *    任何 `TopAppBar` 消费（`heightOffsetLimit = 0`），白挂一条 nestedScroll 链；
 *    顶栏折叠本来由 `SettingsActivity.Root()` 的 `Box.nestedScroll(...)` 承担，
 *    子孙的滚动 delta 会自动冒泡上去（首页/设置页就是这么工作的，本页无需再挂）。
 *
 * 本次修复的结构（**滚动容器必须是 `Column` 的 weight 直接子项**）：
 *  `Column` → `LazyColumn(Modifier.weight(1f))`（weight 给的是**固定高度**约束，
 *  `LazyColumn` 必定可滚）→ 卡片底色/圆角由 `LazyColumn` 自己的
 *  `clip + background` 画（不再包 `Card`）→ 按钮行固定在 `Column` 末尾。
 *
 * 事件顺序 `entries.reversed().take(50)` 逐字不动（最新在上、最多 50 条）。
 */
@Composable
fun LogTab(topInset: Dp = 0.dp) {
    // 刷新由 SettingsActivity.Root() 常驻驱动（见 data/LogRefresher），
    // 本页只读 SettingsState.logEntries，切 Tab 不再中断数据流。
    val entries = SettingsState.logEntries
    val last = entries.lastOrNull {
        it.event == "GATE_HIT" || it.event == "WINDOW_OPEN" || it.event == "WINDOW_REFRESH"
    }
    Column(Modifier.fillMaxSize()) {
        // 2026-11-21：玻璃顶栏是**浮在内容之上**的（Scaffold topBar 不再占内容布局行），
        // 故本页最顶部补一个 topInset 占位，避免标题行被顶栏压住。
        // 本页整体是 Column（列表只有中间一段滚动），不存在「内容穿透顶栏」的诉求，
        // 所以用占位而非 contentPadding —— 与 HomeTab/SettingsTab 的 LazyColumn 处理方式不同属有意。
        Spacer(Modifier.height(topInset))
        // Step 2O（2026-11-10 用户真机截图后授权的大改）：
        // 无判定记录时**整块不渲染** —— 此前是「小标题 + 一张写『暂无记录』的空卡」，
        // 真机观感是一大片空白噪音。有记录时才出现标题行 + 状态卡。
        if (last != null) {
            // 顶部留白 12.dp（Step 2Q，与首页/设置页一致）—— miuix 大标题下方只有
            // `LargeTitleBottomPadding = 4.dp`，不补留白则首卡贴住大标题。
            Spacer(Modifier.height(12.dp))
            LogHeaderRow(text = "最近一次判定")
            Spacer(Modifier.height(12.dp))
            VerdictCard(last)
        }
        // 事件列表：**滚动容器是 `Column` 的 weight 直接子项**（见文件头第二次修复说明）。
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            BhCard {
                BhText(
                    "还没有事件", slot = BhTextStyle.Footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    textAlign = TextAlign.Center,
                )
            }
            // 空态没有滚动容器 → 用权重占位把按钮行顶到屏幕最底（与列表态位置一致）。
            Spacer(Modifier.weight(1f))
        } else {
            EventList(entries = entries, modifier = Modifier.weight(1f))
        }
        // 按钮行：**固定在屏幕最底**，不随内容滚动。
        Spacer(Modifier.height(12.dp))
        LogActions()
        // 【2026-11-21 修正】底部避让从 84 → **110.dp**。
        //
        // 原因：底栏从 `GlassNavigationBar`(54dp) 换成官方 iOS-like 的
        // `IosLiquidGlassNavigationBar`(64dp 胶囊 + 4dp 内边距 + floatingMargin 18~26dp
        // + 系统手势条 insets)，**实际占位约 94~100dp**，84dp 仍会压住按钮下沿一小截
        // （用户真机反馈「还是被遮住了一部分」）。
        //
        // 本页是 Column 固定布局（列表 weight + 按钮行贴底），没有 contentPadding 可叠加，
        // 所以这里必须一次给足：110dp = 胶囊 64 + 内边距 8 + 悬浮边距 ~26 + 呼吸 ~12。
        Spacer(Modifier.height(110.dp))
    }
}

private fun fmtTs(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(epochMs))

/** 卡头：区块名 + 右上「只读」（`18` §4.1/§4.2）。
 *  Step 2C 问题 3：标题走 `BhSubTitle` 统一左基准；「只读」徽标右对齐到同一 16dp 边。 */
@Composable
private fun LogHeaderRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BhSubTitle(text = text, topPadding = false)
        ReadOnlyBadge()
    }
}

/** ② 事件列表（Step 2O 大改：自绘两行式 `EventRow` —— 级别色点 + 事件名 + 右对齐时间 + 截断摘要）。
 *  `entries.reversed().take(50)` 顺序逐字不动（最新在上、最多 50 条）；
 *  仅 WARN/ERROR 着色，INFO/DEBUG 用中性灰。
 *
 *  **2026-11-10 第二次修复**：这里**不再包 `Card`**，`LazyColumn` 直接吃掉调用方给的
 *  `weight(1f)`（`Column` 的 weight 会给**固定高度**约束），卡片观感由自己的
 *  `clip + background` 画（16dp 圆角 + `surfaceContainer`，与 `BhCard` 同参数同色）。
 *  理由见文件头「第二次修复」：`Card` 内部的 `Column` 会让滚动容器拿不到可靠的高度约束，
 *  表现为「内容全部展开 → 无滚动余量 → 在卡片边界被裁掉」。
 *
 *  条目 key 用 `ts` + `event` + 索引（同一毫秒可能有多条同事件 → 需索引去重）。 */
@Composable
private fun EventList(
    entries: List<com.bydhooker.autostart.model.LogEntry>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // 卡外 16dp（与 `BhCard` 的 `padding(horizontal = 16.dp)` 对齐）
            .padding(horizontal = 16.dp)
            // 卡面：16dp 圆角 + `surfaceContainer`（`CardDefaults.defaultColors()` 同色）
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer),
        // 首末条与卡片上下沿各留 6.dp 呼吸（行内已有 9.dp 竖向内边距）
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(
            count = minOf(entries.size, 50),
            // key 必须稳定且唯一：同一毫秒可能有多条同事件 → 加索引。
            key = { i ->
                val e = entries[entries.size - 1 - i]
                "${e.ts}_${e.event}_$i"
            },
        ) { i ->
            // 索引 i 从 0 起表示**最新**（reversed 后的顺序）。
            EventRow(entries[entries.size - 1 - i])
        }
    }
}

/**
 * ① 最近一次判定 —— 状态卡（Step 2O 大改）。
 *
 * 与事件列表同一套编排：**时间戳与事件名同一行右对齐**、摘要 `maxLines = 2` 截断、
 * 语义图标与标题**顶部对齐**。标题用中文映射（判定卡是"给人看"的，
 * 原始事件名仍完整保留在下方的事件日志列表里供排障）。
 */
@Composable
private fun VerdictCard(last: com.bydhooker.autostart.model.LogEntry) {
    BhCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VerdictIcon(last.event, last.level)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                BhText(
                    text = verdictTitle(last.event), slot = BhTextStyle.Headline1, bold = true,
                    color = MiuixTheme.colorScheme.onSurface, maxLines = 1,
                )
                BhText(
                    text = last.summary, slot = BhTextStyle.Body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    maxLines = 2,
                )
            }
            BhText(
                text = fmtTs(last.ts), slot = BhTextStyle.Footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1,
            )
        }
    }
}

/** 判定事件的中文标题（未识别的原样显示原始事件名）。 */
private fun verdictTitle(event: String): String = when (event) {
    "GATE_HIT" -> "判定命中"
    "WINDOW_OPEN" -> "窗口开启"
    "WINDOW_REFRESH" -> "窗口刷新"
    else -> event
}

/**
 * 事件行（Step 2O 大改）：左侧 7dp 级别色点（顶对齐）→ 事件名 + 右对齐时间戳（同一行）
 * → 摘要（`maxLines = 2`；`BhText` 已固定 `TextOverflow.Ellipsis`，无需额外处理）。
 *
 * 级别表达由「文字徽标」改为「颜色点」：WARN 琥珀 / ERROR 红 / INFO 中性灰 ——
 * 密排列表里颜色足够辨识，且省下 44dp 横向空间。
 */
@Composable
private fun EventRow(e: com.bydhooker.autostart.model.LogEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(levelColor(e.level)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BhText(
                    text = e.event, slot = BhTextStyle.Body1,
                    color = levelTextColor(e.level),
                    // weight(1f)：事件名文本框占满剩余宽度（文字本身仍左对齐），
                    // 右侧时间戳因此被推到**行末**（此前用 weight(1f, fill=false)
                    // 只按内容绘制，时间戳会紧贴在事件名后面 —— 真机截图实证）。
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                BhText(
                    text = fmtTs(e.ts), slot = BhTextStyle.Footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1,
                )
            }
            BhText(
                text = e.summary, slot = BhTextStyle.Footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                maxLines = 2,
            )
        }
    }
}

/**
 * 判定卡图标槽（Step 2N）：官方矢量 + 语义色圆形底。
 *
 * **图标按事件语义选**（`last` 只可能是这三个之一，见 `LogTab` 的 `lastOrNull` 过滤）：
 *  - `GATE_HIT` → `Ok`（已执行判定）
 *  - `WINDOW_OPEN` → `Timer`（窗口开启）
 *  - `WINDOW_REFRESH` → `Refresh`（窗口刷新）
 * **颜色按 level**（ERROR 红 / WARN 黄 / 其余绿），不猜业务成败——
 * 判定「未上电」也是 INFO 级，颜色不代表成功与否，只代表日志级别。
 */
@Composable
private fun VerdictIcon(event: String, level: String) {
    val icon = when (event) {
        "GATE_HIT" -> MiuixIcons.Ok
        "WINDOW_OPEN" -> MiuixIcons.Timer
        "WINDOW_REFRESH" -> MiuixIcons.Refresh
        else -> MiuixIcons.Info
    }
    val tint = when (level) {
        "ERROR" -> BhColors.error(isDarkMode())
        "WARN" -> BhColors.warn(isDarkMode())
        else -> BhColors.ok(isDarkMode())
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}

/** 事件标题色：仅 WARN/ERROR 用级别色，其余中性（Step 2O：改为返回纯 `Color` 供自绘行使用；
 *  此前的 `BasicComponentDefaults.titleColor(...)` 包装随官方组件一起退役）。 */
@Composable
private fun levelTextColor(level: String): Color = when (level) {
    "WARN" -> BhColors.warn(isDarkMode())
    "ERROR" -> BhColors.error(isDarkMode())
    else -> MiuixTheme.colorScheme.onSurface
}

/**
 * ③ 清空 / 导出（导出 = 宿主分享面板 T05 → 禁用；清空经 log.clearTs 通道）。
 *
 * **2026-11-10 改造**：改用 **miuix 官方 `Button`（实底 + 图标）** —— 用户要求
 * 「按钮做成符合 miuix 规范」。此前用自绘 `BhTextButton`（官方 `TextButton` 的透明底变体），
 * 页面最底部没有实体按钮的视觉锚点，两个按钮并排时都像纯文字、看不出是按钮。
 *
 * 现在的形态（均为官方 `Button`）：
 *  - **清空** = 次级底色（`secondaryVariant`）+ **错误色文字与图标**（`error`）——
 *    危险语义由**内容色**表达，底色仍是中性 → 符合 `18` §3.5「危险按钮不用红实底」的裁定，
 *    同时有了官方实底按钮的实体感；
 *  - **导出** = 同款次级底色 + 禁用态（功能未落地，T05）。
 *
 * 官方 `Button` 的 content 是 `RowScope.() -> Unit`（需自放文字与图标），
 * 文字用官方 button 字阶（`BhTextStyle.Button` = 17sp）。
 */
@Composable
private fun LogActions() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 清空 —— 危险操作：中性底 + 红色内容（图标 + 文字）。
        Button(
            onClick = {
                // 清本进程 events.log（授权流事件）；宿主侧经导出/重装管理
                com.bydhooker.autostart.core.EventLog.clear()
                SettingsState.logEntries = emptyList()
            },
            enabled = true,
            modifier = Modifier.weight(1f),
            cornerRadius = 16.dp,
            minWidth = 0.dp,
            minHeight = 48.dp,
            colors = ButtonDefaults.buttonColors(
                contentColor = MiuixTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.error,
            )
            BhText(
                text = "清空",
                slot = BhTextStyle.Button,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // 导出（2026-11-10 用户需求）：合并宿主端 + 模块端日志 → 系统文件管理器选位置。
        // 可用性取决于 `SettingsState.exportLogs` 是否已由 SettingsActivity 注入；
        // 导出进行中置灰，避免重入 launch（会抛 IllegalStateException）。
        val canExport = SettingsState.exportLogs != null && !SettingsState.exporting
        val exportTint = if (canExport) {
            MiuixTheme.colorScheme.onSurface
        } else {
            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        Button(
            onClick = {
                val trigger = SettingsState.exportLogs ?: return@Button
                if (SettingsState.exporting) return@Button
                SettingsState.exporting = true
                try {
                    trigger()
                } catch (_: Throwable) {
                    // launcher 已被占用 / Activity 已销毁 → 复位，保持按钮可用
                    SettingsState.exporting = false
                }
            },
            enabled = canExport,
            modifier = Modifier.weight(1f),
            cornerRadius = 16.dp,
            minWidth = 0.dp,
            minHeight = 48.dp,
            colors = ButtonDefaults.buttonColors(
                contentColor = MiuixTheme.colorScheme.onSurface,
                disabledContentColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        ) {
            Icon(
                imageVector = MiuixIcons.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = exportTint,
            )
            BhText(
                text = if (SettingsState.exporting) "导出中…" else "导出",
                slot = BhTextStyle.Button,
                color = exportTint,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 事件级别色点（§7.2：WARN ● / ERROR ■；INFO/DEBUG 不绘点）——T05 列表通道接入后复用。 */
@Composable
fun LevelDot(level: String) {
    when (level) {
        "WARN" -> StatusDot(dotWarn = true)
        "ERROR" -> StatusDot(dotError = true)
        else -> Box(Modifier.size(8.dp))  // 色点列留空
    }
}

/** 级别文本颜色（§7.2：WARN 黄 / ERROR 红 / 其余中性）——T05 复用。 */
@Composable
fun levelColor(level: String): Color = when (level) {
    "WARN" -> BhColors.warn(isDarkMode())
    "ERROR" -> BhColors.error(isDarkMode())
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

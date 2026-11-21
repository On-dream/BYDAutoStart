package com.bydhooker.autostart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用小组件（Step 2B：行一律用 miuix 0.9.4 公共组件；仅 NoticeBanner / StatusDot 等
 * 无对应公共组件者保留布局原语，按 skill「公共组件不存在时才用布局原语」）。
 */

/**
 * 深浅判定（跟随系统；§3.6 结构守恒）。
 *
 * Step 2C 问题 2 修正：此前读 `LocalContext.current.resources.configuration.uiMode`，
 * 与 miuix 主题**不同源** —— miuix 的 `ThemeController.currentColors()` 在
 * `ColorSchemeMode.System` 分支调用的是 `DarkThemeKt.isSystemInDarkTheme()`
 * （AAR 字节码实证），它读的是 Compose 的 `LocalConfiguration.current.uiMode`。
 * `LocalContext.resources` 不是 Compose 状态源：系统切换深浅色时不会驱动重组，
 * 且可能拿到与当前 UI 配置不一致的 Resources → 与 MiuixTheme 不同步
 * （真机实证：用户系统为深色模式，页面底色却按浅色取值）。
 *
 * 现改用 `androidx.compose.foundation.isSystemInDarkTheme()`：**与 miuix 内部调用的是
 * 同一个函数、同一数据源**，深浅判定必然一致，且随系统深浅色自动重组。
 * （注：若将来把 ThemeController 的 isDark 显式指定为非 null，miuix 会优先用该值，
 * 此时本函数与主题会再次分叉 —— 当前工程用的是 `ThemeController(ColorSchemeMode.System)`，
 * isDark 默认为 null（字节码实证），走 isSystemInDarkTheme 分支。）
 */
@Composable
fun isDarkMode(): Boolean = isSystemInDarkTheme()

/** 页面级条件性提示条（`18` §2.2：surface-2 底、8dp 圆角、8/12dp 内边距；不可关闭）。
 *  Step 2B 保留自绘：0.9.4 无对应公共组件。 */
@Composable
fun NoticeBanner(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BhColors.surface2(isDarkMode()))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BhText(lines.first(), slot = BhTextStyle.Footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (lines.size > 1) {
            BhText(
                lines[1], slot = BhTextStyle.Footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 区块标签（§3.4：C 级 13/500 变体、text-2、上 16 下 8 左 16）。
 *  Step 2C 问题 3：改走 `BhSubTitle` 统一封装，保证与卡内小标题同一左基准。 */
@Composable
fun SectionLabel(text: String, topPadding: Boolean = true) {
    BhSubTitle(text = text, topPadding = topPadding)
}

/** 8dp 状态色点（§3.2：色 + 形；固定栅格列）。
 *  dotOk=null 且非 warn/error → 色点列**留空**（U-1：当前模式 / 最近事件行）。 */
@Composable
fun StatusDot(dotOk: Boolean? = null, dotWarn: Boolean = false, dotError: Boolean = false) {
    val dark = isDarkMode()
    if (dotOk == null && !dotWarn && !dotError) {
        Box(Modifier.size(8.dp))
        return
    }
    val (color, square, hollow) = when {
        dotError -> Triple(BhColors.error(dark), true, false)
        dotWarn -> Triple(BhColors.warn(dark), false, false)
        dotOk == true -> Triple(BhColors.ok(dark), false, false)
        else -> Triple(BhColors.unknown(dark), false, true)
    }
    if (hollow) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .border(1.dp, color, CircleShape),
        )
        return
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(if (square) RoundedCornerShape(1.dp) else CircleShape)
            .background(color),
    )
}

/**
 * 2026-11-10（Step 2N）：原 `KeyValueRow(label, value)` 已删除 —— 设置页诊断卡改用
 * `SettingsTab.IconValueRow`（官方 `BasicComponent` + `startAction` 语义图标槽），
 * 与日志页两张卡共用同一套行语义；该 helper 随之零调用点，故不保留死代码。
 */

/**
 * 页面画布底色（Step 2C 问题 2）。
 *
 * 官方层级实证（miuix 0.9.4 源码 commit 39c40f9）：miuix 自己的 `Scaffold` 默认
 * `containerColor = MiuixTheme.colorScheme.surface`，而 `CardDefaults.defaultColors()`
 * = `surfaceContainer` + `onSurfaceContainer`。两者配合才是官方层级：
 *   浅色：页面 surface #F7F7F7，卡片 surfaceContainer #FFFFFF → 卡片比底更亮一档
 *   深色：页面 surface #000000，卡片 surfaceContainer #242424 → 卡片比底更亮一档
 * 而本工程原先用 `background` 当页面底：
 *   浅色：页面 #FFFFFF，卡片若也用 surfaceContainer #FFFFFF → 两者同色、卡片完全看不见
 *   深色：页面 #242424，卡片 #242424 → 同样同色
 * （miuix 0.9.4 的 Card 用 `squircleSurface` 实心填充，**不画阴影/渐变/描边** —— 官方
 * Colors.kt + SquircleBackground.kt 双向实证 —— 所以卡片与页面同色就没有任何区分手段。）
 * 故页面底色改用 `surface`，与官方 Scaffold 一致，卡片层级方向才正确。
 */
@Composable
fun pageBackgroundColor(): Color = MiuixTheme.colorScheme.surface

/** 区块小标题统一封装（Step 2C 问题 3：所有小标题左缩进必须落在同一条竖线；
 *  Step 2E B1：改走官方 `subtitle` 字阶，字重按项目约定压到 500）。
 *
 * 此前两种写法基准不同：卡外 `SectionLabel` 用 `SmallTitle` + `padding(start=16.dp)`，
 * 而卡内标题行（首页「状态」、日志页「最近一次判定」）把 `SmallTitle` 直接放进
 * `Row`（无 16dp 左缩进），于是「自动上电 / 状态 / 参数 / 最近一次判定」等标题的
 * 左边缘参差不齐。现统一走本封装，文字左边缘一律落在 16dp 竖线上。
 *
 * **B1 换掉 `SmallTitle` 组件的理由**（官方 0.9.4 源码实证）：
 * - `SmallTitle` 的样式**硬编码**为 `textStyles.subtitle`，且**没有 `textStyle` 参数** →
 *   无法改字重；`subtitle` 槽官方带 **Bold(700)**，**超出** `18` §3.4「字重仅 400/500」。
 * - 改用 miuix 公共 `Text` + `MiuixTheme.textStyles.subtitle.copy(fontWeight = 500)`，
 *   既走官方字阶（约定一），又严守项目字重约定。
 * - 原来靠 `SmallTitleDefaults.InsideMargin = PaddingValues(28.dp, 8.dp)` 抵消 28dp 内缩，
 *   现在没有这层内缩 → 外层 `padding(start = 16.dp)` 即为最终左边缘，基准更直接。
 */
@Composable
fun BhSubTitle(
    text: String,
    modifier: Modifier = Modifier,
    topPadding: Boolean = true,
) {
    BhText(
        text = text,
        modifier = modifier.padding(start = 16.dp, top = if (topPadding) 16.dp else 0.dp),
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        slot = BhTextStyle.Subtitle,
        bold = true,
    )
}

/** 分组卡（surface、16dp 圆角、无阴影；§3.5）。
 *
 * Step 2C 问题 2 修正：**不再显式传 `color = colorScheme.surface`**。
 * miuix 0.9.4 的 `CardDefaults.defaultColors()` 默认即
 * `surfaceContainer` + `onSurfaceContainer`（AAR 字节码 + 官方 Colors.kt 双向实证），
 * 而 `surface` 深色是纯黑、浅色是 #F7F7F7 —— 显式传 surface 覆盖了 miuix 默认，
 * 造成「卡片比页面底色更暗 / 更亮」的反置观感（用户原话：颜色刚好反了）。
 * 现完全交回 miuix 默认，卡片层级由官方语义决定。
 *
 * 【2026-11-21 用户拍板：**卡片保持实心，不做玻璃**】
 * 背景：本轮把全局玻璃落在浮层（`GlassTopAppBar` / `GlassNavigationBar` /
 * `GlassDialog` / `GlassDropdownPreference`）。问过是否把卡片也玻璃化，用户明确选
 * 「卡片保持实心」。原因（已向用户说明的技术约束，记在这里避免后人再改）：
 *  · 卡片在**滚动列表里**，而官方 `docs/guide/glass.md` §Background Sampling 明确要求
 *    「录制区里不能含玻璃面本身」—— 卡片既在录制区里又要采样录制结果，就是自己采样自己
 *    → 发灰/拖影；
 *  · 每张卡一个 RuntimeShader，滚动时开销明显。
 * 所以**不要再给 BhCard 加 `Modifier.glassPanel`**；要玻璃观感请加在浮层上。
 */
@Composable
fun BhCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cornerRadius = 16.dp,
        insideMargin = CardDefaults.InsideMargin,
        colors = CardDefaults.defaultColors(),
        content = content,
    )
}

/** 卡内 0.5dp hairline（左右缩进 16dp；末行不画由调用方控制；§3.4）。 */
@Composable
fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.dividerLine,
    )
}

/** 卡内行右侧「只读」徽标（`18` §4.1/§4.2 右上角）。
 *  Step 2B：自绘 BhText → 0.9.4 原生 `Badge`；默认容器色是 error 红底 / onError 内容，
 *  故显式传中性 `surfaceContainerHighest` + `onSurfaceVariantSummary`，避免做成错误态。 */
@Composable
fun ReadOnlyBadge() {
    val neutral = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Badge(
        containerColor = MiuixTheme.colorScheme.surfaceContainerHighest,
        contentColor = neutral,
    ) {
        BhText("只读", slot = BhTextStyle.Footnote2, color = neutral)
    }
}

/** 禁用态整卡 alpha（§3.5：浅 0.38 / 深 0.30）。
 *  Step 2B 起不再调用：标准 `enabled=false` 自带 disabled 语义，
 *  叠加手工 alpha 违反 skill design-language.md:35。保留定义以免影响既有引用。 */
@Composable
fun disabledCardAlpha(): Float = if (isDarkMode()) 0.30f else 0.38f

/** 中性灰空心环（禁用态色点；§3.5）。 */
@Composable
fun DisabledDot() {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .border(
                1.dp,
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f),
                CircleShape,
            ),
    )
}

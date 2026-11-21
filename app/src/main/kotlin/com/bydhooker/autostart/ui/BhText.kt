package com.bydhooker.autostart.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix `Text` 薄封装（收敛**字阶槽位**与**字重口径**）。
 *
 * **2026-11-10 更正（本轮实证推翻旧注释）**：miuix 0.9.4 官方 `Text.kt` **有 Kotlin 默认参数**
 * ——pinned 源码 `39c40f99` 里第一个 `Text(String, …)` 重载共 18 参、**17 个带默认值**；
 * 本工程 `AuthHeroCard.kt` 已用简写形式（只传 `text` / `fontSize` / `fontWeight`）编译通过。
 * 此前这里记的「无默认参数、每次须显式传全 18 参」是当时只 `javap` 了合成类导致的误判，
 * **已作废**。本封装自身仍显式传全参，是因为要在封装层统一注入 `style` / `color` / `autoSize`，
 * 与官方是否提供默认值无关。
 *
 * 字重口径：全站仅 400 / 500 两档（`18` §3.4）。**例外**：`AuthHeroCard.kt` 1:1 照抄
 * KernelSU `StatusCard`，其大标题为 `22sp / SemiBold(600)`，属用户明确指定的照抄项，
 * 不套用本封装。除该例外，miuix 官方字阶里只有 `subtitle`（Bold）与 `BasicComponent`
 * 标题（Medium）两处非 Normal，层级靠**字号**拉开（32/20/17/14/13/11）。
 *
 * foundation 1.12 的 `TextAutoSize` 无 `Off`（Companion 仅 `StepBased`，javap 实证），
 * 以 `min==max` 等效关闭自动缩放。
 */

/**
 * 官方字阶槽位（`23` §15.3）。
 *
 * 数值来自 miuix 0.9.4 官方 pinned 源码 `39c40f99` `TextStyles.kt` 的 `defaultTextStyles()`：
 * title1=32 / title2=24 / title3=20 / title4=18 / headline1=17 / headline2=16 /
 * subtitle=14(Bold) / body1=16 / body2=14 / main=17 / paragraph=17 / button=17 /
 * footnote1=13 / footnote2=11。
 */
enum class BhTextStyle {
    /** 页面主文本（正文基准，官方 `main` 17sp）。 */
    Main,

    /** 大标题（官方 `title2` 24sp；状态卡主标题用）。 */
    Title2,

    /** 中标题（官方 `title3` 20sp；`TopAppBar` 折叠标题同槽）。 */
    Title3,

    /** 正文正文（官方 `body1` 16sp）。 */
    Body1,

    /** 辅助说明（官方 `body2` 14sp；亦为 `BasicComponent` 摘要用槽）。 */
    Body2,

    /** 分组小标题（官方 `subtitle` 14sp，**字重按项目约定压到 500**）。 */
    Subtitle,

    /** 卡内行标题（官方 `headline1` 17sp；官方字重 Medium=500，与项目约定一致）。 */
    Headline1,

    /** 数值 / 辅助说明（官方 `footnote1` 13sp）。 */
    Footnote1,

    /** 极小注记（官方 `footnote2` 11sp）。 */
    Footnote2,

    /** 按钮标签（官方 `button` 17sp）。 */
    Button,
}

/**
 * 取官方字阶的 `TextStyle`（按项目约定把非 Normal 的槽位统一压到 500）。
 *
 * @param slot 官方字阶槽位
 * @param bold 官方槽位是否带粗体（仅 `subtitle` 为 Bold）；为 true 时按 `18` §3.4 压到 500
 */
@Composable
fun bhTextStyle(slot: BhTextStyle, bold: Boolean = false): TextStyle {
    val styles = MiuixTheme.textStyles
    val base = when (slot) {
        BhTextStyle.Main -> styles.main
        BhTextStyle.Title2 -> styles.title2
        BhTextStyle.Title3 -> styles.title3
        BhTextStyle.Body1 -> styles.body1
        BhTextStyle.Body2 -> styles.body2
        BhTextStyle.Subtitle -> styles.subtitle
        BhTextStyle.Headline1 -> styles.headline1
        BhTextStyle.Footnote1 -> styles.footnote1
        BhTextStyle.Footnote2 -> styles.footnote2
        BhTextStyle.Button -> styles.button
    }
    // 全站仅 400 / 500 两档：官方 Bold(700) 一律压到 Medium(500)。
    return if (bold) base.copy(fontWeight = FontWeight(500)) else base
}

/**
 * 文本。
 *
 * @param slot 官方字阶槽位（走 miuix `MiuixTheme.textStyles`，不再硬编码 sp）
 * @param fontSizeSp 旧接口：显式给定时覆盖 `slot` 的字号（仅供极少数需要精确 sp 的场景）
 */
@Composable
fun BhText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
    slot: BhTextStyle = BhTextStyle.Body2,
    bold: Boolean = false,
    fontSizeSp: Int? = null,
    lineHeightSp: Int? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = bhTextStyle(slot, bold)
    val size = fontSizeSp?.sp ?: style.fontSize
    val lineHeight = lineHeightSp?.sp ?: style.lineHeight
    Text(
        text = text,
        modifier = modifier,
        color = color,
        autoSize = TextAutoSize.StepBased(minFontSize = size, maxFontSize = size),
        fontSize = size,
        fontStyle = null,
        fontWeight = style.fontWeight,
        fontFamily = null,
        letterSpacing = style.letterSpacing,
        textDecoration = null,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        maxLines = maxLines,
        minLines = 1,
        onTextLayout = null,
        style = style,
    )
}

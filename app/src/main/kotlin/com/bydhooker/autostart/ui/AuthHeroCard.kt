/*
 * 官方图标矢量数据逐字取自 AOSP（Apache-2.0）：
 *   androidx.compose.material:material-icons-extended:1.7.8
 *   commonMain/androidx/compose/material/icons/rounded/CheckCircleOutline.kt
 *   commonMain/androidx/compose/material/icons/rounded/ErrorOutline.kt
 * 分别是 KernelSU HomeMiuix.kt 中「工作中」态的 Icons.Rounded.CheckCircleOutline
 * 与「未安装 / 不支持」态的 Icons.Rounded.ErrorOutline 的精确 pathData。
 * 内联而非引入 material-icons-extended（该产物 12 MB、含数千图标），零新增依赖。
 */
package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 授权状态大卡 —— **1:1 照抄 KernelSU 官方 `HomeMiuix.kt` 的 `StatusCard`（working 分支）**。
 *
 * 源码证据（2026-11-10 取，tiann/KernelSU main）：
 *   `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt:244-368`
 *   该文件本身已使用 miuix 0.9.4（与本项目同版本），故可逐字移植。
 *
 * **2026-11-10 追加（用户拍板）**：已授权态维持绿色「工作中」形态**完全不变**；
 * **未授权态的颜色 / 图案对标 KernelSU 的红色错误态**，形态/字号/间距/偏移两态相同。
 *
 * ### 红态取证（KernelSU 官方同仓库源码，非本项目自创色）
 * **先纠正一个常见印象**：绿色大卡所在的 `HomeMiuix.kt` **没有红色大卡**；
 * 它的「未安装 / 不支持」态只是普通 `Card` + `BasicComponent` + `Icons.Rounded.ErrorOutline`。
 * KernelSU 仓库里真正「红色背景」的有三处，本实现取其中**结构最对称**的一组：
 *
 * | 来源 | 红色背景 | 图标 |
 * |---|---|---|
 * | `ui/component/miuix/WarningCard.kt`（`WarningLevel.Error`）**← 取色** | 动态色 `errorContainer` / 深 `#310808` / 浅 `#F8E2E2`；内容色 `#F72727` | 无（纯文字警告条） |
 * | `ui/screen/home/HomeMiuix.kt:384-426`（未安装 / 不支持）**← 取图标** | 无红色，普通卡片 | `Icons.Rounded.ErrorOutline` |
 * | `ui/screen/home/HomeMaterial.kt:206-213`（Material 版旧实现） | `MaterialTheme.colorScheme.errorContainer` | `Warning` / `Block` |
 *
 * 选 `ErrorOutline` 而非 `Warning` / `Block` / `MoodBad` 的理由：与绿态 `CheckCircleOutline`
 * **同为 24dp 圆形描边图标**，放大到 110dp 后形状对称，且是 `HomeMiuix.kt` 未安装态的官方图标。
 *
 * **有意偏离**：`WarningCard` 会显式把 `contentColor` 设成亮红 `#F72727`（整卡文字染红）；
 * 本实现**不传 contentColor**，文字仍走 Card 默认深色 —— 与绿态一致、可读性更好，
 * 红色信号由「背景 + 110dp 大图标」承担。
 *
 * **照抄项**（用户要求「包括字体/间距/颜色配置等等」）：
 *
 * | 项 | KernelSU 原值 |
 * |---|---|
 * | 外层 | `Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), CenterVertically)` |
 * | Card 底色（绿 / 已授权） | 动态色 → `colorScheme.secondaryContainer`；深 → `#1A3825`；浅 → `#DFFAE4` |
 * | Card 底色（红 / 未授权） | 动态色 → `colorScheme.errorContainer`；深 → `#310808`；浅 → `#F8E2E2`（`WarningCard` Error 级） |
 * | Card 交互 | `pressFeedbackType = Tilt`、`showIndication = true`，默认圆角 / 默认内边距 |
 * | 右下大图标 | `110.dp`、`offset(27.dp, 31.dp)`、`BottomEnd`；绿 `CheckCircleOutline` / 红 `ErrorOutline` |
 * | 图标 tint | 绿：动态色 `primary.copy(0.8f)` 或 `#36D167`；红：动态色 `error.copy(0.8f)` 或 `#F72727` |
 * | 大标题 | `22.sp` / `FontWeight.SemiBold` |
 * | 中间行 | `15.sp`，`Modifier.weight(1f, fill = false)` |
 * | 最下面一行 | `16.sp` / `FontWeight.Medium`，`BottomStart` |
 * | 垂直间距 | 标题与中间行之间 `Spacer(1.dp)` |
 * | 内层 padding | 顶部文字 `padding(16.dp, 14.dp)` / `TopStart`；底部文字 `padding(16.dp, 10.dp)` / `BottomStart` |
 * | 文字颜色 | 不传 `color` → 走 `LocalContentColor`（由 Card 提供） |
 *
 * **与 KernelSU 原版的三处不同**（用户指定）：
 *  1. 三行文本改成授权数据；2. `onClick` 改成打开本模块授权弹窗（沿用既有点击逻辑，零业务改动）；
 *  3. 未授权态按用户追加指令换成红态配色与 `ErrorOutline` 图标（KernelSU 该分支本就单态）。
 *
 * | 行 | 未授权 | 已授权 |
 * |---|---|---|
 * | 22.sp 标题 | `未授权` | `已授权` |
 * | 15.sp 中间行 | `点击卡片授权` | **车型**（`credAutoType`，空值 `—`） |
 * | 16.sp 底部行 | `版本 0.9.32` | **车牌明码 + 授权时间**（`粤Bxxxxx · yyyy-MM-dd HH:mm`） |
 *
 * **唯一非照抄项（页面级）**：外层保留本项目 `padding(horizontal = 16.dp)`，
 * 因为本页其他卡片都是 16dp 外边距，而 KernelSU 是在 LazyColumn 上统一 `padding(horizontal = 12.dp)`。
 * 卡片**内部**的所有间距配置均逐字照抄。
 *
 * **隐私裁定（本次用户明确拍板）**：车牌「不要掩码」。项目 `MEMORY.md` 原记
 * 「UI 展示层仍按 `18` §5 掩码，是否去掩码待用户另行明确」——本次即为该「另行明确」，
 * 故 UI 层车牌改为明码；**仅限本地显示**，仍禁止上传网盘 / 分享 evidence / 提交公开仓库。
 * 代码与文档均不内嵌真实车牌值（运行时才从凭据读取），截图受 `.gitignore` 排除。
 *
 * **已知偏离项目旧约定**（用户「照抄字体」指令优先）：
 *  - `22.sp SemiBold(600)` 超出 `18` §3.4「字重仅 400/500 两档」；
 *  - 不走 `BhText` 的官方字阶槽位，改用 miuix `Text` 精确指定 22/15/16sp。
 */
@Composable
fun AuthStatusHeroCard(
    authorized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 三行文本（唯一与 KernelSU 不同的内容项）
    val titleText = if (authorized) "已授权" else "未授权"
    val middleText = if (authorized) {
        SettingsState.credAutoType.ifEmpty { "—" }
    } else {
        "点击卡片授权"
    }
    val bottomText = if (authorized) {
        val plate = SettingsState.credAutoPlate.ifEmpty { "—" }   // 明码，不掩码
        val time = if (SettingsState.credAcquireTs > 0) fmtAuthTime(SettingsState.credAcquireTs) else "—"
        "$plate · $time"
    } else {
        moduleVersionLabel()
    }

    // 绿态逐字照抄 HomeMiuix.kt:274-302；红态对标同仓库 WarningCard.kt 的 WarningLevel.Error。
    // 已授权分支的颜色/图标/tint 与上一版逐字相同，未授权分支才走红色。
    val cardColor = if (MiuixTheme.isDynamicColor) {
        if (authorized) MiuixTheme.colorScheme.secondaryContainer
        else MiuixTheme.colorScheme.errorContainer
    } else if (isDarkMode()) {
        if (authorized) Color(0xFF1A3825) else Color(0xFF310808)
    } else {
        if (authorized) Color(0xFFDFFAE4) else Color(0xFFF8E2E2)
    }
    val iconTint = if (MiuixTheme.isDynamicColor) {
        if (authorized) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        else MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
    } else {
        if (authorized) Color(0xFF36D167) else Color(0xFFF72727)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 页面级外边距：与首页其他卡片保持一致（KernelSU 是在 LazyColumn 统一给 12dp）。
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardColor),
            onClick = onClick,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                // 右下角超大圆环勾：偏移出卡片边缘（KernelSU 原样）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = if (authorized) CheckCircleOutline else ErrorOutline,
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                // 最下面一行（KernelSU 的 workingMode / "LKM" 位）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 10.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(
                        text = bottomText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                // 标题 + 中间行（KernelSU 的 workingText / 版本行）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = titleText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = middleText,
                                modifier = Modifier.weight(1f, fill = false),
                                fontSize = 15.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * `Icons.Rounded.CheckCircleOutline` 的精确矢量（AOSP Apache-2.0，逐字复刻 pathData）。
 *
 * 等价展开：
 *  `materialIcon(name = "Rounded.CheckCircleOutline") { materialPath { ... } }`
 *  - `materialIcon` → `ImageVector.Builder(24.dp, 24.dp, viewport 24×24)`
 *  - `materialPath` → `path(fill = SolidColor(Color.Black), fillAlpha = 1f, pathFillType = NonZero)`
 *  绘制时由 miuix `Icon` 的 `tint` 覆盖填充色，故 `Color.Black` 仅为占位。
 */
private val CheckCircleOutline: ImageVector by lazy {
    ImageVector.Builder(
        name = "Rounded.CheckCircleOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
            close()
            moveTo(15.88f, 8.29f)
            lineTo(10.0f, 14.17f)
            lineToRelative(-1.88f, -1.88f)
            curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
            curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
            lineToRelative(2.59f, 2.59f)
            curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
            lineTo(17.3f, 9.7f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
            curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.42f, 0.0f)
            close()
        }
    }.build()
}

/**
 * `Icons.Rounded.ErrorOutline` 的精确矢量（AOSP Apache-2.0，逐字复刻 pathData）。
 * 红态（未授权）使用 —— 即 `HomeMiuix.kt:388/423` 未安装态所用的官方图标，
 * 与 `CheckCircleOutline` 同为「圆形描边 + 内部符号」，放大到 110dp 后形状对称。
 */
private val ErrorOutline: ImageVector by lazy {
    ImageVector.Builder(
        name = "Rounded.ErrorOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.0f, 7.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
            verticalLineToRelative(4.0f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            lineTo(11.0f, 8.0f)
            curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
            close()
            moveTo(11.99f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
            curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
            reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
            reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
            reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
            reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
            close()
            moveTo(13.0f, 17.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }.build()
}

/** 授权时间（`18` §5.1 口径，与原设置页授权信息卡同一格式）。 */
private fun fmtAuthTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))

/** 当前模块版本号（未授权态最下面一行展示用）。 */
fun moduleVersionLabel(): String =
    "版本 " + com.bydhooker.autostart.BuildConfig.VERSION_NAME

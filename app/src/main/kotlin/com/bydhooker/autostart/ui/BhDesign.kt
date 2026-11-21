package com.bydhooker.autostart.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 玻璃采样源（Backdrop）的跨层传递（2026-11-21 新增）。
 *
 * **为什么需要它**：`GlassDialog` / `GlassDropdownPreference` 这类玻璃控件必须拿到
 * 「页面背景录制」（`rememberLayerBackdrop()` + `Modifier.layerBackdrop(...)`），
 * 否则只能退化为不透明底色。录制对象只在 `SettingsActivity.Root()` 里创建一次，
 * 而消费方在很深的层（`SettingsTab → ParamsCard → NfcCardSpinnerRow`、各个弹窗），
 * 逐层加参数会把 backdrop 污染进 6+ 个函数签名 —— 用 CompositionLocal 一次提供、随处读取。
 *
 * **null 语义**：未提供 / 未录制时为 null，玻璃控件按官方 fallback 走不透明容器色
 * （不会崩，只是没有模糊）。所以在 Preview / 单测里不提供也能渲染。
 *
 * 用 `staticCompositionLocalOf`：这个值在整个 Activity 生命周期内不变（一次创建），
 * 不需要重组追踪，静态版本开销更低。
 */
val LocalBhBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 视觉 token（`18-UI设计定稿.md` §3；全部为【设计建议】目标值，非 miuix 官方值）。
 *
 * 用法口径（18 §11 #1/#2）：
 * - 表面/文字基色**优先用 miuix 默认**（MiuixTheme.colorScheme.*），本表只供
 *   ① 状态色点（§3.2）、② Ink 中性主色（§3.1 主色决策：Switch 开态/滑块激活/主按钮
 *   实底/底栏选中态——不用 miuix primary，避免引入彩色品牌色）、③ 危险红字。
 * - 深色切换：结构守恒，只换 hex（§3.6）。
 */
object BhColors {

    /** 中性主色 Ink（唯一主色，不带色相；§3.1）。 */
    fun ink(dark: Boolean): Color = if (dark) Color(0xFFE9ECF1) else Color(0xFF20252E)

    /** Ink 上反色文字（主按钮文字）。 */
    fun onInk(dark: Boolean): Color = if (dark) Color(0xFF14181E) else Color(0xFFFFFFFF)

    /** 正常（绿 ●）。 */
    fun ok(dark: Boolean): Color = if (dark) Color(0xFF45C98C) else Color(0xFF1B8A52)

    /** 需重授权 / WARN（黄 ◉ / ●）。 */
    fun warn(dark: Boolean): Color = if (dark) Color(0xFFF0C24B) else Color(0xFF96690A)

    /** 不可用 / ERROR（红 ■）。 */
    fun error(dark: Boolean): Color = if (dark) Color(0xFFF2776E) else Color(0xFFC23B32)

    /** 未知 / 降级（灰 ○）。 */
    fun unknown(dark: Boolean): Color = if (dark) Color(0xFF8A93A0) else Color(0xFF6B7280)

    // 2026-11-10：原 `okContainer` / `onOkContainer`（Step 2G 自派生的绿容器色）已删除。
    // 授权状态大卡改为 1:1 照抄 KernelSU `HomeMiuix.kt` StatusCard，容器色直接用官方
    // 分支的硬编码值（动态色 → `colorScheme.secondaryContainer`；深 `#1A3825`；浅 `#DFFAE4`），
    // 不再需要项目自派生色。见 `AuthHeroCard.kt`。

    /** 次级表面（提示条 / 滑块未激活轨道 / 结论行底）。 */
    fun surface2(dark: Boolean): Color = if (dark) Color(0xFF262B33) else Color(0xFFEDF0F3)
}

/** 禁用整卡 alpha（§3.5：浅 0.38 / 深 0.30）。 */
fun disabledAlpha(dark: Boolean): Float = if (dark) 0.30f else 0.38f

/** 底栏 / 状态点图标（系统未带 material-icons，自绘极简向量；仅取剪影）。 */
object BhIcons {

    /** 首页：屋形。 */
    val home: ImageVector by lazy {
        ImageVector.Builder(
            name = "BhHome", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3f)
                lineTo(21f, 10.2f)
                lineTo(21f, 21f)
                lineTo(14f, 21f)
                lineTo(14f, 14f)
                lineTo(10f, 14f)
                lineTo(10f, 21f)
                lineTo(3f, 21f)
                lineTo(3f, 10.2f)
                close()
            }
        }.build()
    }

    /** 日志：三横线列表。 */
    val log: ImageVector by lazy {
        ImageVector.Builder(
            name = "BhLog", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 5f)
                horizontalLineTo(20f)
                verticalLineTo(7f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 11f)
                horizontalLineTo(20f)
                verticalLineTo(13f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 17f)
                horizontalLineTo(20f)
                verticalLineTo(19f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
    }
}

package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「支持与关于」卡片（**2026-11-10 新增**，设置页）。
 *
 * **形态对标 KernelSU 截图底部的「支持开发 / 了解 KernelSU」卡**：
 * 每行 = **左侧图标 + 标题 + 副文本（值在标题下方）+ 右侧箭头**，
 * 行间有分隔，全部收在一张卡里。
 *
 * 实现用 **miuix 官方 `ArrowPreference`**（`miuix-preference` 0.9.4，
 * 与 KernelSU 用的是同一个组件 —— 其 `HomeMiuix.kt` 的 `SupportLinks` 直接用
 * `ArrowPreference` + `Icons.Filled.VolunteerActivism` / `Icons.AutoMirrored.Filled.MenuBook`），
 * **不另造轮子**。`ArrowPreference` 自带右箭头（`ArrowPreferenceEndAction`），
 * 无需自绘。
 *
 * 三行内容与图标：
 *  ① **追踪更新** / 关注酷安 —— miuix 官方 `Update`（圆圈 + 向上箭头，标准「更新」符号，
 *     与 `Refresh` 的循环箭头区分得开）；
 *  ② **支持开发** / …向开发者捐赠以示支持 —— Material `Icons.Filled.VolunteerActivism`
 *     （人手托爱心，**与 KernelSU 截图同一枚**）；
 *  ③ **了解本APP** / 了解本APP及如何使用 —— Material `Icons.Filled.MenuBook`
 *     （翻开的书，**与 KernelSU 截图同一枚**）。
 *
 * **点击事件**（2026-11-10 用户拍板绑定）：
 *  ① 追踪更新 → [AppLinks.COOLAPK_URL]（酷安开发者主页，**装了酷安直投、没装走网页**：
 *     避免本机「酷安 + 浏览器」同时存在时弹系统「打开方式」选择器，
 *     同时保证未装酷安的用户仍能通过网页引导下载）；
 *  ② 支持开发 → 弹 `DonateDialog`（收款码 + 「前往支付宝」）；
 *  ③ 了解本APP → 打开 [GuidePage] 二级页（使用说明）。
 *  三个动作全部是普通 Intent / 纯 UI 状态，**不需要 root、不需要模块激活**。
 */
@Composable
fun SupportCard() {
    val ctx = LocalContext.current
    BhCard {
        // ① 追踪更新 —— miuix 官方 `Update`（零新增依赖）。
        ArrowPreference(
            title = "追踪更新",
            summary = "关注酷安",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Update,
                    contentDescription = "追踪更新",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            onClick = {
                // **装了酷安就直投，没装才走网页**（2026-11-10 用户拍板）。
                // 装了 → 不会弹系统「打开方式」选择器（本机还装了 Via，不限定必弹）；
                // 没装 → openUrl 内部跳过直投，交系统打开网页（酷安网页版会引导下载）。
                val msg = AppLinks.openUrl(
                    ctx,
                    AppLinks.COOLAPK_URL,
                    preferPackage = AppLinks.COOLAPK_PACKAGE,
                )
                if (msg.isNotEmpty()) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
        // ② 支持开发 —— Material `Icons.Filled.VolunteerActivism`（KernelSU 同一枚）。
        ArrowPreference(
            title = "支持开发",
            summary = "如果您觉得这个APP有帮助到您，可以向开发者捐赠以示支持",
            startAction = {
                Icon(
                    imageVector = MaterialVolunteerActivism,
                    contentDescription = "支持开发",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            onClick = { SettingsState.showDonateDialog = true },
        )
        // ③ 了解本APP —— Material `Icons.Filled.MenuBook`（KernelSU 同一枚）。
        ArrowPreference(
            title = "了解本APP",
            summary = "了解本APP及如何使用",
            startAction = {
                Icon(
                    imageVector = MaterialMenuBook,
                    contentDescription = "了解本APP",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            // 2026-11-21：从「置 showGuide 布尔」改为「调用注入的 openGuide」——
            // 二级页现在是 miuix-nav 路由栈里的一页（压栈/出栈 + 官方转场），
            // 布尔开关已删除。见 AppState.openGuide 与 SettingsActivity.Root()。
            onClick = { SettingsState.openGuide?.invoke() },
        )
    }
}

/*
 * ---------------------------------------------------------------------------
 * Material 图标内联（零新增依赖）
 *
 * 这两枚图标取自 **KernelSU 同一份素材**（`HomeMiuix.kt` 的 `SupportLinks`）：
 *   androidx.compose.material:material-icons-extended:1.7.8
 *   commonMain/androidx/compose/material/icons/filled/VolunteerActivism.kt
 *   commonMain/androidx/compose/material/icons/filled/MenuBook.kt
 *
 * 与 `AuthHeroCard.kt` / `AppInfoCard.kt` 的既有先例一致：取**精确 pathData 内联**
 * 而非引入该依赖（产物 12 MB、含数千图标）。
 * `materialIcon` → `ImageVector.Builder(24.dp, 24.dp, viewport 24×24)`；
 * `materialPath` → `path(fill = SolidColor(Color.Black), fillAlpha = 1f, pathFillType = NonZero)`。
 * 绘制时由 miuix `Icon` 的 `tint` 覆盖填充色，故 `Color.Black` 仅为占位。
 *
 * 注：这两枚都是**多路径**图标（VolunteerActivism 3 段、MenuBook 5 段），
 * 与官方一致地拆成多个 `path` 调用。
 * ---------------------------------------------------------------------------
 */

/** Material `Icons.Filled.VolunteerActivism`（人手托爱心）—— 用于「支持开发」。 */
private val MaterialVolunteerActivism: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.VolunteerActivism",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(1.0f, 11.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(11.0f)
            horizontalLineToRelative(-4.0f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.0f, 3.25f)
            curveTo(16.65f, 2.49f, 17.66f, 2.0f, 18.7f, 2.0f)
            curveTo(20.55f, 2.0f, 22.0f, 3.45f, 22.0f, 5.3f)
            curveToRelative(0.0f, 2.27f, -2.91f, 4.9f, -6.0f, 7.7f)
            curveToRelative(-3.09f, -2.81f, -6.0f, -5.44f, -6.0f, -7.7f)
            curveTo(10.0f, 3.45f, 11.45f, 2.0f, 13.3f, 2.0f)
            curveTo(14.34f, 2.0f, 15.35f, 2.49f, 16.0f, 3.25f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.0f, 17.0f)
            horizontalLineToRelative(-7.0f)
            lineToRelative(-2.09f, -0.73f)
            lineToRelative(0.33f, -0.94f)
            lineTo(13.0f, 16.0f)
            horizontalLineToRelative(2.82f)
            curveToRelative(0.65f, 0.0f, 1.18f, -0.53f, 1.18f, -1.18f)
            verticalLineToRelative(0.0f)
            curveToRelative(0.0f, -0.49f, -0.31f, -0.93f, -0.77f, -1.11f)
            lineTo(8.97f, 11.0f)
            horizontalLineTo(7.0f)
            verticalLineToRelative(9.02f)
            lineTo(14.0f, 22.0f)
            lineToRelative(8.01f, -3.0f)
            verticalLineToRelative(0.0f)
            curveTo(22.0f, 17.9f, 21.11f, 17.0f, 20.0f, 17.0f)
            close()
        }
    }.build()
}

/** Material `Icons.Filled.MenuBook`（翻开的书）—— 用于「了解本APP」。 */
private val MaterialMenuBook: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.MenuBook",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21.0f, 5.0f)
            curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
            curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
            reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
            verticalLineToRelative(14.65f)
            curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
            curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
            curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
            curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
            curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
            curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
            curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
            verticalLineTo(6.0f)
            curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
            close()
            moveTo(21.0f, 18.5f)
            curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
            verticalLineTo(8.0f)
            curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
            verticalLineTo(18.5f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.5f, 10.5f)
            curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
            verticalLineTo(9.24f)
            curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
            curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
            verticalLineToRelative(1.66f)
            curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.0f, 12.49f)
            verticalLineToRelative(1.66f)
            curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
            curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
            verticalLineTo(11.9f)
            curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
            curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.5f, 14.33f)
            curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
            verticalLineToRelative(1.66f)
            curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
            curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
            verticalLineToRelative(-1.52f)
            curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
            close()
        }
    }.build()
}

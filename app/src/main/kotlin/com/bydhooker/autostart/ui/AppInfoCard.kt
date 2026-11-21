package com.bydhooker.autostart.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用信息卡（**2026-11-10 新增**，设置页）。
 *
 * **形态对标 KernelSU 的「管理器版本 / 内核版本 / 设备型号 / 系统指纹」信息卡**
 * （用户提供的 KernelSU 设置页截图）：每行 = **左侧图标 + 标题 + 副文本（值在标题下方）**，
 * 行间有分隔，全部收在一张卡里。本卡用官方 `BasicComponent` 的 **title / summary 两槽**
 * 承载，与车况卡（`VehicleStatus.kt`）完全同构。
 *
 * 三行内容：
 *  ① **应用版本** —— 本模块（BYDAutoStart）的 versionName；
 *  ② **比亚迪 App 版本** —— 官方 App `com.byd.aeri.caranywhere` 的 versionName；
 *  ③ **车辆型号** —— 授权时从 `getVehicleInfo` 落盘的 `autoType`（如「元 Pro」）。
 *
 * **② 的取值途径**：设置页进程用 `PackageManager.getPackageInfo()` 查询宿主包。
 * targetSdk 35 触发 Android 11+ 包可见性过滤 → 已在 `AndroidManifest.xml` 用
 * `<queries><package android:name="com.byd.aeri.caranywhere"/></queries>` 声明
 * （官方文档《软件包可见性过滤》实证：未声明则 `getPackageInfo` 抛 NameNotFoundException）。
 *
 * **取值失败一律显示「未获取」，不猜测、不编造**。
 */
@Composable
fun AppInfoCard() {
    val ctx = LocalContext.current
    // 宿主 App 版本：一次查询即可，remember 避免重组时反复打 PackageManager。
    val bydAppVersion = remember(ctx) { hostAppVersion(ctx) }
    BhCard {
        InfoRow(MiuixIcons.Tasks, "应用版本", moduleVersionLabel())
        CardDivider()
        InfoRow(MaterialSmartphone, "比亚迪 App 版本", bydAppVersion)
        CardDivider()
        InfoRow(MaterialDirectionsCar, "车辆型号", SettingsState.credAutoType.ifEmpty { "未获取" })
    }
}

/**
 * 宿主（比亚迪官方 App）的 versionName。
 *
 * 查不到时返回「未获取」——**不编造版本号**。可能失败的原因：
 *  ① 官方 App 未安装（此时模块本身也无从工作）；
 *  ② 包可见性未生效（`<queries>` 声明缺失或被裁剪）；
 *  ③ `PackageManager.NameNotFoundException`（包名变更 / OTA 漂移）。
 */
private fun hostAppVersion(ctx: Context): String = try {
    val pm = ctx.packageManager
    val info = pm.getPackageInfo(HOST_PACKAGE_NAME, 0)
    val v = info.versionName
    if (v.isNullOrBlank()) "未获取" else v
} catch (t: Throwable) {
    "未获取"
}

/** 宿主包名（与 `BridgeContract.HOST_PACKAGE` 同值，此处独立常量避免 UI 层依赖 bridge 包）。 */
private const val HOST_PACKAGE_NAME = "com.byd.aeri.caranywhere"

/**
 * 图标 + 标题 + 副文本（值在标题下方）。
 *
 * 与 `VehicleStatus.kt` 的 `IconValueRow` **同构但独立** —— 两处各自是 private，
 * 不重复暴露；形态参数（24dp 图标 / onSurface 着色）保持一致。
 */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    BasicComponent(
        title = label,
        summary = value,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        },
    )
}

/*
 * ---------------------------------------------------------------------------
 * Material 图标内联（零新增依赖）
 *
 * miuix 官方扩展集共 156 枚图标，**全集中没有任何汽车/车辆图标**
 * （GitHub API 全仓 tree 实测：仅 `BankCards` / `Carrier` 命中 car/veh/auto 关键字；
 *   `Carrier` 的 Material 原型虽是「车载信息娱乐」，但 miuix 把它改画成了**购物袋**，
 *   真机截图确认渲染出来像个包 → 不可用）。
 *
 * KernelSU 的设置页信息卡用的正是 **Material Icons**（`Icons.Rounded.*`），
 * 其「设备型号」一行用的是 `Icons.Rounded.DirectionsCar` → 本卡沿用同一素材来源。
 *
 * 与 `AuthHeroCard.kt` 的既有先例一致：
 *   `androidx.compose.material:material-icons-extended:1.7.8`
 *   `commonMain/androidx/compose/material/icons/rounded/<Name>.kt`
 * 取**精确 pathData 内联**而非引入该依赖（产物 12 MB、含数千图标）。
 * `materialIcon` → `ImageVector.Builder(24.dp, 24.dp, viewport 24×24)`；
 * `materialPath` → `path(fill = SolidColor(Color.Black), fillAlpha = 1f, pathFillType = NonZero)`。
 * 绘制时由 miuix `Icon` 的 `tint` 覆盖填充色，故 `Color.Black` 仅为占位。
 * ---------------------------------------------------------------------------
 */

/** Material `Icons.Rounded.Smartphone`（手机）—— 用于「比亚迪 App 版本」。 */
private val MaterialSmartphone: ImageVector by lazy {
    ImageVector.Builder(
        name = "Rounded.Smartphone",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.0f, 1.01f)
            lineTo(7.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(10.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(3.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -1.99f, -2.0f, -1.99f)
            close()
            moveTo(17.0f, 19.0f)
            horizontalLineTo(7.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }.build()
}

/** Material `Icons.Rounded.DirectionsCar`（轿车侧视轮廓）—— 用于「车辆型号」。 */
private val MaterialDirectionsCar: ImageVector by lazy {
    ImageVector.Builder(
        name = "Rounded.DirectionsCar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(18.92f, 6.01f)
            curveTo(18.72f, 5.42f, 18.16f, 5.0f, 17.5f, 5.0f)
            horizontalLineToRelative(-11.0f)
            curveToRelative(-0.66f, 0.0f, -1.21f, 0.42f, -1.42f, 1.01f)
            lineToRelative(-1.97f, 5.67f)
            curveToRelative(-0.07f, 0.21f, -0.11f, 0.43f, -0.11f, 0.66f)
            verticalLineToRelative(7.16f)
            curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
            reflectiveCurveTo(6.0f, 20.33f, 6.0f, 19.5f)
            lineTo(6.0f, 19.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(0.5f)
            curveToRelative(0.0f, 0.82f, 0.67f, 1.5f, 1.5f, 1.5f)
            curveToRelative(0.82f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
            verticalLineToRelative(-7.16f)
            curveToRelative(0.0f, -0.22f, -0.04f, -0.45f, -0.11f, -0.66f)
            lineToRelative(-1.97f, -5.67f)
            close()
            moveTo(6.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(5.67f, 13.0f, 6.5f, 13.0f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveTo(7.33f, 16.0f, 6.5f, 16.0f)
            close()
            moveTo(17.5f, 16.0f)
            curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
            close()
            moveTo(5.0f, 11.0f)
            lineToRelative(1.27f, -3.82f)
            curveToRelative(0.14f, -0.4f, 0.52f, -0.68f, 0.95f, -0.68f)
            horizontalLineToRelative(9.56f)
            curveToRelative(0.43f, 0.0f, 0.81f, 0.28f, 0.95f, 0.68f)
            lineTo(19.0f, 11.0f)
            lineTo(5.0f, 11.0f)
            close()
        }
    }.build()
}

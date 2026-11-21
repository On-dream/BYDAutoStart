package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bydhooker.autostart.data.XposedServiceGate
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 弹窗统一入口（2026-11-21 重写：自绘 `Dialog` 卡片 → miuix 原生 `GlassDialog`）。
 *
 * **为什么可以改了**（用户 2026-11-21 要求「所有弹窗改为 miuix 原生 dialog」）：
 * 本项目此前在 `BhDialogs` / `AuthDialog` / `NfcCardPicker` 三处都记录过同一个绕行理由 ——
 * miuix `WindowDialog` 内部 `DialogContentLayout` 调 `NavigationBackHandler`，
 * 而 Compose 独立 window 里没有 `LocalNavigationEventDispatcherOwner`
 * → `IllegalStateException: No NavigationEventDispatcher was provided`；
 * `OverlayDialog` 则要求 `Scaffold` 祖先。
 * 现在两件事都成立了（0.9.5-beta02 实测）：
 *  · `Scaffold` 已引入（`SettingsActivity.Root`，官方结构，提供 `MiuixPopupHost`）；
 *  · miuix 新增 `WindowNavigationEventScope`（`utils/WindowNavigationEventScope_androidKt`，
 *    javap 实证存在），把返回事件重新绑到当前 window。
 * 因此改用官方玻璃弹窗 `GlassDialog`，不再自绘壳。
 *
 * **⚠️ 调用约定（官方 glassdialog.md 明确）**：
 *  · 组件调用**必须留在组合树里**，用 `show` 控制显隐；
 *    **不能**写 `if (show) GlassDialog(...)` —— 那样退场动画会被直接跳过。
 *    本文件与 `AuthDialog` / `DonateDialog` / `NfcCardPicker` 全部按 `show` 参数写。
 *  · `backdrop` 传**页面背景录制**（`Root` 里的 `rememberLayerBackdrop()`），
 *    且该录制区**不含**弹窗与 scrim，否则玻璃会采样到自己（guide/glass.md 硬性要求）。
 *    传 null 时退化为不透明 `containerColor`（观感差一档，但不会崩）。
 */

/**
 * 玻璃弹窗外壳（供需要自定义内容的弹窗复用：授权二维码、收款码、NFC 选卡）。
 *
 * @param show 目标可见性。**必须**由调用方持续把组件留在组合里（见文件头）。
 * @param title 弹窗标题（`GlassDialog` 自带排版；传 null 则无标题行）。
 * @param summary 支持性正文（单段浅色小字）。多段/自定义内容请走 [content]。
 * @param maxWidth 最大宽度覆盖。授权弹窗内含 200dp 二维码，需要比默认更宽。
 */
@Composable
fun BhGlassDialog(
    show: Boolean,
    title: String?,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
    summary: String? = null,
    maxWidth: Dp? = null,
    content: @Composable () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        show = show,
        backdrop = backdrop,
        title = title,
        summary = summary,
        maxWidth = maxWidth ?: DialogMaxWidth,
        content = content,
    )
}

/**
 * `GlassDialog` 默认宽度（`DialogDefaults.MaxWidth`）。
 * 这里显式引用官方常量而非写死 dp：官方若调整，跟随之。
 */
private val DialogMaxWidth: Dp
    get() = top.yukonga.miuix.kmp.layout.DialogDefaults.MaxWidth

/**
 * 二次确认弹窗（内容是 `GlassDialog` 自带 title + summary + 两个 `GlassDialogButton`）。
 *
 * 语义与旧版逐字一致（`18` §5.8）：取消为中性、确认项为 Destructive（红字）；
 * 返回键/点外部 = 取消。
 */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
) {
    BhGlassDialog(
        show = show,
        title = title,
        summary = body,
        onDismiss = onDismiss,
        backdrop = backdrop,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text("取消", style = MiuixTheme.textStyles.button)
            }
            GlassDialogButton(
                onClick = {
                    // 先关窗再执行：与旧实现同序，避免确认动作阻塞退场动画。
                    onDismiss()
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Destructive,
            ) {
                Text(confirmText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** 清除授权凭据二次确认（`18` §5.5 逐字）。 */
@Composable
fun ClearCredentialDialog() {
    ConfirmDialog(
        show = SettingsState.showClearCredDialog,
        title = "清除授权凭据？",
        body = "将删除本机保存的授权凭据，模块随即停止自动上电，" +
            "需要重新扫码授权才能恢复。此操作不可撤销。",
        confirmText = "清除凭据",
        onConfirm = { XposedServiceGate.clearCredentials() },
        onDismiss = { SettingsState.showClearCredDialog = false },
        backdrop = LocalBhBackdrop.current,
    )
}

/** 清空事件日志二次确认（`18` §7.4 E-8 逐字；架构 A 下按钮禁用，弹窗保留给 T05）。 */
@Composable
fun ClearLogDialog() {
    ConfirmDialog(
        show = SettingsState.showClearLogDialog,
        title = "清空事件日志？",
        body = "将删除本机全部 500 条以内的事件记录，清空后无法恢复。",
        confirmText = "清空",
        onConfirm = { /* T05：经桥触发宿主 EventLog.clear（架构 A 下不可达） */ },
        onDismiss = { SettingsState.showClearLogDialog = false },
        backdrop = LocalBhBackdrop.current,
    )
}

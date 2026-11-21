package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 授权弹窗（Step 2G，2026-11-09 用户拍板）。
 *
 * **交互（用户逐字指定）**：
 *  - 首页授权状态卡在**未授权**时点击 → 本弹窗展示**二维码** + **文本解释如何完成授权**；
 *  - 在**已授权**时点击 → 本弹窗展示**两个选项**：`清除凭据` / `重新授权`。
 *
 * **两种模式**（`SettingsState.authDialogFlow`）：
 *  - `true` = **流程模式**：说明文本 + 二维码区（承接原七态渲染）；
 *  - `false` = **选项模式**：已授权 → 「清除凭据」/「重新授权」两个按钮。
 *
 * **2026-11-21 换壳**：自绘 `androidx.compose.ui.window.Dialog` → miuix 原生 `GlassDialog`
 * + `GlassDialogButton`（用户要求「所有弹窗改为 miuix 原生 dialog」）。
 * 此前不能用 miuix 弹窗的根因（`WindowDialog` 缺 `NavigationEventDispatcher`、
 * `OverlayDialog` 缺 `Scaffold`）现已解除，详见 `BhDialogs.kt` 文件头。
 *
 * **业务零改动**：二维码渲染、剩余时间、重新生成、状态推进全部复用既有
 * `authController`（`ui/auth/AuthController.kt`）与 `QrContainer`，**不新增任何网络/凭据逻辑**；
 * 文案逐字沿用 `18` §5.2 已拍板原文。
 */
@Composable
fun AuthDialog() {
    // backdrop 从 CompositionLocal 取（Root 里 rememberLayerBackdrop 一次，见 BhDesign.kt）。
    val backdrop = LocalBhBackdrop.current
    // ⚠️ 不再写 `if (!show) return`：GlassDialog 用 `show` 驱动退场动画，
    // 组件必须一直留在组合树里（官方 glassdialog.md：「Keep the dialog composed until then」）。
    val show = SettingsState.showAuthDialog

    // 流程模式下授权成功 → 自动关闭弹窗，回到首页看到「已授权」大卡。
    //
    // ⚠ 判据必须是「auth **从**非 AUTHORIZED **变成** AUTHORIZED」，不能只看「当前 == AUTHORIZED」：
    //   已授权态点「重新授权」时，`credPresent` 仍为 true 且 `auth` 初始就是 AUTHORIZED，
    //   若用「当前值 == AUTHORIZED」判定，弹窗会在点下的瞬间立刻关闭（用户什么都没看到）。
    //   故用 remember 的「上一次 auth 值」做前后对比，只在**真正发生跃迁**时关窗。
    var prevAuth by remember { mutableStateOf(SettingsState.auth) }
    LaunchedEffect(SettingsState.auth) {
        val prev = prevAuth
        prevAuth = SettingsState.auth
        if (SettingsState.authDialogFlow &&
            prev != AuthUi.AUTHORIZED &&
            SettingsState.auth == AuthUi.AUTHORIZED
        ) {
            SettingsState.showAuthDialog = false
        }
    }

    // maxWidth 340.dp：内含 200dp 二维码 + 四边 insideMargin，用官方默认宽度会压窄二维码。
    BhGlassDialog(
        show = show,
        title = if (SettingsState.authDialogFlow) "扫码授权" else "已授权",
        onDismiss = { SettingsState.showAuthDialog = false },
        backdrop = backdrop,
        maxWidth = 340.dp,
    ) {
        if (SettingsState.authDialogFlow) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 文本解释如何完成授权（`18` §5.2 NONE 态逐字文案，流程中始终可见）
                // 2026-11-19 用户要求追加一句 root 授权提示（扫码前先给模块授权）
                Text(
                    "首次使用需通过比亚迪官方 App 扫码授权一次。授权动作在模块内完成，" +
                        "凭据加密后仅保存在本机。请为模块授予root权限。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                AuthFlowBody()
            }
        } else {
            // 已授权 → 两个选项（用户逐字指定）
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "模块已取得授权凭据。你可以清除凭据，或重新扫码授权。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassDialogButton(
                        onClick = {
                            SettingsState.showAuthDialog = false
                            SettingsState.showClearCredDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = SettingsState.bridgeAvailable,
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text("清除凭据", style = MiuixTheme.textStyles.button)
                    }
                    GlassDialogButton(
                        onClick = {
                            SettingsState.authDialogFlow = true
                            authController.startAuth()
                        },
                        modifier = Modifier.weight(1f),
                        // 桥不可用时 `authController.startAuth()` 会直接 return（不产生任何状态变化）
                        // → 若不禁用，点下去既无反应又会让流程模式的「授权成功即关窗」条件
                        //   立刻成立（auth 仍为 AUTHORIZED）→ 弹窗秒关，用户以为程序坏了。
                        //   故与「清除凭据」一致按桥可用性禁用（§5.5 同口径）。
                        enabled = SettingsState.bridgeAvailable,
                    ) {
                        Text("重新授权", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

/**
 * 授权流程主体（二维码 + 状态推进）。
 *
 * 直接复用既有七态渲染分支（`CREATING` / `WAITING_SCAN` / `SCANNED` /
 * `CONFIRMING` / `FAILED`），**逐字沿用既有文案**，不新写任何解释性文案。
 * 2026-11-21：按钮由项目自绘 `BhTextButton` 换成官方 `GlassDialogButton`。
 */
@Composable
private fun ColumnScope.AuthFlowBody() {
    when (SettingsState.auth) {
        AuthUi.CREATING -> {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BhLoading()
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在生成二维码…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AuthUi.WAITING_SCAN -> {
            QrContainer(enabled = true, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text(
                "请用比亚迪官方 App 扫码",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "剩余 ${mmss(SettingsState.qrRemainingSec)}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            GlassDialogButton(
                onClick = {
                    authController.cancelAuth()
                    authController.startAuth()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("重新生成", style = MiuixTheme.textStyles.button)
            }
            Spacer(Modifier.height(8.dp))
            // 快速授权（2026-11-09）：免截图，直接把二维码 payload 交给官方 App。
            // 需要 root：写宿主私有目录 + am start 拉起未导出的 WatchKeyLoginActivity。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            GlassDialogButton(
                onClick = {
                    val msg = com.bydhooker.autostart.ui.auth.QuickAuth.launch(
                        SettingsState.qrPayload,
                    )
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG)
                        .show()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("快速授权", style = MiuixTheme.textStyles.button)
            }
        }
        AuthUi.SCANNED -> {
            QrContainer(enabled = false, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text(
                "已扫码，请在手机上点确认",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        AuthUi.CONFIRMING -> {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BhLoading()
                Spacer(Modifier.height(12.dp))
                Text(
                    "确认中，正在换取凭据…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AuthUi.FAILED -> {
            // 「授权未完成」用 headline1 + 项目 error 色（`BhColors.error`，深浅两套，
            // 与旧版同源；不直接用 colorScheme.error，避免与项目既有红色体系分叉）。
            Text(
                "授权未完成",
                style = MiuixTheme.textStyles.headline1,
                color = BhColors.error(isDarkMode()),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                SettingsState.authFailReason,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            GlassDialogButton(
                onClick = { authController.retry() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("重试", style = MiuixTheme.textStyles.button)
            }
        }
        else -> {
            // NONE：尚未出码（桥不可用时 startAuth 会直接返回）→ 给出降级说明
            Text(
                "请先打开一次比亚迪官方 App 以激活模块",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** mm:ss 倒计时（`18` §5.2 Q-8 定稿）。 */
fun mmss(totalSec: Long): String =
    "%d:%02d".format(java.util.Locale.US, totalSec / 60, totalSec % 60)

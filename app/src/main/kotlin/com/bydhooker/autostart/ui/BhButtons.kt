package com.bydhooker.autostart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按钮（`18` §3.5 分级：主=Ink 实底 / 次=outline / 危险=红字 outline 非红实底）。
 *
 * miuix `Button` / `TextButton` 无默认参数（javap 实证 0 处 `$default`），全参显式传入；
 * 颜色口径：主按钮 Ink 实底 + on-ink 文字；危险红字 = `--bh-error`。
 */

/** 主按钮（Ink 实底、占满内容宽、高 ≥48）。全站仅两处：
 *  ① 授权 `NONE` 态「扫码授权」；② 支持开发弹窗「前往支付宝」（2026-11-10 新增）。 */
@Composable
fun BhPrimaryButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = BhColors.ink(isDarkMode())
    val onInk = BhColors.onInk(isDarkMode())
    val text2 = MiuixTheme.colorScheme.onSurface
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        cornerRadius = 8.dp,
        minWidth = 0.dp,
        minHeight = 48.dp,
        colors = ButtonDefaults.buttonColors(
            color = ink,
            disabledColor = ink.copy(alpha = 0.12f),
            contentColor = onInk,
            disabledContentColor = text2.copy(alpha = 0.38f),
        ),
        insideMargin = ButtonDefaults.InsideMargin,
        interactionSource = null,
        indication = null,
    ) {
        BhText(text, slot = BhTextStyle.Button, bold = true, color = onInk)
    }
}

/** 次按钮（outline；`重新授权`/`重新生成`/`重试`/`测试车况查询`/`导出`）。
 *  danger = 危险按钮（红字 outline，非红实底；`清除凭据`/`清空`）。 */
@Composable
fun BhTextButton(
    text: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = isDarkMode()
    val content = if (danger) BhColors.error(dark) else MiuixTheme.colorScheme.onSurface
    val disabled = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val textStyle = MiuixTheme.textStyles.button
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 8.dp,
        minWidth = 0.dp,
        minHeight = 44.dp,
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = content,
            disabledTextColor = disabled,
        ),
        insideMargin = ButtonDefaults.InsideMargin,
        textStyle = textStyle,
        interactionSource = null,
        indication = null,
    )
}

/** 环形进度（CREATING / CONFIRMING；Ink 色，`18` §5.8 loading）。 */
@Composable
fun BhLoading() {
    InfiniteProgressIndicator(
        modifier = Modifier.size(40.dp),
        color = BhColors.ink(isDarkMode()),
        size = 40.dp,
        strokeWidth = 3.dp,
        orbitingDotSize = 4.dp,
    )
}

/** 二维码容器（WAITING_SCAN 真码渲染；zxing 纯 Java 编码，白底黑码保证可扫）。 */
@Composable
fun QrContainer(enabled: Boolean, modifier: Modifier = Modifier) {
    val payload = SettingsState.qrPayload
    Box(
        modifier
            .size(200.dp)
            .background(
                if (enabled) MiuixTheme.colorScheme.surfaceContainer
                else MiuixTheme.colorScheme.surface,
            )
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        if (payload.isEmpty()) {
            BhText(
                "二维码占位", slot = BhTextStyle.Footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            val sizePx = 400
            val bmp = androidx.compose.runtime.remember(payload) {
                val bits = com.google.zxing.qrcode.QRCodeWriter().encode(
                    payload, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
                    mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
                )
                val pixels = IntArray(sizePx * sizePx) { i ->
                    val x = i % sizePx
                    val y = i / sizePx
                    if (bits.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                android.graphics.Bitmap.createBitmap(pixels, sizePx, sizePx, android.graphics.Bitmap.Config.RGB_565)
            }
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "授权二维码",
                modifier = Modifier.size(190.dp),
            )
        }
    }
}

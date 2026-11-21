package com.bydhooker.autostart.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「支持开发」收款码弹窗（**2026-11-10 新增**，设置页支持卡第 2 行）。
 *
 * **形态（用户逐字指定）**：复用公共弹窗外壳（2026-11-21 起是 miuix 原生 `BhGlassDialog`），
 * 有文案、有按钮；**只保留一个按钮**「前往支付宝」，**不做「保存到相册」** ——
 * 用户截图即可。
 *
 * **只有"前往支付宝"的原因**：用户是在同一台手机上看到这张码的，无法用这台手机
 * 扫自己屏幕。所以按钮的职责不是「显示二维码」，而是把用户送到支付宝的付款入口；
 * 用户在此之前先截一张图，到支付宝里「扫一扫 → 相册」选它。
 *
 * 按钮点击走 [AppLinks.openDonate]：先尝试把收款码链接直接交给支付宝（直达付款页），
 * 不行退化为打开支付宝首页。**未安装支付宝时按钮仍可点**，只弹 Toast。
 */
@Composable
fun DonateDialog() {
    // backdrop 从 CompositionLocal 取（Root 里 rememberLayerBackdrop 一次）。
    val backdrop = LocalBhBackdrop.current
    // 不用 `if (!show) return`：GlassDialog 靠 show 驱动进退场动画（官方要求常驻组合）。
    val show = SettingsState.showDonateDialog

    val ctx = LocalContext.current
    // assets 里的图只解码一次（持有 Application context，不会泄漏 Activity）。
    val qr: Bitmap? = remember { loadDonateQr(ctx) }

    BhGlassDialog(
        show = show,
        title = "支持开发",
        onDismiss = { SettingsState.showDonateDialog = false },
        backdrop = backdrop,
        maxWidth = 320.dp,
    ) {
        BhText(
            "如果这个 App 有帮到你，可以扫码支持一下开发。金额随意，感谢。",
            slot = BhTextStyle.Body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        // 二维码必须衬一层**纯白**圆角底：
        //  · 玻璃弹窗是半透明的，码的黑块若直接压在玻璃上会与背后内容糊在一起、边缘分不清；
        //  · 8dp 内边距让图中自带的 quiet zone 之外再留一圈，视觉上不顶边。
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "支付宝收款码",
                        modifier = Modifier.size(216.dp),
                    )
                } else {
                    // 资源缺失（不应发生）→ 不留空白方块，直接给一句可读的说明。
                    Box(Modifier.size(216.dp), contentAlignment = Alignment.Center) {
                        BhText(
                            "收款码缺失",
                            slot = BhTextStyle.Footnote1,
                            color = Color(0xFF8A8A8A),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BhText(
            // 2026-11-10 实测后改写：按钮现在**直接打开支付宝扫一扫**（不是首页），
            // 用户只需再点一下扫一扫右下角的「相册」。文案必须跟着行为走。
            "先截图，按钮会打开扫一扫，再选「相册」",
            slot = BhTextStyle.Footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // 2026-11-21：主按钮由项目 `BhPrimaryButton`（Ink 实底）换成官方
        // `GlassDialogButton(role = Primary)` —— 弹窗内按钮与弹窗材质同源，
        // 深色下不会出现「深色玻璃上再叠一块纯黑实底」的割裂。
        GlassDialogButton(
            onClick = {
                val msg = AppLinks.openDonate(ctx)
                if (msg.isNotEmpty()) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                } else {
                    // 成功发起跳转后关窗：用户已经离开本页，留着弹窗只会在返回时挡一下。
                    SettingsState.showDonateDialog = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            role = GlassDialogButtonRole.Primary,
        ) {
            Text("前往支付宝", style = MiuixTheme.textStyles.button)
        }
    }
}

/** 从 `assets/donate/alipay_qr.png` 解码收款码（原图裁切，未重绘、未缩放）。 */
private fun loadDonateQr(ctx: android.content.Context): Bitmap? = try {
    ctx.assets.open("donate/alipay_qr.png").use { BitmapFactory.decodeStream(it) }
} catch (_: Throwable) {
    null
}

// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.bydhooker.autostart.ui.official

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bydhooker.autostart.R
import com.bydhooker.autostart.ui.official.effect.BgEffectBackground

/**
 * 官方 example `AboutPage.kt` 的**页面外壳**移植（2026-11-21 用户要求
 * 「直接移植示例app的页面」）。
 *
 * 完整保留官方的三段式结构与动效：
 *  ① **动态流体背景**：`BgEffectBackground`（`component/effect/` 7 个文件一起移植，
 *     OS3 shader + 颜色插值动画），背景随滚动淡出（`alpha = 1 - scrollProgress`）；
 *  ② **logo 固定 + 内容上盖**：logo 区在列表之外（Column），列表首项是透明
 *     `logoSpacer`，高度按 logo 实测高度算，使列表内容从 logo 下方开始、
 *     滚动时上移覆盖 logo；
 *  ③ **滚动折叠**：`scrollProgress` 由 logoSpacer 的滚动比例推出，
 *     驱动 logo 图标缩小淡出、标题渐显（`titleAlpha`）。
 *
 * 与官方差异（均为本项目适配，不是简化偷懒）：
 *  · `AdaptiveTopAppBar` → 本项目的 `GlassTopAppBar`（同样支持 `titleAlpha`）；
 *  · `BlurredBar` / `rememberBlurBackdrop` → 直接用 `GlassTopAppBar` 自带的
 *    `progressiveBlur` + 我们的 `layerBackdrop`；
 *  · `VersionInfo` → 由调用方传入版本文本（本模块走 packageManager 自取）；
 *  · 图标底色 **Ink #20252E**（= 本模块 adaptive icon 的背景色，用户 2026-11-21
 *    明确要求「图标应该是黑底」；官方示例用的是白底）；
 *  · 官方底部那张 `GlassBottomSheet`（纹理参数调试面板）不移植 —— 那是示例 App 的调试 UI。
 *
 * @param appName logo 区的大标题。
 * @param versionText logo 区的版本行（形如 `v3.2.0 (268)`）。
 * @param belowLogo logo 之后的列表内容（链接卡 + 说明各节由调用方提供）。
 */
@Composable
fun OfficialAboutPage(
    appName: String,
    versionText: String,
    onBack: () -> Unit,
    belowLogo: LazyListScope.() -> Unit,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current

    // 官方同款：内容已滚过头 → 进度 1；否则按 logoSpacer 的滚动比例算。
    val scrollProgress by remember {
        computedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    // 官方 rememberBlurBackdrop 的等价物：先铺不透明底再录内容。
    // 底色必须在 composable 作用域取（rememberLayerBackdrop 的 lambda 不是 composable）。
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    var logoHeightDp by remember { mutableStateOf(300.dp) }
    // 标题在 0.35~1.0 的进度区间里渐显（官方同值）。
    val titleAlpha = { ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
                title = "了解本APP",
                titleAlpha = { titleAlpha() },
                isContentScrolled = lazyListState.canScrollBackward,
                expandable = false,
                progressiveBlur = true,
                backdrop = backdrop,
                bandBrush = GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface),
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = { BackNavigationIcon(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            BgEffectBackground(
                dynamicBackground = true,
                isOs3Effect = true,
                isFullSize = true,
                modifier = Modifier.fillMaxSize(),
                alpha = { 1f - scrollProgress },
            ) {
                // ---- logo 区（列表之外，滚动时缩小淡出）----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = innerPadding.calculateTopPadding() + 52.dp)
                        .onSizeChanged { size ->
                            logoHeightDp = with(density) { size.height.toDp() }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .graphicsLayer {
                                val iconProgress = ((scrollProgress - 0.35f) / 0.15f).coerceIn(0f, 1f)
                                clip = true
                                shape = RoundedCornerShape(24.dp)
                                alpha = 1 - iconProgress
                                scaleX = 1 - (iconProgress * 0.05f)
                                scaleY = 1 - (iconProgress * 0.05f)
                            }
                            // **Ink #20252E** = 本模块 adaptive icon 的背景色（用户要求黑底）。
                            .background(Color(0xFF20252E), RoundedCornerShape(24.dp)),
                    ) {
                        Image(
                            modifier = Modifier.size(74.dp),
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                        )
                    }
                    Text(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 5.dp)
                            // 【补移植】官方 projectNameProgress：0.20~0.35 区间淡出并微缩。
                            // 三层错峰顺序：版本号(0.05~0.20) → 标题(0.20~0.35) → 图标(0.35~0.50)
                            // → 顶栏标题渐显(0.35~1.0)，官方同款。
                            .graphicsLayer {
                                val nameProgress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
                                alpha = 1 - nameProgress
                                scaleX = 1 - (nameProgress * 0.05f)
                                scaleY = 1 - (nameProgress * 0.05f)
                            },
                        text = appName,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 【补移植】官方 versionCodeProgress：0.05~0.20 区间淡出（最先消失）。
                            .graphicsLayer {
                                val versionProgress =
                                    ((scrollProgress - 0.05f) / 0.15f).coerceIn(0f, 1f)
                                alpha = 1 - versionProgress
                            },
                        text = versionText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        // 【补移植】官方版本号是 fillMaxWidth + textAlign=Center，
                        // 少了 textAlign 文字会贴到屏幕左边。
                        textAlign = TextAlign.Center,
                    )
                }

                // ---- 内容列表：首项是透明 spacer，让内容从 logo 下方开始 ----
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    // 官方 scrollPadding.top = 0：About 顶栏是**浮空**的（内容从它下面滚过去，
                    // 顶栏玻璃浮在上层）。这里同样置 0，logo 的顶部留白自己算。
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = innerPadding.calculateBottomPadding() + 32.dp,
                    ),
                ) {
                    item(key = "logoSpacer") {
                        // 官方公式：logoHeight + 52 + logoPadding.top - scrollPadding.top + 126
                        // 抵消掉 top 差值后 = logoHeight + 52 + 40 + 126 = logoHeight + 218.dp。
                        Box(
                            Modifier.height(logoHeightDp + 218.dp),
                        )
                    }
                    item(key = "about") {
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    // 内容（链接卡 + 说明各节）由调用方提供 —— 必须在 LazyListScope
                    // 作用域里调用，不能塞进别的 Column（编译器会报「隐式 receiver」）。
                    belowLogo()
                }
            }
        }
    }
}

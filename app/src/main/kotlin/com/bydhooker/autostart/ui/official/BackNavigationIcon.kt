// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.bydhooker.autostart.ui.official

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 二级页左上角返回按钮（**官方 example `component/BackNavigationIcon.kt` 移植**，
 * 2026-11-21 用户要求「左上角的返回按钮也应该参照示例app的」）。
 *
 * 与官方唯一差异：官方按 `AppState.topAppBarStyle` 在
 * `GlassIconButton`（玻璃样式，`MiuixIcons.Glass.ChevronBackward`，28dp）
 * 与普通 `IconButton`（`MiuixIcons.Back`，24dp）之间二选一；
 * 本项目 UI 全局玻璃（用户 2026-11-21 拍板「全局 glass 透明」），
 * 因此**恒用玻璃分支**，不再引入官方 example 的 `LocalAppState`（那是示例 App 的状态宿主）。
 *
 * RTL 镜像（`scaleX = -1f`）与官方一致。
 */
@Composable
fun BackNavigationIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    GlassIconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                },
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = "返回",
            tint = colorScheme.onSurface,
        )
    }
}

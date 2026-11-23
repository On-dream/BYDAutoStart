package com.bydhooker.autostart.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bydhooker.autostart.data.XposedServiceGate
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference

/**
 * 设置页「NFC 触发卡」行（`docs/30` §5.10.2）。
 *
 * 【形态】参数卡里的**一行原生玻璃下拉**：`NFC 触发卡` + 当前卡名 + 箭头。
 * **没有副行小字、没有区块标题、没有独立卡片**（2026-11-11 23:3x 用户要求
 * 「改成下拉样式、不要卡片标题、下面不要小字」）。
 *
 * 【2026-11-21 换成真·原生下拉 —— 这里终于能用官方的了】
 * 此前本项目在 `NfcCardPicker.kt` / `AuthDialog.kt` / `BhDialogs.kt` 三处都记录过同一份绕行理由：
 *  · miuix `WindowSpinnerPreference` → 内部走 `NavigationBackHandler`，
 *    Compose Dialog 的独立 window 里没有 `LocalNavigationEventDispatcherOwner`
 *    ⇒ 点开直接崩（23:32 真机实测 `IllegalStateException: No NavigationEventDispatcher`）；
 *  · miuix `OverlaySpinnerPreference` → 需要 `Scaffold` 祖先，而旧页面是
 *    `Box + TopAppBar + HorizontalPager` 手搭骨架，没有 Scaffold
 *    ⇒ 有触感、`onExpandedChange` 也收到 true，但**弹层一个像素都不画**（23:29~23:34 实测）。
 * 现在两个前提都变了（0.9.5-beta02 + Scaffold 骨架），因此按用户要求
 * 「下拉栏改为 miuix 原生样式」直接改用 `GlassDropdownPreference`（miuix-glass 原生玻璃下拉），
 * **删掉原来「点开是一个自绘单选框弹窗」的实现**。
 *
 * 【数据来源】`SettingsState.nfcCards` ← 模块私有 SP ← tsmclient 采集后经 Provider 传来。
 * `CardInfoManager` 在 `com.miui.tscmclient` 的 ClassLoader 里，模块 App 进程加载不到
 * （探针 `CardProbe.java:116` 实证）。
 *
 * 【选中态语义】`selectedIndex` 传 `indexOfFirst` 的原始值（可能是 -1）。
 * 官方 doc 明确：out-of-range 的 `selectedIndex` ⇒ **不显示选中值、也不打勾**
 * （原文 "No selection: An out-of-range `selectedIndex` shows no selected value or tick"），
 * 正好对应本项目「还没选过卡」的状态，因此不需要额外的「未选择」占位文案。
 */
@Composable
fun NfcCardSpinnerRow() {
    // 【2026-11-23 修复「首页有 NFC 卡、设置页却没有触发卡」】
    //
    // 主界面三个 Tab 是 Pager **内部切页**，不会触发 Activity 的 `onResume`，
    // 而 NFC 状态（HOOK 三态 + 卡列表）原本只在 `onResume` 里重读（`SettingsActivity:167`）。
    // 于是：App 打开时 tsmclient 还没上报 ⇒ 状态为空；之后上报成功也只是落了 SP，
    // 用户滑到「设置」页时读到的仍是旧状态 ⇒ 「NFC 触发卡」整行不显示。
    // （首页的 ①-N 卡只看 HOOK 三态，所以可能先出来 —— 正是用户看到的现象。）
    // 这里补一次「进入本行就重读」；配合 `NfcWakeProvider` 的主线程回调，两条路都覆盖。
    LaunchedEffect(Unit) { SettingsState.reloadNfcLocal() }

    val cards = SettingsState.nfcCards
    // 诊断留痕：状态变化时记一条，下次同类问题可直接从日志看出是「HOOK 没绿」还是「卡列表为空」。
    LaunchedEffect(SettingsState.nfcHookVisible, cards.size) {
        com.bydhooker.autostart.core.EventLog.debug(
            "NFC_ROW",
            "hookVisible=${SettingsState.nfcHookVisible}|cards=${cards.size}" +
                "|selectedAid=${SettingsState.nfcSelectedAid.take(8)}",
        )
    }
    if (!SettingsState.nfcHookVisible || cards.isEmpty()) {
        return
    }
    // 分隔线放在行内：本行隐藏时连分隔线都不出现，否则参数卡底部会多一条悬空横线。
    CardDivider()
    GlassDropdownPreference(
        onSelectedIndexChange = { index ->
            // 写入是**三键原子提交**（aid 判据 / cid 随卡落盘 / name 显示），
            // 见 XposedServiceGate.writeNfcCard。索引越界（理论上不会）时静默不写。
            cards.getOrNull(index)?.let { c ->
                XposedServiceGate.writeNfcCard(c.aid, c.cid, c.name)
            }
        },
        // 每行只显示卡名：事件层只能拿到产品级 AID、拿不到 cid，
        // 显示尾号会让人误以为「只有这张卡会触发」（2026-11-15 用户要求，docs/30 §5.10）。
        items = cards.map { it.displayName },
        selectedIndex = cards.indexOfFirst {
            SettingsState.nfcSelectedAid.equals(it.aid, ignoreCase = true)
        },
        title = "NFC 触发卡",
        backdrop = LocalBhBackdrop.current,
        // 配置写入依赖桥与授权，缺一就只读（沿用 `docs/18` §6 的统一降级规则）。
        enabled = nfcCardPickable(),
    )
}

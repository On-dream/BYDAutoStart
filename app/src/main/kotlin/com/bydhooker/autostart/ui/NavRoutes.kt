package com.bydhooker.autostart.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 应用路由（miuix-nav，2026-11-21 引入）。
 *
 * 【为什么是两级而不是把 Tab 也做成路由】
 * 底栏三项 `首页/日志/设置` 是 `18` §2 定稿的**一级导航**，由 `HorizontalPager` +
 * `GlassNavigationBar` 承载（左右滑切页、保留各页滚动位置）；`了解本APP` 是**二级页**，
 * 从设置页进入、语义上不属于一级导航。官方 muiix-nav.md 也是这个分工：
 * 「Keep routing separate from navigation chrome」—— `NavDisplay` 管路由栈，
 * `NavigationBar` 管持久目的地。所以这里只有 Main / Guide 两个路由。
 *
 * 【@Serializable 是硬性要求】
 * `rememberNavBackStack<T>(...)` 内部用 `navBackStackSaver` + Json 持久化路由栈，
 * 官方原文：「@Serializable is a hard requirement for every key in rememberNavBackStack」。
 * 因此本项目同时启用了 `org.jetbrains.kotlin.plugin.serialization` 编译器插件
 * （见 app/build.gradle.kts 与 libs.versions.toml 注释）。
 *
 * 【data object】不用普通 object：data object 的 equals/toString 稳定，
 * 官方要求「Equal route/content keys cannot be pushed twice」，
 * 且能避免 `toString()` 冲突导致的路由去重误判。
 */
@Serializable
sealed interface BhRoute : NavKey {

    /** 主界面（Scaffold + 三 Tab + 玻璃顶栏/底栏）。路由栈根，永远不弹出。 */
    @Serializable
    data object Main : BhRoute

    /** 「了解本APP」使用说明页（原覆盖层，2026-11-21 改为真二级页）。 */
    @Serializable
    data object Guide : BhRoute
}

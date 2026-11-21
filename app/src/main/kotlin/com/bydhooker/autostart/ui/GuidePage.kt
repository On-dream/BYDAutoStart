package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bydhooker.autostart.ui.official.OfficialAboutPage

/**
 * 「了解本APP」页 —— **2026-11-21 按官方 example 的 AboutPage 重构**。
 *
 * 页面外壳（动态流体背景、logo 固定 + 内容上盖、滚动折叠、玻璃顶栏、玻璃返回按钮）
 * 全部来自 `ui/official/AboutPage.kt`（官方 example 的移植），本文件只提供内容：
 * logo 文案与版本号、链接卡、说明各节。
 *
 * 文案口径全部来自本项目**已实测**的事实，不写没有依据的承诺：
 *  · 两种模式的定义 = `18` §2.1；
 *  · 「上电是瞬态」= 01:26 `powerGear=3` → 02:03 已回 `1` 的实测记录；
 *  · 「通道没有下电指令」= watch 通道 12 个 commandType 全表实证；
 *    「可以去官方 App 下电」= 2026-11-21 用户提供的事实（官方 App 有自己的下电途径）；
 *  · 「不常驻后台」= 硬约束（零额外常驻 App）。
 */
@Composable
fun GuidePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // 本模块版本（AppInfoCard 同款 packageManager 取法；对自身包名不抛异常）。
    val versionText = remember {
        try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "v${pi.versionName} (${pi.versionCode})"
        } catch (_: Throwable) {
            ""
        }
    }

    OfficialAboutPage(
        appName = "BYD 自动上电",
        versionText = versionText,
        onBack = onBack,
    ) {
        // ---- 链接卡（一张卡两行，用户指定）----
        item(key = "links") {
            BhCard {
                ArrowPreference(
                    title = "View Source",
                    endActions = {
                        Text(
                            text = "GitHub",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    onClick = { uriHandler.openUri(REPO_URL) },
                )
                ArrowPreference(
                    title = "License",
                    endActions = {
                        Text(
                            text = "GPL-3.0",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    onClick = { uriHandler.openUri(LICENSE_URL) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        // ---- 说明各节（原样保留）----
        item { GuideSection("这个 App 做什么", Intro) }
        item { GuideSection("使用前提", Prereq) }
        item { GuideSection("首次使用", FirstRun) }
        item { GuideSection("三种触发方式", Triggers) }
        item { GuideSection("两种上电模式", Modes) }
        item { GuideSection("上电是瞬态", Transient) }
        item { GuideSection("看日志", Logs) }
        item { GuideSection("常见问题", Faq) }
        item { GuideSection("隐私", Privacy) }
    }
}

/** 一个小节：区块标签 + 一张卡片正文。 */
@Composable
private fun GuideSection(title: String, lines: List<String>) {
    BhSubTitle(title)
    BhCard {
        Column(Modifier.padding(16.dp)) {
            lines.forEachIndexed { i, line ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                BhText(
                    line,
                    slot = BhTextStyle.Body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    lineHeightSp = 22,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/*
 * ---------------------------------------------------------------------------
 * 正文（逐节）。用 `List<String>` 而不是一整段 `\n`：
 * 空行在 Compose 里靠 `\n\n` 撑，行距不可控；分段列表能给每段统一 10dp 间隔。
 * ---------------------------------------------------------------------------
 */

/** 项目仓库（2026-11-21 用户指定，「了解本APP」View Source 行跳转目标）。 */
private const val REPO_URL = "https://github.com/On-dream/BYDAutoStart"

/** GPL-3.0 协议官方页面（License 行跳转目标；仓库根的 LICENSE 是同一份文本）。 */
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

private val Intro = listOf(
    "车辆解锁之后，如果你打开主驾车门，替你下发一次「启动」指令，让车提前上电。",
    "官方 App 只有手动远程启动，没有这个联动 —— 每次都要自己打开 App、等车联网、点启动。",
    "触发方式有三种：在官方 App 里解锁、用手机 NFC 刷卡解锁、连按三下电源键。" +
        "三种都在触发现场直接执行，不依赖任何常驻后台程序。",
)

private val Prereq = listOf(
    "1. 手机已 root（KernelSU 或 Magisk），并安装、启用了 LSPosed。",
    "2. 在 LSPosed 里启用本模块，并勾选你要用的那条链对应的作用域：",
    "   · 比亚迪官方 App（com.byd.aeri.caranywhere）：App 内解锁；",
    "   · 小米智能卡（com.miui.tsmclient）：NFC 刷卡；",
    "   · 系统框架（system）：电源键三击；",
    "   · 系统界面（com.android.systemui）：状态提示（超级岛），与上电无关。",
    "3. 手机上装有比亚迪官方 App，已登录，且车辆已完成绑定。",
)

private val FirstRun = listOf(
    "1. 先打开一次比亚迪官方 App，让模块注入生效。" +
        "刚装完模块时可能需要重启官方 App，模块才算真正加载。",
    "2. 回到本 App，点首页顶部的授权卡 → 用官方 App 扫码并确认。授权凭据只保存在本机。",
    "3. 打开一次本 App —— 它会把自己需要的运行文件发布到系统目录。",
    "4. 重启手机。这一步不能省：三击那条链工作在系统进程里，模块代码只在开机时加载；" +
        "以后每次升级模块版本，同样要重启一次才会生效。",
    "5. 回「首页」打开「APP解锁自动上电」总开关并选好模式。" +
        "用 NFC 的还要到「设置」页里选一张触发卡。",
)

private val Triggers = listOf(
    "APP 解锁自动上电（首页第一张卡）：在官方 App 里解锁后触发，开关和模式都在这张卡上。",
    "NFC 解锁自动上电（首页第二张卡）：用手机 NFC 刷卡解锁后触发，开关和模式同样在首页，" +
        "触发卡在「设置」页里选。刷卡解锁由车端本地执行，指令下得比 App 解锁更快。",
    "电源键三击（设置页）：连按三下电源键触发解锁，开关在「设置」页。" +
        "它的上电方式不单独选，直接跟随首页「APP解锁自动上电」的选择。",
    "三条链互不影响，可以单独开关，也可以同时用。",
)

private val Modes = listOf(
    "开门上电：解锁后有一段等待窗口（默认 60 秒），窗口内检测到主驾门被拉开才启动。" +
        "只是解锁、没开门，不会触发；取东西不开门也不会误触发。",
    "解锁上电：解锁瞬间就启动，响应更快，但只是开门取个东西也会触发。" +
        "如果经常在车边停留，建议用开门上电。",
    "APP 解锁和 NFC 刷卡各有一张同款卡片，两张卡可以选不一样的模式。" +
        "「等待窗口」「轮询间隔」「数据龄上限」在「设置」页里调，只影响开门上电。",
)

private val Transient = listOf(
    "启动后车辆通电一段时间会自行恢复，这是正常现象，不是失败。",
    // 【2026-11-21 用户修订】原文案「一旦误触发只能上车处理，不能远程关掉」已过时：
    // 比亚迪官方 App 里可以远程下电，误触发不用再上车。
    // （模块用的 watch 通道 12 个 commandType 确实没有下电指令 —— 那说的是本模块；
    //  官方 App 下电走它自己的途径，两者不矛盾。）
    "这个通道里没有下电 / 熄火指令，所以一旦误触发，可以去比亚迪官方 App 里下电。" +
        "选模式时按这个前提权衡。",
    "三种触发方式都是一样：触发一次，最多下发一次启动指令。",
)

private val Logs = listOf(
    "「日志」页按时间倒序显示本 App 自己记录的事件：授权、加密组件、网络请求等。",
    "想看完整判定链路（何时解锁、条件是否成立、有没有下发、车况复核结果），点「导出」：" +
        "它会把四处的日志合并成一个文件 —— 官方 App、本 App、小米智能卡、系统侧" +
        "（含三击与执行日志），存到哪个位置由你自己选。",
    "升级模块后，系统侧那段日志要等下次重启才会更新，属正常现象。",
    "WARN / ERROR 级别会带颜色标记；全部正常时是中性灰，不用特别关注。",
)

private val Faq = listOf(
    "不会自动上电？依次检查：模块是否启用 → 作用域是否勾了该链对应的项 → " +
        "授权是否还有效 → 首页总开关是否打开。",
    "首页没有「NFC 解锁自动上电」那张卡？说明小米智能卡那边还没被注入。" +
        "结束一次「小米智能卡」再打开本 App，通常就会回来。",
    "升级模块后没反应？重启手机 —— 模块在系统进程里的那部分只在开机时加载。",
    "会额外耗电吗？不会。模块不常驻后台，也没有额外的定时唤醒，" +
        "只有触发的那一下会短暂联网。",
    "换了手机 / 重装系统怎么办？重新走一次扫码授权即可，凭据不跟随账号漫游。",
)

private val Privacy = listOf(
    "授权凭据、车型、车牌都只保存在本机；指令本身要发往比亚迪自己的云端接口才能生效，" +
        "不经过任何第三方服务器。",
    "为了让三条链各自就地执行、互不依赖，凭据在本机的模块私有目录里以明文存放" +
        "（root 与 LSPosed 管理器可以看到）。这是换取「不用常驻后台」的取舍。",
    "事件日志也只写在本机，用于自己排查问题；导出时由你决定发给谁。",
)

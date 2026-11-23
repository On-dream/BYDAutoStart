package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bydhooker.autostart.data.XposedServiceGate
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreferenceDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 首页 Tab（`18` §4.1）：① 授权状态大卡 → ② 合并卡（总开关+模式）→ ③ 车况区块。
 * Step 2B：所有行改用 miuix 0.9.4 公共 preference 组件。
 * Step 2G（2026-11-09 用户拍板）：授权状态从「设置页」搬到**首页最上面**，形态改成参照
 * KernelSU「工作中」卡的强调大卡；原「状态」卡片（授权状态/白盒组件/当前模式/最近事件四行）
 * **整张删除**（用户原话：「去除首页的状态卡片」）。
 *
 * **2026-11-10 本轮调整（用户指定）**：
 *  - **参数卡迁到设置页**（「参数卡片挪到设置页」）→ 实现见 `SettingsTab.kt`；
 *    保留 M1 条件与 `15` §6.2 的键语义不变。
 *  - **车况卡片 + 「获取车况」按钮从设置页迁到首页**（实现见 `VehicleStatus.kt`），
 *    位置在合并卡之后 —— 车况是用户最常看的运行态信息，不必切页。
 */
@Composable
fun HomeTab(topInset: Dp = 0.dp) {
    LazyColumn(
        Modifier.fillMaxWidth(),
        // Step 2L：卡片间距对标 KernelSU —— `HomeMiuix.kt:128` 用
        // `Arrangement.spacedBy(12.dp)` 组织同组卡片，本工程用同一数值。
        //
        // 【2026-11-21 底栏改悬浮后的底部留白】
        // 底栏从「占一行」改回「悬浮玻璃胶囊」（用户指定 IOS-LIKE），会盖在内容之上，
        // 故底部留白回到 96.dp（= 胶囊 54 + 悬浮间距 12 + 手势条 insets 余量 + 视觉呼吸），
        // 保证最后一张卡能完整滚到胶囊上方。
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Step 2M：顶部留白 12.dp（对标 KernelSU 首卡顶部间距
        // `HomeMiuix.kt:126` 的 `Column(Modifier.padding(top = 12.dp))`）。
        //
        // 【2026-11-21 新增 topInset】由 Scaffold 传入 = 玻璃顶栏高度。
        // 内容因此**延伸到顶栏下方**（首项从顶栏底面开始），滚动时内容穿过顶栏后面，
        // 玻璃顶栏才采得到真实内容；旧写法把内容排在顶栏下方，玻璃只能采到纯底色。
        contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 24.dp),
    ) {
        // Step 2G：授权状态大卡置于首页**最上面**（用户指定位置）。
        item { AuthStatusHeroCardHost() }
        // Step 2M：提示条改为**条件性 item** —— 此前即使没有任何提示内容，它仍占一个
        // item 位，`spacedBy(12.dp)` 会在它前后各插一份间距，导致「授权大卡」到
        // 「自动上电」卡实际是 **24.dp**（卡片之间的两倍），视觉上头重脚轻
        // （用户反馈：「与大标题距离太近、与自动上电卡片距离太远，不协调」）。
        // 改为**无提示时不生成该 item**，间距回到统一的 12.dp。
        val notice = homeNoticeLines()
        if (notice.isNotEmpty()) {
            item { NoticeBanner(notice) }
        }
        // Step 2D：原「自动上电」区块小标题已删（与卡内 SwitchPreference 标题重复）；
        // Step 2L：原先这里还有一个 8.dp Spacer 充当卡间距，现由 LazyColumn 的
        // spacedBy(12.dp) 统一承担，故一并删除。
        item { MergedCard() }
        // ①-N NFC 方案卡（`docs/30` §5.10.1 / `docs/28` §1.3 ⑦）。
        // **显示条件 = L1+L2+L3 全绿**（`docs/29` §5.1 Q2=乙；L4 心跳不作门槛）——
        // Hook 没装上时这张卡根本点不动，显示出来只会误导，所以整卡隐藏（不置灰、不留占位）。
        // 数据源 = 模块私有 SP（`NfcLocalState`），同进程直读，零延迟。
        if (SettingsState.nfcHookVisible) {
            item { NfcSchemeCard() }
        }
        // 车况区块（**2026-11-10 从设置页迁入**）：车况卡 + 「获取车况」按钮。
        // 按钮**与卡片分离**（沿用 Step 2Q 约定），两者各占一个 item 位、
        // 间距由统一的 spacedBy(12.dp) 承担。
        item { VehicleStatusCard() }
        item { VehicleStatusButton() }
        // 【2026-11-21 悬浮玻璃底栏的"避让"item】
        // 底部 contentPadding 已按官方示例收到 24.dp（内容可穿到胶囊后面 → 玻璃才有东西可采样），
        // 但「获取车况」是**功能按钮**，不能被胶囊压住 —— 故在列表末尾补一个占位 item，
        // 让滚到底时按钮停在胶囊上方。48.dp(占位) + 12.dp(spacedBy) = 60.dp ≈ 胶囊高度 54 + 呼吸。
        // 卡片仍然可以穿到胶囊后面滚动 → 液态玻璃效果保留。
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/**
 * 授权状态大卡的点击行为（Step 2G）。
 *
 * 用户指定：**未授权**点卡片 → 弹窗展示二维码 + 授权步骤说明；
 * **已授权**点卡片 → 弹窗展示两个选项（清除凭据 / 重新授权）。
 * 两种情形共用同一个 `AuthDialog`（内部按 `credPresent` 分支渲染）。
 *
 * 「未授权」时点开弹窗会**立即发起出码**（`authController.startAuth()`）——
 * 否则用户点开只能看到一句说明文案，还要再点一次「扫码授权」，多一步无谓操作。
 */
@Composable
private fun AuthStatusHeroCardHost() {
    val authorized = SettingsState.credPresent
    AuthStatusHeroCard(
        authorized = authorized,
        onClick = {
            if (!authorized) {
                authController.startAuth()
                SettingsState.authDialogFlow = true
                SettingsState.showAuthDialog = true
            } else {
                SettingsState.authDialogFlow = false
                SettingsState.showAuthDialog = true
            }
        },
    )
}

/** 顶部提示条文案（§4.1 提示条表；优先级 桥不可用 > 关闭）。
 *  2026-11-10 用户拍板：**删除未授权分支的「尚未授权，请先完成扫码授权后使用」**——
 *  授权信息已由首页最上方的授权状态大卡（未授权态红卡 +「点击卡片授权」）完整表达，
 *  大卡下方再挂一行同义提示属重复信息。该分支改为 `emptyList()`，
 *  `NoticeBanner` 对空列表直接 return（`BhWidgets.kt` 实证），故未授权时**整条提示条不渲染**。
 *  其余两态（桥不可用 / 模块已关闭）的提示逐字保留，不受影响。 */
private fun homeNoticeLines(): List<String> = when {
    !SettingsState.bridgeAvailable ->
        // 2026-11-23 用户指定文案：原「请先打开一次比亚迪官方 App 以激活模块」已不准确 ——
        // 模块没被激活时，打开多少次官方 App 都没用，真正要做的是
        // LSPosed 里启用模块 + 勾作用域 + 给 root 授权。
        listOf(
            "请在LSPosed框架激活模块、勾选作用域，并前往root管理器授予模块root权限",
            "当前仅可查看，配置与授权暂不可用",
        )
    !SettingsState.enabled ->
        listOf("模块已关闭，不会拦截或下发任何指令")
    !SettingsState.credPresent -> emptyList()  // 2026-11-10 按用户要求删除该提示行
    else -> emptyList()  // EXPIRING 文案待宿主回传通道（架构 A：T04 无通道不出现）
}

/** ① 合并卡：总开关（永不禁用，§6.1）+ 卡内 2 行单选模式（§12.1 A-3）。
 *  Step 2C 问题 4：删除卡内「模式」小标题行（用户明确推翻 18 定稿）。
 *
 *  **2026-11-11 改名**：主行 `自动上电` → **`APP解锁自动上电`**。
 *  理由：首页现在有两张同款方案卡（APP / NFC），只写「自动上电」无法区分是哪一套
 *  （`docs/28` §1.3 ⑦ / `docs/30` §5.10.6）。① 卡其余文案一字未动。 */
@Composable
private fun MergedCard() {
    BhCard {
        SwitchPreference(
            // 2026-11-23 用户要求：模块未激活（bridgeAvailable=false）时
            // **默认显示关闭且无法打开** —— 此时开关操作写不进任何地方，能点只会误导。
            // （原约定「总开关永不禁用」据此废止。）
            checked = SettingsState.bridgeAvailable && SettingsState.enabled,
            onCheckedChange = { v -> XposedServiceGate.writeEnabled(v) },
            title = "APP解锁自动上电",
            enabled = SettingsState.bridgeAvailable,
        )
        CardDivider()
        val editable = modeAndParamsEditable()
        // Step 2B：自绘 ModeRow → miuix RadioButtonPreference（0.9.4 已移除顶层
        // titleColor/summaryColor，颜色统一走官方工厂 + 主题语义 token）。
        RadioButtonPreference(
            title = "开门上电",
            summary = "解锁后窗口期内打开主驾门上电",
            selected = SettingsState.mode == "M1",
            onClick = { XposedServiceGate.writeMode("M1") },
            colors = RadioButtonPreferenceDefaults.radioButtonPreferenceColors(),
            radioButtonLocation = RadioButtonLocation.Start,
            enabled = editable,
        )
        RadioButtonPreference(
            title = "解锁上电",
            summary = "解锁后自动上电",
            selected = SettingsState.mode == "M2",
            onClick = { XposedServiceGate.writeMode("M2") },
            colors = RadioButtonPreferenceDefaults.radioButtonPreferenceColors(),
            radioButtonLocation = RadioButtonLocation.Start,
            enabled = editable,
        )
    }
}

/**
 * ①-N NFC 方案卡（`docs/30` §5.10.1）。
 *
 * **与 ① 卡严格同款**：同一套 `BhCard` + `SwitchPreference` + `RadioButtonPreference`，
 * 视觉规格逐项沿用（圆角 / 间距 / 行高 / 分隔线 / 单选样式 / Ink 主色）。
 * **唯一差异 = 主行文案 + 显示条件**（`docs/18` §0-A 定稿）；
 * 22:0x 拍板撤销了「卡内加触发卡行」的方案 —— **选卡在设置页的独立卡里**（见 `NfcCardPicker.kt`），
 * 所以本卡内**不得再加任何控件**。
 *
 * 文案与 ① **完全一致**（2026-11-12 00:0x 用户改首页文案后，两卡四行小字逐字相同）：
 * `开门上电` → 「解锁后窗口期内打开主驾门上电」、`解锁上电` → 「解锁后自动上电」。
 * 旧版 NFC 卡的 M1 副标题写的是「刷卡后…」（强调触发动作是刷卡），
 * 现统一成「解锁后…」——刷卡本身就是解锁车门，语义成立，且两张卡视觉/文案完全同款。
 */
@Composable
private fun NfcSchemeCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    BhCard {
        SwitchPreference(
            checked = SettingsState.nfcEnabled,
            onCheckedChange = { v ->
                // v3.1.6：未选触发卡时不允许打开（R-AID-7：没有判据可比，刷卡必被忽略）。
                // 直接禁掉开关会让用户不知道为什么点不动，所以用「可点 + toast 指引」。
                if (v && SettingsState.nfcSelectedAid.isEmpty()) {
                    android.widget.Toast.makeText(
                        ctx, "请前往设置页选择NFC触发卡", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    XposedServiceGate.writeNfcEnabled(v)
                }
            },
            title = "NFC解锁自动上电",
            // 开关永不禁用：与 ① 卡一致（`docs/18` §6.1）。桥不可用时开关仍可点，
            // 只是写不进配置 —— 此时列表已有「请先打开一次比亚迪官方 App」提示条兜底。
            enabled = true,
        )
        CardDivider()
        // 用 NFC 自己的门控：APP 方案开关关闭不应影响 NFC 卡的模式单选
        val editable = nfcModeEditable()
        RadioButtonPreference(
            title = "开门上电",
            summary = "解锁后窗口期内打开主驾门上电",
            selected = SettingsState.nfcMode == "M1",
            onClick = { XposedServiceGate.writeNfcMode("M1") },
            colors = RadioButtonPreferenceDefaults.radioButtonPreferenceColors(),
            radioButtonLocation = RadioButtonLocation.Start,
            enabled = editable,
        )
        RadioButtonPreference(
            title = "解锁上电",
            summary = "解锁后自动上电",
            selected = SettingsState.nfcMode == "M2",
            onClick = { XposedServiceGate.writeNfcMode("M2") },
            colors = RadioButtonPreferenceDefaults.radioButtonPreferenceColors(),
            radioButtonLocation = RadioButtonLocation.Start,
            enabled = editable,
        )
    }
}



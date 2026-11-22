package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.bydhooker.autostart.data.XposedServiceGate
import com.bydhooker.autostart.ui.auth.AuthController
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 设置 Tab（Step 2G，2026-11-09 用户拍板：原「授权」页更名为「设置」页）。
 *
 * **改动记录**：
 *  - 页面名：`授权` → `设置`（顶部标题与底栏标签同步）；
 *  - 2026-11-10：**去掉「授权信息」卡片与小标题**（用户逐字指定）——
 *    授权时间 / 车型 / 车牌已全部移到首页授权状态大卡上（见 `AuthHeroCard.kt`）；
 *  - 2026-11-10（**本轮**）：**车况卡片与「获取车况」按钮迁到首页**
 *    （用户指定「原本设置页的车况卡片和获取按钮挪到首页」，实现见 `VehicleStatus.kt`）；
 *    **参数卡从首页迁入本页**（用户指定「参数卡片挪到设置页」）——
 *    参数是「怎么用」的细节配置，放设置页更合适；本页后续还会继续加其它卡片。
 */
@Composable
fun SettingsTab(topInset: Dp = 0.dp) {
    LazyColumn(
        Modifier.fillMaxWidth(),
        // Step 2L：卡片间距对标 KernelSU（spacedBy(12.dp)）。
        // 2026-11-21：底栏改回悬浮玻璃胶囊（用户指定 IOS-LIKE）→ 底部留白恢复 96.dp，
        // 避免最后一张卡被胶囊压住；顶部 topInset = 玻璃顶栏高度，让内容能滚到顶栏后面。
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Step 2Q：顶部留白 12.dp（与首页一致）。
        contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 24.dp),
    ) {
        // 参数区（**2026-11-10 从首页迁入**）。
        //
        // **2026-11-10 用户拍板**：改成「解锁上电」后**参数卡也不隐藏** ——
        // 即 M1 / M2 两种模式都展示本卡。此前按 `15` §491 / §586 Q6（选 B）
        // 「M2 不暴露任何参数」只在 M1 展示；现按用户要求改为**始终展示**，
        // 由各行小字自行说明适用范围（见下）。
        // 注：**M2 的执行仍不读这三个键**（`15` §4.2 / §13.5：M2 用固定常量
        // `M2_STATUS_MAX_AGE_SEC = 30`），本卡在 M2 下仅作**展示与说明**，
        // 修改这些值**不改变 M2 行为**（改回 M1 时生效）——此点已在小字中如实说明。
        item { ParamsCard() }
        // 电源键三击（`docs/32`，2026-11-12 23:4x 用户要求「在设置页新增一个开关」）。
        // **单独一张卡**：它既不属于"窗口怎么跑"（参数卡），也不属于"应用信息"。
        // 卡内只有一行开关 —— 上电方式不在这里选，直接跟随首页「APP解锁自动上电」的配置
        // （2026-11-12 23:4x 用户明确要求「不再让用户额外选择」）。
        item { TriplePowerKeyCard() }
        // 应用信息卡（**2026-11-10 新增**）：模仿 KernelSU 设置页的信息卡形态，
        // 展示应用版本 / 比亚迪 App 版本 / 车辆型号。放在参数卡之后。
        item { AppInfoCard() }
        // 「支持与关于」卡（**2026-11-10 新增**）：追踪更新 / 支持开发 / 了解本APP，
        // 形态对标 KernelSU 截图底部的「支持开发 / 了解 KernelSU」卡（实现见 `SupportCard.kt`）。
        // 三行**本次不绑定点击事件**（`onClick = null`），保留右箭头作为视觉预告。
        item { SupportCard() }
        // 【2026-11-21】悬浮玻璃底栏避让：底部 padding 收到 24.dp 后（官方写法，内容可穿透），
        // 末尾补 48.dp 占位（+ spacedBy 12 = 60）让「了解本APP」等行不被胶囊压住。
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/**
 * 参数区（**2026-11-10 从首页迁入设置页**；`15` §6.2 键；
 * 滑块步长 1 秒、仅两端范围值；隐藏≠清除）。
 *
 * Step 2C 问题 2：`colors` 不再显式传 `surface`，交回 miuix 默认（surfaceContainer）。
 */
@Composable
private fun ParamsCard() {
    val editable = modeAndParamsEditable()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cornerRadius = 16.dp,
        insideMargin = CardDefaults.InsideMargin,
        colors = CardDefaults.defaultColors(),
    ) {
        SliderRow(
            label = "等待窗口",
            valueSec = SettingsState.windowSeconds,
            // 2026-11-22 用户要求：上限 180 s → **120 s**。
            range = 10..120,
            // 2026-11-11：补「刷卡」—— NFC-M1 与 APP-M1 共用同一个窗口实例，
            // 刷卡触发也会顺延同一个截止时间（`docs/28` §1.3.1 ②），旧文案只说「解锁」会失真。
            gray = "开门上电模式下，触发后检测开门信号的时长；再次解锁或刷卡只顺延截止时间",
            enabled = editable,
            // 2026-11-22 用户要求：步长 10 秒 + 带点样式；上限已降到 120 s
            // ⇒ 10..120 / 10 = 12 档，13 个点（含两端），间距比 180 s 时更宽松。
            step = 10,
            showKeyPoints = true,
            onChange = { SettingsState.windowSeconds = it },
            onCommit = { XposedServiceGate.writeWindowSeconds(it) },
            readLatest = { SettingsState.windowSeconds },
        )
        CardDivider()
        SliderRow(
            label = "轮询间隔",
            valueSec = SettingsState.pollIntervalSec,
            // 2026-11-10 用户拍板：上限 10 s → **3 s**。
            // 理由（用户原话仅「滑块最长只能是 3s」，此处补充工程含义）：
            // 窗口最长 120 s（2026-11-22 由 180 s 下调），间隔 3 s 时最坏 40 次请求
            // （原 10 s 为 12 次），但比 1 s（120 次）仍低得多 → 在「发现更快」与
            // 「限流风险」之间取中间值。
            // watch 通道的限流阈值项目内**【不知道】**（`01` §14 待确认），
            // 故本值取用户拍板的保守侧，不擅自放宽。
            range = 1..3,
            gray = "越短发现开门越快",
            enabled = editable,
            // 2026-11-21 用户要求「轮询间隔的 slider 改为带点的样式」。
            //
            // 用 miuix 0.9.5-beta02 官方的 `showKeyPoints`（不是自绘刻度）：
            // 反编译 `SliderKt` 实证点位由 `stepsToTickFractions(steps)` 自动生成，
            // `steps = (3-1)-1 = 1` ⇒ 生成 3 个刻度点（1s / 2s / 3s）落在正确位置；
            // 点半径 / 点色由 `SliderDefaults.KeyPointRadius` 与 `SliderColors` 决定，
            // 全部走官方默认值，不自造尺寸。其余两条滑块保持无点（取值范围大，点会挤成一排）。
            showKeyPoints = true,
            onChange = { SettingsState.pollIntervalSec = it },
            onCommit = { XposedServiceGate.writePollIntervalSec(it) },
            readLatest = { SettingsState.pollIntervalSec },
        )
        CardDivider()
        SliderRow(
            label = "数据龄上限",
            valueSec = SettingsState.dataAgeLimitSec,
            range = 10..60,
            gray = "车况数据超过此秒数视为陈旧，本帧不参与判定",
            enabled = editable,
            // 2026-11-22 用户要求：步长 10 秒（10..60 ⇒ 6 档）+ 带点样式。
            // 6 个点位（10/20/30/40/50/60）间距宽松，不会挤成一排。
            step = 10,
            showKeyPoints = true,
            onChange = { SettingsState.dataAgeLimitSec = it },
            onCommit = { XposedServiceGate.writeDataAgeLimitSec(it) },
            readLatest = { SettingsState.dataAgeLimitSec },
        )
        // NFC 触发卡（**2026-11-11 23:2x 用户要求**）：从「独立卡片 + 单选列表」改为
        // **本卡内的一行下拉**（`OverlaySpinnerPreference`），不要区块标题 —— 与其它设置行统一。
        // 分隔线在本函数内部随行一起出（未就绪时**连分隔线都不画**，否则卡底会多一条悬空横线）。
        // 放在最后一个滑块之后：三条滑块是「窗口怎么跑」，这一行是「哪张卡触发」，同属 NFC 配置。
        NfcCardSpinnerRow()
    }
}

/**
 * 滑块单元（Step 2B：自绘 Slider/BhText 三行 → miuix `SliderPreference`）。
 * `valueText` 显示「N 秒」、`summary` 承载原灰字解释、`hapticEffect = Step`；
 * `onValueChange` 本地乐观更新 SettingsState，`enabled=false` 用组件自带 disabled 语义
 * （不再叠加 disabledCardAlpha() 手工 alpha）。
 *
 * ★ 22 §5 / 23 §12.1 关键陷阱：SliderPreference 内部虽对「回调本身」做了
 * rememberUpdatedState，但 lambda 若捕获组合时的局部 `valueSec`，快速拖动后松手
 * 仍会提交旧值。因此 `onValueChangeFinished` 绝不捕获 valueSec，而是在回调触发
 * 瞬间通过 [readLatest] 读取 SettingsState.* 快照值 —— onValueChange 已同步写入该
 * 状态，所以 N 恒等于松手瞬间 UI 显示值（§12.3 第 5 条验收）。
 */
@Composable
private fun SliderRow(
    label: String,
    valueSec: Int,
    range: IntRange,
    gray: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    readLatest: () -> Int,
    showKeyPoints: Boolean = false,
    /**
     * 调整步长（秒）。2026-11-22 用户要求「等待窗口」与「数据龄上限」改为 **10 秒一档**。
     *
     * 注意两件事：
     *  ① `steps` 是 Compose Slider 的「**中间**可取值个数」（不含两端），
     *     所以 `steps = 区间长度 / step - 1`：10..180 / 10 ⇒ 16；10..60 / 10 ⇒ 4；1..3 / 1 ⇒ 1。
     *  ② 历史值可能是旧步长（1s）下的任意整数（例如 47），落在 10s 网格之外。
     *     Slider 的 value 若不在网格上会出现「滑块位置与显示值不一致」，
     *     故统一用 [snapToStep] 吸附到最近档位（显示、回调、提交三处都用吸附后的值）。
     */
    step: Int = 1,
) {
    val shown = snapToStep(valueSec, range, step)
    SliderPreference(
        value = shown.toFloat(),
        onValueChange = { onChange(snapToStep(it.toInt(), range, step)) },
        title = label,
        summary = gray,
        valueText = "$shown 秒",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        onValueChangeFinished = { onCommit(snapToStep(readLatest(), range, step)) },
        enabled = enabled,
        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
        // 2026-11-21：点位显示（官方 `showKeyPoints`）。点位来自 steps 自动换算，
        // 不需要传 `keyPoints` 自定义值；`keyPoints` 留给「刻度不落在 steps 上」的场景。
        showKeyPoints = showKeyPoints,
    )

}

/**
 * 把秒值吸附到最近的 step 档位（四舍五入），并夹在区间内。
 * 例：`snapToStep(47, 10..60, 10) = 50`、`snapToStep(12, 10..180, 10) = 10`。
 */
private fun snapToStep(value: Int, range: IntRange, step: Int): Int {
    if (step <= 1) return value.coerceIn(range.first, range.last)
    val clamped = value.coerceIn(range.first, range.last)
    val index = ((clamped - range.first).toFloat() / step).roundToInt()
    return (range.first + index * step).coerceIn(range.first, range.last)
}

/**
 * 「三击电源键解锁」卡（`docs/32`）。
 *
 * 【只有一行开关，没有模式选择】
 * 三击先下发真正的**解锁指令**（watch 通道 `OPENDOOR`，零界面），
 * 解锁受理后**复用首页「APP解锁自动上电」的配置**继续上电 ——
 * 总开关关 ⇒ 只解锁不上电；M1 ⇒ 开 60 秒窗口等拉门；M2 ⇒ 立即上电。
 * 因此这里不再给用户第二个"上电方式"选项（2026-11-12 23:4x 用户明确要求）。
 *
 * 【未就绪时不禁用开关，但如实说明】
 * system_server 的 Hook 是否装上，模块 App 只能靠 `/data/system/bydhk-triple.ready` 判断。
 * 未就绪时**仍允许打开开关**（先把配置写好），但要告诉用户「需要重启才生效」——
 * 比灰掉更实用：用户可以在同一个流程里「打开开关 → 重启 → 直接用」。
 */
@Composable
private fun TriplePowerKeyCard() {
    BhCard {
        SwitchPreference(
            checked = SettingsState.tripleEnabled,
            onCheckedChange = { v -> XposedServiceGate.setTripleEnabled(v) },
            title = "三击电源键解锁",
            summary = if (SettingsState.tripleHookReady) {
                "连按三次电源键解锁车门；解锁后按「APP解锁自动上电」的设置继续"
            } else {
                "尚未接入系统框架：需在 LSPosed 里给本模块勾选「系统框架」并重启手机"
            },
            enabled = true,
        )
    }
}


/**
 * 授权控制器单例。
 *
 * 位置说明（2026-11-10）：原先本文件末尾还有整段诊断查询链路（已随车况区块
 * 迁到 `VehicleStatus.kt`）。`authController` 保留在此处 —— 它是**授权流程**的入口，
 * 与设置页语义相关，且被 `HomeTab.kt` 的授权大卡点击逻辑跨文件引用。
 */
val authController = AuthController()

package com.bydhooker.autostart.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 车况区块（**2026-11-10 从设置页迁到首页**）。
 *
 * **迁移背景**：用户指定「原本设置页的车况卡片和获取按钮挪到首页」。
 * 车况是用户最常看的运行态信息（主驾门开合 / 门锁 / 上下电 / 数据新鲜度），
 * 放在首页合并卡之后可直接看到，不必切到设置页。
 *
 * 本文件承载整块「车况」相关内容：
 *  - [VehicleStatusCard] —— 车况卡片（默认展示）
 *  - [VehicleStatusButton] —— 「获取车况」按钮（**与卡片分离**，独立放在卡片下方）
 *  - [runVehicleStatusQuery] —— 只读查询链路（**零下发**，见文件末尾说明）
 *
 * **既有约定全部保持不变**（迁移只改位置，不改行为）：
 *  ① 卡片**默认展示**，不等到查询成功才出现；
 *  ② 按钮**与卡片分离**，独立放在卡片下方；
 *  ③ 未获取时各行值位写「未获取」，**不再另起灰色横条**；
 *  ④ 失败信息用 `surfaceContainerHighest` 条保留（成功时不显示「查询成功」）；
 *  ⑤ 档位 / 车辆状态**文字化**，原值以括注保留（见 [vehicleStateText]）。
 */
/**
 * 车况卡片（KernelSU InfoCard 式信息行：图标 + 标题 + 副文本）。
 *
 * 行语义来自官方 `BasicComponent` 的 **title / summary 两槽** —— 值在标题下方，
 * 与 KernelSU `HomeMiuix.kt:478-513` 的信息行同构。
 */
@Composable
fun VehicleStatusCard() {
    val f = SettingsState.diagFields
    // 区分「未查询 / 查询中 / 已有结果」三态。
    val hasData = f.isNotEmpty()
    val message = SettingsState.diagMessage
    // 失败文案统一以「查询失败」开头（查询链路内三处失败分支）→ 精确判定。
    val failed = message != null && message.startsWith("查询失败")
    val lfdText = if (!hasData) "未获取" else (f["lfd"]?.let { when (it) {
        "0" -> "关"; "1" -> "开"; else -> "未知（$it）" } } ?: "—")
    val lockText = if (!hasData) "未获取" else (f["lockFL"]?.let { when (it) {
        "2" -> "已锁"; "1" -> "未锁"; else -> "未知（$it）" } } ?: "—")
    BhCard {
        // 结果区：**始终渲染**。未获取到车况时，各行值直接写「未获取」——
        // 原先是值位显示「—」+ 卡片底部另起一条灰色「未获取车况」横条，
        // 真机观感是同一件事说了两遍、且那条灰条把卡片撑高；现改为**只在值位表达**，
        // 灰条删除（失败信息仍需保留，见下）。
        IconValueRow(MiuixIcons.Timer, "数据年龄",
            when {
                !hasData -> "未获取"
                // 服务端没给时间戳（time=0）：如实说不知道，不拿 epoch 差值冒充年龄
                f["ageSec"] == "time=0" -> "未知（服务端未返回时间）"
                else -> f["ageSec"]?.let { "${it} 秒" } ?: "—"
            })
        IconValueRow(MiuixIcons.Home, "主驾门", lfdText)
        IconValueRow(MiuixIcons.Lock, "门锁", lockText)
        // 档位 / 车辆状态：原本是两个裸数字行（截图里是「档位 1」「车辆状态 2」），
        // 现**合并为一行「车辆状态」**并文字化，理由：
        //  ① 实测中 `powerGear` 与 `vehicleState` **恒同现**（01:26 上电 = (3, 0)，
        //     02:03 退出上电 = (1, 2)），拆成两行、且都文字化后必然显示同一句话；
        //  ② 原始数值不丢 —— 以括注形式保留在文字后面，排障仍能看到原值。
        // 映射依据（**不编造**）：`powerGear == 3` = 已上电是**实测确认**的上电判据；
        // `1` = 未上电来自上述配对观测；`0` 与 `vehicleState == 1` 的语义项目内
        // 【不知道】（`01` §14 待确认项 #14）→ 一律显示「未知（原值）」。
        IconValueRow(MiuixIcons.Info, "车辆状态", if (hasData) vehicleStateText(f) else "未获取")
        // 状态行：**只在查询失败时渲染**（未查询的「未获取」已在各行值位表达，
        // 不再另起一条灰条重复；成功时同样不显示「查询成功」——用户拍板）。
        if (failed) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MiuixTheme.colorScheme.surfaceContainerHighest,
            ) {
                BhText(
                    text = message.orEmpty(),
                    slot = BhTextStyle.Footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 车况查询按钮：**与卡片分离**、独立放在卡片下方；
 * 文案 `获取车况` / `获取中…` / `重新获取`，官方主色（`buttonColorsPrimary()`）。
 */
@Composable
fun VehicleStatusButton() {
    val diagEnabled = SettingsState.bridgeAvailable && SettingsState.enabled &&
        SettingsState.credPresent
    val hasData = SettingsState.diagFields.isNotEmpty()
    val querying = SettingsState.diagMessage == DIAG_QUERYING
    Button(
        onClick = { runVehicleStatusQuery() },
        enabled = diagEnabled && !querying,
        // 卡片自身左右各留 16.dp（`BhCard` 内部），独立按钮同样对齐这条基准线。
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        BhText(
            text = when {
                querying -> "获取中…"
                hasData -> "重新获取"
                else -> "获取车况"
            },
            slot = BhTextStyle.Button,
            color = MiuixTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 车辆状态文案：**文字化**表达，不裸显数字。
 *
 * 依据（不编造）：
 *  · `powerGear == 3` → 已上电 —— **实测确认**（也是上电判据 `15` G-1）；
 *  · `powerGear == 1` → 未上电 —— 来自配对观测（01:26 上电时 `powerGear=3`/`vehicleState=0`，
 *    02:03 退出上电态后 `powerGear=1`/`vehicleState=2`，见 `00` §M-2）；
 *  · `powerGear == 0` 与 `vehicleState == 1` 的语义项目内**【不知道】**
 *    （`01` §14 待确认项 #14）→ 显示「未知（原值）」，**不猜测**。
 */
private fun vehicleStateText(f: Map<String, String>): String {
    val gear = f["gear"]
    val state = f["vs"]
    // 只认**实测确认**的 powerGear 映射（Models.kt:13：3=已上电【实测】；1=未上电配对观测）。
    // 2026-11-12 23:0x 修：原实现还会用 vehicleState 兜底猜（`0`→已上电 / `2`→未上电），
    // 真机出现 `gear=0 + vs=0` 时显示「已上电」——但那时车根本没上电，
    // 而 Models 里 `powerGear=0` 的语义本身是【不知道】。按「不知道的不胡说」，
    // 不认识的取值一律显示原始值，不再推测。
    val word = when (gear) {
        "3" -> "已上电"
        "1" -> "未上电"
        else -> null
    }
    val raw = listOfNotNull(gear, state).joinToString("/")
    return when {
        word != null && raw.isNotEmpty() -> "$word（原值 $raw）"
        word != null -> word
        raw.isNotEmpty() -> "未知（原值 $raw）"
        else -> "—"
    }
}

/**
 * 图标 + 标题 + 副文本（对齐 KernelSU `InfoCard` 的信息行形态）。
 *
 * KernelSU `HomeMiuix.kt:478-513` 的信息行 = `Icon(24dp)` + `title`(headline1 Medium)
 * + `content`(body2 灰，行距 24dp)。这正好就是官方 `BasicComponent` 的
 * **title / summary 两槽**语义 —— 值放 `summary`（标题下方），而不是 `endActions`（行内右对齐）。
 */
@Composable
private fun IconValueRow(icon: ImageVector, label: String, value: String) {
    BasicComponent(
        title = label,
        summary = value,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        },
    )
}

/**
 * 车况查询中标记：UI 据此切按钮文案与禁用态，避免在两处硬编码同一字符串。
 * 查询链路本身未改（仍是零下发的 getStatusNow 只读请求）。
 */
private const val DIAG_QUERYING = "正在查询…"

/**
 * 车况查询链路：**直查**（本进程有 Whitebox + remote prefs 凭据，架构 A 既成事实），
 * 直接发 getStatusNow 只读请求——**零下发**；宿主实时推送不可靠（当日实测），不经宿主。
 * 在 io 线程执行。
 */
private fun runVehicleStatusQuery() {
    SettingsState.diagMessage = DIAG_QUERYING
    SettingsState.diagFields = emptyMap()
    Thread({
        try {
            val cred = com.bydhooker.autostart.data.XposedServiceGate.readCredential()
            if (cred == null) {
                SettingsState.diagMessage = "凭据未就绪，请重新授权"
                return@Thread
            }
            val f = com.bydhooker.autostart.crypto.WatchRequest.asStatusSource().fetch(cred)
            if (f == null) {
                SettingsState.diagMessage = "查询失败（网络或服务端）"
            } else {
                // ⚠️ 服务端**可能返回 time=0**（2026-11-12 23:0x 真机：点「获取车况」显示
                // 「数据年龄 1794495801 秒」= 当前 epoch 秒）。此时 age 没有意义，
                // 直接算出来会显示成十几亿秒，把「数据未知」伪装成「数据极旧」。
                val age = if (f.timeSec > 0) {
                    System.currentTimeMillis() / 1000 - f.timeSec
                } else {
                    -1L
                }
                SettingsState.diagFields = mapOf(
                    "ageSec" to if (age >= 0) age.toString() else "time=0",
                    "lfd" to (f.leftFrontDoor?.toString() ?: ""),
                    "lockFL" to (f.doorLockFL?.toString() ?: ""),
                    "gear" to (f.powerGear?.toString() ?: ""),
                    "vs" to (f.vehicleState?.toString() ?: ""),
                )
                SettingsState.diagMessage = "查询成功"
                // 车型/车牌补全：凭据早于 getVehicleInfo 功能时为空 → 顺手补齐并落盘
                if (SettingsState.credAutoType.isEmpty()) {
                    try {
                        val vi = com.bydhooker.autostart.crypto.WatchQrAuth.getVehicleInfo(
                            encryToken = cred.encryToken,
                            signToken = cred.signToken,
                            wi = cred.watchImei,
                            identifier = cred.identifier,
                            identifierType = cred.identifierType,
                            vin = cred.vin,
                            vehicleBrand = cred.vehicleBrand,
                            appChannel = cred.appChannel,
                        )
                        if (vi != null) {
                            val at = com.bydhooker.autostart.crypto.WatchQrAuth.deepFind(vi, "autoType")
                            val ap = com.bydhooker.autostart.crypto.WatchQrAuth.deepFind(vi, "autoPlate")
                            val tv = com.bydhooker.autostart.crypto.WatchQrAuth.deepFind(vi, "tboxVersion")
                            com.bydhooker.autostart.core.EventLog.info(
                                "AUTH", "vehicleInfo 解析：autoType=" + (at ?: "null")
                                    + " autoPlate=" + (ap?.let { "***" } ?: "null")
                                    + " tbox=" + (tv ?: "null"))
                            val extras = buildMap {
                                at?.let { put(com.bydhooker.autostart.bridge.BridgeContract.KEY_CRED_AUTO_TYPE, it) }
                                ap?.let { put(com.bydhooker.autostart.bridge.BridgeContract.KEY_CRED_AUTO_PLATE, it) }
                                tv?.let { put(com.bydhooker.autostart.bridge.BridgeContract.KEY_CRED_TBOX_VERSION, it) }
                            }
                            if (extras.isNotEmpty()) {
                                com.bydhooker.autostart.data.XposedServiceGate.persistCredentials(extras)
                                SettingsState.credAutoType = at ?: ""
                                SettingsState.credAutoPlate = ap ?: ""
                            }
                        } else {
                            com.bydhooker.autostart.core.EventLog.warn(
                                "AUTH", "vehicleInfo 无响应（send 失败或 respondData 缺失）")
                        }
                    } catch (t: Throwable) {
                        com.bydhooker.autostart.core.EventLog.warn(
                            "AUTH", "vehicleInfo 异常：" + t.javaClass.simpleName)
                    }
                }
            }
        } catch (t: Throwable) {
            SettingsState.diagMessage = "查询失败：" + t.javaClass.simpleName
        }
    }, "BYDHK-Diag").apply { isDaemon = true; start() }
}

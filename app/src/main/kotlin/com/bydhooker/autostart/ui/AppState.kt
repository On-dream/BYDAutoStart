package com.bydhooker.autostart.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bydhooker.autostart.bridge.BridgeContract

/**
 * 设置页全局状态（架构 A：授权流程在设置页执行 → 本页即状态源；
 * 宿主不回传实时状态（框架无通道，`18` §0.2 变更注记）→
 * 状态卡 = 配置回显 + 授权页自身状态口径）。
 *
 * 用 Compose 快照状态（线程安全）：XposedServiceGate 回调线程写、UI 帧读。
 * 存 object 单例以满足 `18` §8「切后台/旋转后状态保持」。
 */
enum class AuthUi { NONE, CREATING, WAITING_SCAN, SCANNED, CONFIRMING, AUTHORIZED, FAILED }

object SettingsState {

    // ---- 桥 / 服务（架构 A：XposedServiceHelper → RemotePreferences）----
    var bridgeAvailable: Boolean by mutableStateOf(false)

    // ---- 配置（`15` §6.2 键；轮询间隔 UI 用秒、持久化 ms）----
    var enabled: Boolean by mutableStateOf(true)
    var mode: String by mutableStateOf("M1")            // 内部值 M1/M2；上屏去代号（§12.6）
    var windowSeconds: Int by mutableStateOf(BridgeContract.DEF_WINDOW_SECONDS)
    var pollIntervalSec: Int by mutableStateOf(BridgeContract.DEF_POLL_INTERVAL_MS / 1000)
    var dataAgeLimitSec: Int by mutableStateOf(BridgeContract.DEF_DATA_AGE_LIMIT_SEC)

    // ---- NFC 方案（`docs/30` §3：与 APP 方案各带独立开关 + 独立模式）----
    /** NFC 方案开关。**独立于 [enabled]**（后者是 APP 方案开关，14:2x 拍板后不再是总开关）。 */
    var nfcEnabled: Boolean by mutableStateOf(false)
    /** NFC 方案模式：内部值 M1/M2；上屏去代号。 */
    var nfcMode: String by mutableStateOf("M1")
    /** 选中的触发卡（三键同源：aid 判据 / cid 随卡落盘 / name 显示）。 */
    var nfcSelectedAid: String by mutableStateOf("")
    var nfcSelectedCid: String by mutableStateOf("")
    var nfcSelectedName: String by mutableStateOf("")

    /**
     * Hook 三层状态（L1 模块已加载 / L2 目标方法已找到 / L3 Hook 已装上）。
     * **首页 ①-N 卡的显示条件 = 三者全真**（`docs/29` §5.1 Q2=乙；L4 心跳不作门槛）。
     * 数据源 = 模块私有 SP（`NfcLocalState`，同进程直读），不走 remote prefs。
     */
    var nfcHookVisible: Boolean by mutableStateOf(false)

    /** 车钥匙卡列表（tsmclient 采集 → Provider → 本地 SP → 这里）。空 = 未采集到。 */
    var nfcCards: List<NfcCard> by mutableStateOf(emptyList())

    /** 「NFC 触发卡」选择弹窗是否可见（2026-11-11 23:4x：下拉行点击后弹出）。 */
    var showNfcCardPicker: Boolean by mutableStateOf(false)

    // ---- 电源键三击（`docs/32`，2026-11-12 23:4x）----
    /** 三击开关。默认关：首次注入绝不误触发车控。 */
    var tripleEnabled: Boolean by mutableStateOf(false)

    /**
     * system_server 侧三击 Hook 是否已就绪。
     *
     * 跨进程查不到 Hook 状态，只能看 system_server 写的 `/data/system/bydhk-triple.ready`。
     * 未就绪时开关仍可打开（写配置），但要如实告诉用户「重启后才生效」。
     */
    var tripleHookReady: Boolean by mutableStateOf(false)

    /**
     * 刷新三击状态。
     *
     * 开关读 `Settings.Global`（毫秒级，同步）；就绪标记要 `su cat`（几百毫秒），**异步**回主线程。
     * 调用时机：`SettingsActivity.onResume`。
     */
    fun reloadTripleState() {
        val ctx = appContext ?: return
        tripleEnabled = com.bydhooker.autostart.core.TripleSwitch.isEnabled(ctx)
        Thread({
            val ready = try {
                com.bydhooker.autostart.core.TripleSwitch.isHookReady()
            } catch (_: Throwable) {
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tripleHookReady = ready
            }
        }, "bydhooker-triple-state").apply { isDaemon = true }.start()
    }

    /**
     * 从本地 SP 重读 NFC 状态（Hook 三层 + 卡列表）。
     *
     * 调用时机：① `SettingsActivity.onResume`（宿主侧可能在此期间写过）；
     * ② Provider 写完 HOOK_STATE / CARDS 后（同进程内，直接刷）。
     * **失败静默**：拿不到 Context 就保持原值，不抛异常、不清空 UI。
     */
    fun reloadNfcLocal() {
        val ctx = appContext ?: return
        try {
            // v3.0.1：tsmclient 开机不自启 + 模块升级不重启它 ⇒ hook.ver 过期 ⇒ 卡被隐藏。
            // 检测到过期就 root 拉起它（后台线程 + 节流，见 TsmclientWaker），
            // 注入与三态上报完成后这里会被再次调用（或 8s 后 waker 主动刷）。
            com.bydhooker.autostart.data.TsmclientWaker.wakeIfStale(ctx)
            val lv = com.bydhooker.autostart.wake.NfcLocalState.readHookLevel(ctx)
            nfcHookVisible = lv[0] && lv[1] && lv[2]
            nfcCards = parseNfcCards(com.bydhooker.autostart.wake.NfcLocalState.readCardsJson(ctx))
            // 只记一次：Hook 未就绪时给排查留痕（`docs/30` §5.10.5 的 NFC_HOOK_MISS）。
            // **不上屏**——NFC 卡此时整卡隐藏，没有任何 UI 位可以承载这句提示。
            if (!nfcHookVisible && !nfcMissLogged) {
                nfcMissLogged = true
                com.bydhooker.autostart.core.EventLog.debug(
                    "NFC_HOOK_MISS",
                    "未检测到小米智能卡 Hook，NFC 功能不可用（L1=${lv[0]} L2=${lv[1]} L3=${lv[2]}）",
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** `NFC_HOOK_MISS` 每次进程只记一条，避免每次 onResume 刷日志。 */
    private var nfcMissLogged = false

    private fun parseNfcCards(json: String): List<NfcCard> {
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val aid = o.optString("aid", "")
                if (aid.isEmpty()) return@mapNotNull null
                NfcCard(aid = aid, cid = o.optString("cid", ""), name = o.optString("name", ""))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---- 授权（本页状态源；凭据键由本页写入 remote prefs）----
    var credPresent: Boolean by mutableStateOf(false)
    var credAcquireTs: Long by mutableStateOf(0L)
    var credAutoType: String by mutableStateOf("")      // 缺值显示 —（18 §11 #13）
    var credAutoPlate: String by mutableStateOf("")     // getVehicleInfo 实证补齐（2026-11-08）
    var auth: AuthUi by mutableStateOf(AuthUi.NONE)
    var authFailReason: String by mutableStateOf("")
    var qrRemainingSec: Long by mutableStateOf(0L)
    var qrPayload: String by mutableStateOf("")
    /** Application Context（用于授权完成后把设置页拉回前台）。 */
    var appContext: android.content.Context? = null         // 二维码内容（watchQRCode://…，真码渲染）

    // ---- 诊断 / 日志（架构 A 反向通道：宿主执行 → remote prefs → 本页读）----
    var diagMessage: String? by mutableStateOf(null)
    var diagFields: Map<String, String> by mutableStateOf(emptyMap())
    var logEntries: List<com.bydhooker.autostart.model.LogEntry> by mutableStateOf(emptyList())

    // ---- 弹窗 ----
    var showClearCredDialog: Boolean by mutableStateOf(false)
    var showClearLogDialog: Boolean by mutableStateOf(false)

    // Step 2G（2026-11-09）：授权弹窗。
    //  - showAuthDialog = 弹窗可见；
    //  - authDialogFlow = true 走「流程模式」（说明文本 + 二维码），
    //    false 走「选项模式」（已授权 → 清除凭据 / 重新授权 两个按钮）。
    var showAuthDialog: Boolean by mutableStateOf(false)
    var authDialogFlow: Boolean by mutableStateOf(false)

    // 2026-11-10：「支持开发」收款码弹窗（设置页 §支持卡第 2 行）。
    var showDonateDialog: Boolean by mutableStateOf(false)

    // ---- 日志导出（2026-11-10 用户需求：合并两端日志 → 系统文件管理器选位置）----
    /**
     * 触发导出的入口，由 `SettingsActivity.onCreate` 注入（内部持有
     * `ActivityResultLauncher<CreateDocument>`）。**为 null = Activity 尚未就绪**，
     * 日志页据此把按钮置灰，避免点了没反应。
     *
     * 放在这里而不是从 Compose 直接拿 launcher：本工程没有 activity-compose，
     * `rememberLauncherForActivityResult` 不可用；launcher 必须在 Activity
     * `onCreate` 里注册（早于 STARTED），只能由 Activity 自己持有再抛出来。
     */
    var exportLogs: (() -> Unit)? = null

    /** 导出进行中（防重复点击；按钮据此置灰）。 */
    var exporting: Boolean by mutableStateOf(false)

    // 2026-11-21：「了解本APP」二级页入口（设置页支持卡第 3 行）。
    //
    // 【从布尔开关改成回调注入 —— 为什么】
    // 原实现是 `showGuide: Boolean`，由 `SettingsActivity.Root()` 读它决定是否盖一层
    // 自绘覆盖层。2026-11-21 用户要求改成 **miuix-nav 真二级页**，页面栈归 `NavDisplay`
    // 所有，布尔开关无法表达「压栈/出栈」，也无法拿到官方转场动画与预测性返回。
    // 于是沿用本项目既有的注入约定（与上面的 `exportLogs` 完全同型）：
    // Activity 侧把「push 一个 Guide 路由」的动作抛进来，深层 UI 直接调用。
    // **null = Activity 尚未就绪**，此时点击应无动作（不置灰，避免用户困惑）。
    var openGuide: (() -> Unit)? = null

    /** 白盒组件行：架构 A 宿主不回传 → 恒「状态未知」灰 ○（18 §0.2 变更记）。 */
    const val WHITEBOX_TEXT = "状态未知"
}

/**
 * 模式 / 参数可用档（`18` §6.1 统一规则）：
 * 未授权 / 白盒失败 / 开关关闭 → 置灰；桥不可用 / EXPIRING / UNKNOWN / 宿主未注入 → 只读降级。
 *
 * **2026-11-10 移入本文件**：参数卡从首页迁到设置页后，本判定同时被
 * 首页合并卡（模式单选）与设置页参数卡（滑块）使用 → 跨文件共享，
 * 故提到 `SettingsState` 同文件，不再是 `HomeTab.kt` 的 private 函数。
 */
/**
 * 一张车钥匙卡（设置页「NFC 触发卡」卡的数据单元）。
 *
 * 字段来源：tsmclient 侧 `getProprietaryCarKeyCards(null)` → JSON → 本地 SP。
 * - [aid] 是**唯一判据**（事件层只能拿到 AID，见 `docs/29` §2.1），**严禁写死**；
 * - [cid] 卡实例级 ID，**不参与判定**（事件层拿不到）。2026-11-15 起 UI 也**不再展示
 *   它的尾号** —— 显示尾号会让人误以为只有那一张卡会触发，实际判据是产品级 AID。
 *   仍随选中卡落盘保留（三键原子提交的历史契约，见 `XposedServiceGate.writeNfcCard`）；
 * - [name] 仅显示，**不得作判据**（`mCardName` 用户可改）。
 */
data class NfcCard(
    val aid: String,
    val cid: String,
    val name: String,
) {
    /** 未取到卡名时的兜底显示（不显示空行）。 */
    val displayName: String get() = name.ifEmpty { "车钥匙卡" }
}

fun modeAndParamsEditable(): Boolean =
    SettingsState.bridgeAvailable && SettingsState.enabled && SettingsState.credPresent

/**
 * NFC 方案的模式单选是否可编辑。
 *
 * **不能用 [modeAndParamsEditable]**：它把 APP 方案的 `enabled` 也算进去了，
 * 但 14:2x 拍板后两张卡各带独立开关（`enabled` 只代表 APP 方案）。
 * 若沿用，用户关掉「APP解锁自动上电」后 NFC 卡的模式就跟着变灰 —— 逻辑上说不通。
 */
fun nfcModeEditable(): Boolean =
    SettingsState.bridgeAvailable && SettingsState.nfcEnabled && SettingsState.credPresent

/**
 * 「NFC 触发卡」卡是否可编辑。
 *
 * 只看桥 + 授权，**不看任何开关** —— 先选卡再开开关是正常操作顺序，
 * 把选卡也绑到 `nfcEnabled` 上会逼用户「先开开关才让选卡」。
 */
fun nfcCardPickable(): Boolean =
    SettingsState.bridgeAvailable && SettingsState.credPresent

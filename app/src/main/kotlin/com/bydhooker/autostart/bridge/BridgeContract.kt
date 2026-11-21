package com.bydhooker.autostart.bridge

/**
 * 跨进程契约常量（单一事实来源）。
 *
 * 数据流（裁定 G，架构 A，`research/P1前置验证/06` §9.1 / `07`）：
 * - **配置 / 授权 / 凭据**：设置页（模块 App，XposedServiceHelper）**写入** →
 *   宿主（本进程，XposedInterface.getRemotePreferences **只读**）经
 *   OnSharedPreferenceChangeListener 实时收取（框架原生 Diff 推送）。
 * - **宿主私有数据**（凭据密文 / 事件日志 / 状态）一律落宿主私有目录，
 *   **不经桥**（被 Hook 侧桥只读，宿主→设置页无通道）。
 *
 * 键名逐字取自 `15-程序设计.md` §6.2（`18` §1.4 裁定统一 `enabled`，
 * 不建 namespaced key）。字段子集见 06 §9.1：凭据由设置页经桥传入。
 */
object BridgeContract {

    /** Remote Preferences 组名（宿主与设置页共用同一组）。 */
    const val PREFS_GROUP = "bydhooker_main"

    /** Java 侧访问（XposedModuleEntry）。 */
    @JvmStatic
    fun getPrefsGroup(): String = PREFS_GROUP

    // ---- 配置键（`15` §6.2；P1-2 探针已真机验证同组读写）----
    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"
    const val KEY_WINDOW_SECONDS = "windowSeconds"
    const val KEY_POLL_INTERVAL_MS = "pollIntervalMs"
    const val KEY_DATA_AGE_LIMIT_SEC = "dataAgeLimitSec"
    const val KEY_DOOR_OPEN_VALUE = "doorOpenValue"
    const val KEY_CONNECT_TIMEOUT_MS = "connectTimeoutMs"
    const val KEY_READ_TIMEOUT_MS = "readTimeoutMs"
    const val KEY_VERIFY_DELAY_MS = "verifyDelayMs"
    const val KEY_VERIFY_RETRY = "verifyRetry"

    // ---- NFC 方案键（`docs/30` §3.1；2026-11-11 22:5x 新增）----
    //
    // 注意方向：`nfc.event.*` 是**模块 App 写、宿主读**（与配置键同向，都是模块写）。
    // tsmclient 侧绝不碰这些键（P0-NFC-7 已证被 Hook 侧写不了）。

    /** NFC 方案总开关（与 APP 方案的 `enabled` 独立）。默认 false。 */
    const val KEY_NFC_ENABLED = "nfcEnabled"

    /** NFC 方案模式："M1" / "M2"，默认 "M1"。 */
    const val KEY_NFC_MODE = "nfcMode"

    /** 用户选中的触发卡 AID（hex 小写）——**唯一事件判据**；空 = 不触发（R-AID-7）。 */
    const val KEY_NFC_SELECTED_AID = "nfc.selectedAid"

    /** 选中卡的 cid（仅用于 UI 区分同 AID 的同名卡，**不参与判定**）。 */
    const val KEY_NFC_SELECTED_CID = "nfc.selectedCid"

    /** 选中卡的显示名（仅 UI）。 */
    const val KEY_NFC_SELECTED_NAME = "nfc.selectedName"

    // ---- 凭据键（裁定 G：设置页 AuthFlow 前移后经桥传入；宿主收到后 Keystore 加密落私有）----
    const val KEY_CRED_WATCH_IMEI = "cred.watchImei"
    const val KEY_CRED_SIGN_TOKEN = "cred.signToken"
    const val KEY_CRED_ENCRY_TOKEN = "cred.encryToken"
    const val KEY_CRED_PASSWORD = "cred.passWord"
    const val KEY_CRED_VIN = "cred.vin"
    const val KEY_CRED_IDENTIFIER = "cred.identifier"
    const val KEY_CRED_IDENTIFIER_TYPE = "cred.identifierType"
    const val KEY_CRED_VEHICLE_BRAND = "cred.vehicleBrand"
    const val KEY_CRED_APP_CHANNEL = "cred.appChannel"
    const val KEY_CRED_APP_VERSION = "cred.appVersion"
    const val KEY_CRED_AUTO_TYPE = "cred.autoType"
    const val KEY_CRED_AUTO_PLATE = "cred.autoPlate"
    const val KEY_CRED_TBOX_VERSION = "cred.tboxVersion"
    const val KEY_CRED_ACQUIRE_TS = "cred.acquireTokenTimestamp"
    const val KEY_CRED_PRESENT = "cred.present"

    /** 全部桥键（listener 过滤用；任何未知键一律忽略）。 */
    val ALL_KEYS: Set<String> = setOf(
        KEY_ENABLED, KEY_MODE, KEY_WINDOW_SECONDS, KEY_POLL_INTERVAL_MS,
        KEY_DATA_AGE_LIMIT_SEC, KEY_DOOR_OPEN_VALUE, KEY_CONNECT_TIMEOUT_MS,
        KEY_READ_TIMEOUT_MS, KEY_VERIFY_DELAY_MS, KEY_VERIFY_RETRY,
        KEY_CRED_WATCH_IMEI, KEY_CRED_SIGN_TOKEN, KEY_CRED_ENCRY_TOKEN,
        KEY_CRED_PASSWORD, KEY_CRED_VIN, KEY_CRED_IDENTIFIER,
        KEY_CRED_IDENTIFIER_TYPE, KEY_CRED_VEHICLE_BRAND, KEY_CRED_APP_CHANNEL,
        KEY_CRED_APP_VERSION, KEY_CRED_AUTO_TYPE, KEY_CRED_AUTO_PLATE,
        KEY_CRED_TBOX_VERSION, KEY_CRED_ACQUIRE_TS, KEY_CRED_PRESENT,
        // NFC（`docs/30` §3.1）：架构 C 下只剩「选项 + 选中卡」，事件键已删
        KEY_NFC_ENABLED, KEY_NFC_MODE, KEY_NFC_SELECTED_AID, KEY_NFC_SELECTED_CID,
        KEY_NFC_SELECTED_NAME,
    )

    /**
     * **可以给宿主看的车辆信息**（`docs/31` 第 3 步）。
     *
     * 快速授权（`WatchKeyLoginHook`）构造 `DiLinkAutoInfo` 只需要这几个字段，
     * 它们官方 App 界面本来就显示，不构成凭据泄露。
     */
    val HOST_VISIBLE_CRED_KEYS: Set<String> = setOf(
        KEY_CRED_VIN, KEY_CRED_AUTO_TYPE, KEY_CRED_TBOX_VERSION,
        KEY_CRED_VEHICLE_BRAND, KEY_CRED_APP_CHANNEL,
    )

    /**
     * **只能留在模块进程的敏感凭据**（`docs/31` 第 3 步）。
     *
     * 这几个能直接发车控指令，写进 remote prefs 就等于把 token 交到另一个进程，
     * 一旦宿主被逆向/抓内存就全暴露。执行层搬走后宿主不再需要它们。
     */
    val SECRET_CRED_KEYS: Set<String> = setOf(
        KEY_CRED_WATCH_IMEI, KEY_CRED_SIGN_TOKEN, KEY_CRED_ENCRY_TOKEN,
        KEY_CRED_PASSWORD, KEY_CRED_IDENTIFIER, KEY_CRED_IDENTIFIER_TYPE,
    )

    /** 凭据类键（收到任一即触发 CredentialVault 重载）。 */
    val CRED_KEYS: Set<String> = setOf(
        KEY_CRED_WATCH_IMEI, KEY_CRED_SIGN_TOKEN, KEY_CRED_ENCRY_TOKEN,
        KEY_CRED_PASSWORD, KEY_CRED_VIN, KEY_CRED_IDENTIFIER,
        KEY_CRED_IDENTIFIER_TYPE, KEY_CRED_VEHICLE_BRAND, KEY_CRED_APP_CHANNEL,
        KEY_CRED_APP_VERSION, KEY_CRED_AUTO_TYPE, KEY_CRED_AUTO_PLATE,
        KEY_CRED_TBOX_VERSION, KEY_CRED_ACQUIRE_TS, KEY_CRED_PRESENT,
    )

    // ---- 固定常量（`15` §6.2 / §10.3；不进桥，代码内使用）----
    const val APP_VERSION = "570"
    const val KEYSTORE_ALIAS = "bydhooker.cred.v1"

    /** M2（解锁上电）内部新鲜度固定常量（用户 10:50 拍板 B：不读 dataAgeLimitSec）。 */
    const val M2_STATUS_MAX_AGE_SEC = 30

    // ---- 默认配置值 ----
    const val DEF_WINDOW_SECONDS = 60
    const val DEF_POLL_INTERVAL_MS = 1000
    const val DEF_DATA_AGE_LIMIT_SEC = 30
    const val DEF_DOOR_OPEN_VALUE = 1
    const val DEF_CONNECT_TIMEOUT_MS = 5000
    const val DEF_READ_TIMEOUT_MS = 8000
    /** 复核初延迟（B 优化：3000 → 1500；车实际 SEND 后 1~2 s 已上电） */
    const val DEF_VERIFY_DELAY_MS = 1500
    const val DEF_VERIFY_RETRY = 3

    /** 复核总上限（`15` §4.1 设计值 12 s；保留给 M1 未来使用）。 */
    const val DEF_VERIFY_TIMEOUT_MS = 12000

    /**
     * M2 复核总上限 20 s（实测修正）。
     *
     * `getStatusNow` 在 `leftFrontDoor` 缺失时走 `rtRequest` + 固定 sleep + `rtResult`
     * 两步（WatchRequest.kt:260-268），单次实际 2.5-3 s。按 12 s 上限 + 3 s 延时起查 +
     * 3 s 间隔，跑不完 3 次（实际只查 1-2 次），故 M2 单独放宽到 20 s。
     */
    /**
     * M2 复核窗口（2026-11-09 09:00 实测修正 20 s → 60 s）。
     *
     * 09:00 那次：指令 `code=0` 受理、车最终确实上电，但 20 s 内 `powerGear`
     * 连续 9 次采样都是 1（`age=0s`，数据新鲜）→ 车端执行远慢于 20 s，
     * 窗口太短导致误报 `VERIFY_PENDING`。拉长到 60 s 以测出真实「下发→上电」耗时。
     */
    const val DEF_VERIFY_TIMEOUT_MS_M2 = 60000

    // ---- 作用域宿主（唯一，`15` C-2）----
    const val HOST_PACKAGE = "com.byd.aeri.caranywhere"

    /** watch 通道 commandType（探针/车控 App【实测】；宿主 Hook 捕获用）。 */
    const val CMD_UNLOCK = "OPENDOOR"
    const val CMD_ENGINE_START = "RUNENGINENODRIVE"
}

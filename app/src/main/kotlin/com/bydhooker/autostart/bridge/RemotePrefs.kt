package com.bydhooker.autostart.bridge

import android.content.SharedPreferences
import com.bydhooker.autostart.core.CredentialVault
import com.bydhooker.autostart.core.EventLog

/**
 * 宿主侧 Remote Preferences **只读**访问（裁定 G：被 Hook 侧只读为框架设计，
 * `06` §9.1；P1-2 真机已验证「设置页写 → 宿主收」全链路）。
 *
 * 职责：
 * - 持有宿主侧只读 SharedPreferences 引用（XposedInterface.getRemotePreferences）
 * - 注册 OnSharedPreferenceChangeListener（框架 Diff 推送，无需重启——P1-2 实测）
 * - 把变更分派给：ConfigStore（配置键）/ CredentialVault（凭据键）
 */
object RemotePrefs {

    @Volatile private var prefs: SharedPreferences? = null

    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == null || !BridgeContract.ALL_KEYS.contains(key)) return@OnSharedPreferenceChangeListener
        if (BridgeContract.CRED_KEYS.contains(key)) {
            // 凭据键：收集当前组内全部凭据键后交给 CredentialVault（保证原子性）
            val snapshot = HashMap<String, Any?>(BridgeContract.CRED_KEYS.size)
            for (k in BridgeContract.CRED_KEYS) {
                snapshot[k] = sp.all[k]
            }
            snapshot[BridgeContract.KEY_CRED_PRESENT] = sp.all[BridgeContract.KEY_CRED_PRESENT]
            com.bydhooker.autostart.core.ModuleBridgeHost.onCredKeyChanged(snapshot)
        } else {
            ConfigStore.onRemoteChanged(key)
        }
    }


    /** 宿主进程内初始化（onPackageLoaded 后；幂等）。 */
    fun init(prefs: SharedPreferences?) {
        if (prefs == null || this.prefs != null) return
        this.prefs = prefs
        try {
            prefs.registerOnSharedPreferenceChangeListener(configListener)
            EventLog.info("BRIDGE", "宿主侧只读桥已注册（Remote Preferences，框架推送）")
            // 快照自愈（2026-11-08 真机缺口）：Listener 只推**变更**不推初值——
            // 宿主重启后凭据/配置若错过实时推送将永久丢失（授权当日实测命中）。
            // 主动读全量快照补齐：凭据组喂 CredentialVault、配置键喂 ConfigStore。
            val all = try { prefs.all } catch (_: Throwable) { null } ?: return
            val present = all[BridgeContract.KEY_CRED_PRESENT]
            val hasCred = BridgeContract.CRED_KEYS.any { all.containsKey(it) }
            if (present == false) {
                // ★ 2026-11-09 修复：`cred.present=false` 是**持久化的清除意图**。
                // 原实现只看 hasCred → 设置页清除时宿主若离线，Listener 不触发，
                // 重启后这里 hasCred=false 又不清理，`CredentialVault.init` 已把
                // Keystore 里的凭据装回来 → 清除无效。
                EventLog.info("CRED", "启动快照：cred.present=false（清除请求）→ 清除本地凭据")
                com.bydhooker.autostart.core.ModuleBridgeHost.onCredKeyChanged(
                    mapOf(BridgeContract.KEY_CRED_PRESENT to false))
            } else if (hasCred) {
                val snapshot = HashMap<String, Any?>(BridgeContract.CRED_KEYS.size)
                for (k in BridgeContract.CRED_KEYS) snapshot[k] = all[k]
                com.bydhooker.autostart.core.ModuleBridgeHost.onCredKeyChanged(snapshot)
            }
            for (k in BridgeContract.ALL_KEYS) {
                if (all.containsKey(k) && !BridgeContract.CRED_KEYS.contains(k)) {
                    ConfigStore.onRemoteChanged(k)
                }
            }
        } catch (t: Throwable) {
            EventLog.warn("BRIDGE_UNAVAILABLE", "桥注册失败：${t.javaClass.simpleName}")
        }
    }

    /** 读配置（宿主侧只读；键不存在返回 null，由 ConfigStore 用默认值兜底）。 */
    fun getString(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (_: Throwable) {
        null
    }

    fun getBoolean(key: String): Boolean? = try {
        prefs?.getBoolean(key, false)?.takeIf { prefs?.all?.containsKey(key) == true }
    } catch (_: Throwable) {
        null
    }

    fun getInt(key: String): Int? = try {
        val all = prefs?.all
        if (all?.containsKey(key) == true) (all[key] as? Number)?.toInt() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 读**原始值**（保持写入时的类型：`Boolean` / `Int` / `String`）；键不存在返回 null。
     *
     * T07 修复（2026-11-08 真机）：设置页用 `putBoolean/putInt` 写，宿主侧若统一用
     * [getString] 读会抛 `ClassCastException` 被吞成 null → 所有非字符串配置
     * 静默回落默认值（实测 `enabled=true` 被读成 false，M2 直接 GATE_SKIP 总开关关闭）。
     * 配置键一律走本方法，由 [ConfigStore] 按类型解释。
     */
    fun getAny(key: String): Any? = try {
        prefs?.all?.get(key)
    } catch (_: Throwable) {
        null
    }
}

/**
 * 配置内存镜像（宿主侧；来源 = remote prefs 推送，缺省用 `15` §6.2 默认值）。
 * 独立于 RemotePrefs 以便 Gate/StatusPoller 同步读（无锁开销）。
 */
object ConfigStore {
    @Volatile var enabled: Boolean = BridgeContract.DEF_WINDOW_SECONDS.let { true }
        private set
    @Volatile var mode: String = "M1"
        private set

    // ---- NFC 方案（`docs/30` §3.1；2026-11-11 22:5x）----
    /** NFC 方案总开关，默认关（新功能默认不改变既有行为）。 */
    @Volatile var nfcEnabled: Boolean = false
        private set

    /** NFC 方案模式："M1" / "M2"（与 APP 方案的 mode 独立）。 */
    @Volatile var nfcMode: String = "M1"
        private set

    /** 用户选中的触发卡 AID（hex 小写）；空 = 不触发（R-AID-7）。 */
    @Volatile var nfcSelectedAid: String = ""
        private set

    @Volatile var windowSeconds: Int = BridgeContract.DEF_WINDOW_SECONDS
        private set
    @Volatile var pollIntervalMs: Int = BridgeContract.DEF_POLL_INTERVAL_MS
        private set
    @Volatile var dataAgeLimitSec: Int = BridgeContract.DEF_DATA_AGE_LIMIT_SEC
        private set
    @Volatile var doorOpenValue: Int = BridgeContract.DEF_DOOR_OPEN_VALUE
        private set
    @Volatile var connectTimeoutMs: Int = BridgeContract.DEF_CONNECT_TIMEOUT_MS
        private set
    @Volatile var readTimeoutMs: Int = BridgeContract.DEF_READ_TIMEOUT_MS
        private set
    @Volatile var verifyDelayMs: Int = BridgeContract.DEF_VERIFY_DELAY_MS
        private set
    @Volatile var verifyRetry: Int = BridgeContract.DEF_VERIFY_RETRY
        private set

    /**
     * remote prefs 变更（配置键）→ 更新内存镜像；未知键忽略。
     *
     * T07 修复：**按写入类型解释**（设置页 `putBoolean`/`putInt`/`putString`），
     * 不再统一 `getString` —— 后者对非字符串值抛 CCE 被吞成 null，导致
     * `enabled` 恒为 false（真机 23:48:33 `GATE_SKIP 总开关关闭` 的根因）。
     */
    /**
     * 从**一份 prefs 全量快照**回填（2026-11-12 执行层迁移新增）。
     *
     * 【为什么不复用 onRemoteChanged】那条路走 `RemotePrefs.getAny(key)`，
     * 依赖宿主进程里的 `RemotePrefs.prefs`。而模块 App 进程**没有**这个句柄
     * （它只能通过 `XposedServiceGate` 拿 `XposedService.getRemotePreferences`），
     * 所以模块侧要执行判断时必须直接吃一份 `getAll()` 的结果。
     *
     * 调用点：`XposedServiceGate.loadInto`（首次绑定）与 `NfcWakeProvider`（每次刷卡前，
     * 保证配置是最新的——用户可能刚切了执行位置/模式）。
     */
    fun syncFromSnapshot(all: Map<String, *>) {
        (all[BridgeContract.KEY_ENABLED] as? Boolean)?.let { enabled = it }
        (all[BridgeContract.KEY_MODE] as? String)?.let { if (it.isNotEmpty()) mode = it }
        (all[BridgeContract.KEY_WINDOW_SECONDS] as? Number)?.let { windowSeconds = it.toInt() }
        (all[BridgeContract.KEY_POLL_INTERVAL_MS] as? Number)?.let { pollIntervalMs = it.toInt() }
        (all[BridgeContract.KEY_DATA_AGE_LIMIT_SEC] as? Number)?.let { dataAgeLimitSec = it.toInt() }
        (all[BridgeContract.KEY_DOOR_OPEN_VALUE] as? Number)?.let { doorOpenValue = it.toInt() }
        (all[BridgeContract.KEY_CONNECT_TIMEOUT_MS] as? Number)?.let { connectTimeoutMs = it.toInt() }
        (all[BridgeContract.KEY_READ_TIMEOUT_MS] as? Number)?.let { readTimeoutMs = it.toInt() }
        (all[BridgeContract.KEY_VERIFY_DELAY_MS] as? Number)?.let { verifyDelayMs = it.toInt() }
        (all[BridgeContract.KEY_VERIFY_RETRY] as? Number)?.let { verifyRetry = it.toInt() }
        // NFC（`docs/30`）
        (all[BridgeContract.KEY_NFC_ENABLED] as? Boolean)?.let { nfcEnabled = it }
        (all[BridgeContract.KEY_NFC_MODE] as? String)?.let { if (it.isNotEmpty()) nfcMode = it }
        (all[BridgeContract.KEY_NFC_SELECTED_AID] as? String)?.let { nfcSelectedAid = it }
    }

    fun onRemoteChanged(key: String) {
        val raw = RemotePrefs.getAny(key)
        when (key) {
            BridgeContract.KEY_ENABLED -> enabled = asBool(raw) ?: enabled
            BridgeContract.KEY_MODE -> mode = asString(raw) ?: "M1"
            BridgeContract.KEY_WINDOW_SECONDS -> windowSeconds =
                asInt(raw) ?: BridgeContract.DEF_WINDOW_SECONDS
            BridgeContract.KEY_POLL_INTERVAL_MS -> pollIntervalMs =
                asInt(raw) ?: BridgeContract.DEF_POLL_INTERVAL_MS
            BridgeContract.KEY_DATA_AGE_LIMIT_SEC -> dataAgeLimitSec =
                asInt(raw) ?: BridgeContract.DEF_DATA_AGE_LIMIT_SEC
            BridgeContract.KEY_DOOR_OPEN_VALUE -> doorOpenValue =
                asInt(raw) ?: BridgeContract.DEF_DOOR_OPEN_VALUE
            BridgeContract.KEY_CONNECT_TIMEOUT_MS -> connectTimeoutMs =
                asInt(raw) ?: BridgeContract.DEF_CONNECT_TIMEOUT_MS
            BridgeContract.KEY_READ_TIMEOUT_MS -> readTimeoutMs =
                asInt(raw) ?: BridgeContract.DEF_READ_TIMEOUT_MS
            BridgeContract.KEY_VERIFY_DELAY_MS -> verifyDelayMs =
                asInt(raw) ?: BridgeContract.DEF_VERIFY_DELAY_MS
            BridgeContract.KEY_VERIFY_RETRY -> verifyRetry =
                asInt(raw) ?: BridgeContract.DEF_VERIFY_RETRY
            // NFC（`docs/30` §3.1）
            BridgeContract.KEY_NFC_ENABLED -> nfcEnabled = asBool(raw) ?: nfcEnabled
            BridgeContract.KEY_NFC_MODE -> nfcMode = asString(raw) ?: "M1"
            BridgeContract.KEY_NFC_SELECTED_AID -> nfcSelectedAid = asString(raw) ?: ""
            else -> return
        }
        // 配置键（非凭据）无敏感值，落值便于真机核对类型/取值
        EventLog.debug("CONFIG", "配置更新：$key=$raw（${raw?.javaClass?.simpleName ?: "缺失"}）")
    }

    /** 类型容错：Boolean / "true" / 1 都认；其余 null。 */
    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        is Number -> v.toInt() != 0
        else -> null
    }

    /** 类型容错：Number / 数字字符串；其余 null。 */
    private fun asInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asString(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        else -> v.toString()
    }
}

package com.bydhooker.autostart.data

import android.content.SharedPreferences
import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.ui.AuthUi
import com.bydhooker.autostart.ui.SettingsState
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 设置页侧 Remote Preferences 访问（架构 A / 裁定 G：设置页**直接写**，
 * 宿主 Change Listener 实时收；P1-2 真机已验证全链路）。
 *
 * 通道：XposedServiceHelper（`06` §9.2 已 javap 确认签名）→
 * XposedService.getRemotePreferences(`BridgeContract.PREFS_GROUP`) →
 * RemotePreferences（框架 IPC 代理，Editor.commit() 走 Bundle diff 推送）。
 *
 * 102.0.0 上游缺陷（AAR 缺 AIDL 生成类）由 app 源码内
 * `io.github.libxposed.service.IXposedService` 精简 Stub 补齐（P1 实测版）。
 */
object XposedServiceGate {

    @Volatile private var registered = false

    /**
     * 绑定完成信号。
     *
     * 广播唤醒 / Provider 调用拉起的进程**没有 Activity**，`SettingsActivity.onCreate`
     * 里的 `register()` 不会执行 → `prefs` 恒为 null → 写配置静默失败。
     * 故 `register()` 移到 `BydApp.onCreate`，并在这里用 latch 让调用方可以等绑定完成。
     */
    private val bindLatch = java.util.concurrent.CountDownLatch(1)

    /** 桥 prefs（RemotePreferences；null = 服务未绑定）。 */
    @Volatile var prefs: SharedPreferences? = null
        private set

    /**
     * 等待绑定完成（最多 [timeoutMs]）。
     *
     * 已绑上立即返回；否则阻塞等待 `onServiceBind`。**调用方必须在后台线程**，
     * 不能放在 `BroadcastReceiver.onReceive` / `ContentProvider.call` 的主路径上直接阻塞太久。
     */
    fun awaitPrefs(timeoutMs: Long): SharedPreferences? {
        if (prefs != null) return prefs
        try {
            bindLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return prefs
    }

    /** 绑定回调线程写快照状态；UI 帧读取（Compose 快照跨线程安全）。 */
    fun register() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val p = try {
                    service.getRemotePreferences(BridgeContract.PREFS_GROUP)
                } catch (_: Throwable) {
                    null
                }
                if (p == null) {
                    SettingsState.bridgeAvailable = false
                    return
                }
                prefs = p
                SettingsState.bridgeAvailable = true
                bindLatch.countDown()
                loadInto(p)
            }

            override fun onServiceDied(service: XposedService) {
                SettingsState.bridgeAvailable = false
            }
        })
    }

    /** 首次绑定：把 remote prefs 现值回填到 UI 状态（配置回显口径）。 */
    private fun loadInto(prefs: SharedPreferences) {
        val all = try {
            prefs.all
        } catch (_: Throwable) {
            emptyMap<String, Any?>()
        }
        SettingsState.enabled = (all[BridgeContract.KEY_ENABLED] as? Boolean) ?: true
        SettingsState.mode = (all[BridgeContract.KEY_MODE] as? String) ?: "M1"
        val win = (all[BridgeContract.KEY_WINDOW_SECONDS] as? Number)?.toInt()
        SettingsState.windowSeconds = win ?: BridgeContract.DEF_WINDOW_SECONDS
        val poll = (all[BridgeContract.KEY_POLL_INTERVAL_MS] as? Number)?.toInt()
        SettingsState.pollIntervalSec =
            (poll ?: BridgeContract.DEF_POLL_INTERVAL_MS) / 1000
        val age = (all[BridgeContract.KEY_DATA_AGE_LIMIT_SEC] as? Number)?.toInt()
        SettingsState.dataAgeLimitSec = age ?: BridgeContract.DEF_DATA_AGE_LIMIT_SEC
        val present = all[BridgeContract.KEY_CRED_PRESENT] as? Boolean ?: false
        SettingsState.credPresent = present
        // 授权时间：**兼容 String**（remote prefs 里的值会被框架序列化成 String，
        // 只认 Number 会永远拿到 0 —— 这就是首页授权卡时间显示「—」的直接原因，
        // 与 2.0.50 `CredentialVault` 里修过的是同一个坑，UI 侧当时漏改）。
        // 都取不到时回落到 cred.bin 的 mtime（≈ 授权时刻），保证老凭据也能显示。
        SettingsState.credAcquireTs =
            parseTs(all[BridgeContract.KEY_CRED_ACQUIRE_TS]).takeIf { it > 0 }
                ?: fallbackAcquireTs()
        SettingsState.credAutoType = (all[BridgeContract.KEY_CRED_AUTO_TYPE] as? String) ?: ""
        SettingsState.credAutoPlate = (all[BridgeContract.KEY_CRED_AUTO_PLATE] as? String) ?: ""
        SettingsState.auth = if (present) AuthUi.AUTHORIZED else AuthUi.NONE
        // NFC 方案（`docs/30` §3.1）：默认关闭 / M1 / 未选卡（未选卡 ⇒ 刷卡不触发，R-AID-7）
        SettingsState.nfcEnabled = all[BridgeContract.KEY_NFC_ENABLED] as? Boolean ?: false
        SettingsState.nfcMode = (all[BridgeContract.KEY_NFC_MODE] as? String) ?: "M1"
        SettingsState.nfcSelectedAid = (all[BridgeContract.KEY_NFC_SELECTED_AID] as? String) ?: ""
        SettingsState.nfcSelectedCid = (all[BridgeContract.KEY_NFC_SELECTED_CID] as? String) ?: ""
        SettingsState.nfcSelectedName = (all[BridgeContract.KEY_NFC_SELECTED_NAME] as? String) ?: ""
        // Hook 三层与卡列表存于本 App 私有 SP（NfcLocalState），不在 remote prefs 里
        SettingsState.reloadNfcLocal()
        // 配置也回填一份到 ConfigStore —— 模块进程若承担执行层，判据/窗口要读它
        // （宿主进程的 ConfigStore 由 RemotePrefs 监听器维护，两条路径互不干扰）
        com.bydhooker.autostart.bridge.ConfigStore.syncFromSnapshot(all)
    }

    /** 读桥键（诊断结果 / 日志快照回读）。 */
    fun getString(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (_: Throwable) {
        null
    }

    /** 写桥键（诊断请求触发用）。 */
    fun putString(key: String, value: String) {
        editor()?.putString(key, value)?.commit()
    }

    /**
     * 从 remote prefs 收集凭据键 → Credential（设置页**直查**用：架构 A 下凭据
     * 本就以明文存于框架侧，设置页进程有 Whitebox，可直接发只读请求；
     * 宿主侧实时推送不可靠（2026-11-08 实测），直查是诊断唯一可靠路径）。
     */
    /**
     * 取一份可用的凭据（UI「获取车况」等只读功能用）。
     *
     * 【2026-11-12 23:0x 修：必须优先本进程 vault】
     * v2.0.29 第 3 步把**敏感凭据键（watchImei / signToken / encryToken / passWord）从
     * remote prefs 中删掉了**（执行层搬到模块后宿主不再需要 token）。而本方法原来只读
     * remote prefs ⇒ 那几个键取不到 ⇒ `?: return null` ⇒ 首页「获取车况」直接显示
     * 「凭据未就绪，请重新授权」，**其实模块自己的 cred.bin 里凭据完好**。
     *
     * 现在顺序：① 本进程 vault（模块侧 CBC 加密库，跨重启已验证可解密）
     *            ② remote prefs（兜底：host 模式 / 旧版本残留）
     */
    fun readCredential(): com.bydhooker.autostart.model.Credential? {
        // ① 本进程 vault 优先
        try {
            com.bydhooker.autostart.core.CredentialVault.current()?.let { return it }
        } catch (_: Throwable) {
            // vault 未就绪（未授权 / 解密失败）→ 继续兜底
        }
        // ② remote prefs 兜底
        val all = try {
            prefs?.all
        } catch (_: Throwable) {
            null
        } ?: return null
        if (all[BridgeContract.KEY_CRED_PRESENT] != true) return null
        fun s(k: String): String? = all[k] as? String
        return com.bydhooker.autostart.model.Credential(
            watchImei = s(BridgeContract.KEY_CRED_WATCH_IMEI) ?: return null,
            signToken = s(BridgeContract.KEY_CRED_SIGN_TOKEN) ?: return null,
            encryToken = s(BridgeContract.KEY_CRED_ENCRY_TOKEN) ?: return null,
            passWord = s(BridgeContract.KEY_CRED_PASSWORD) ?: return null,
            vin = all[BridgeContract.KEY_CRED_VIN] as? String ?: "",
            identifier = all[BridgeContract.KEY_CRED_IDENTIFIER] as? String ?: "",
            identifierType = all[BridgeContract.KEY_CRED_IDENTIFIER_TYPE] as? String ?: "",
            vehicleBrand = all[BridgeContract.KEY_CRED_VEHICLE_BRAND] as? String ?: "",
            appChannel = all[BridgeContract.KEY_CRED_APP_CHANNEL] as? String ?: "",
            autoType = all[BridgeContract.KEY_CRED_AUTO_TYPE] as? String,
            tboxVersion = all[BridgeContract.KEY_CRED_TBOX_VERSION] as? String,
            acquireTokenTimestamp =
                parseTs(all[BridgeContract.KEY_CRED_ACQUIRE_TS]),
        )
    }

    private fun editor(): SharedPreferences.Editor? = try {
        prefs?.edit()
    } catch (_: Throwable) {
        null
    }

    fun writeEnabled(v: Boolean) {
        editor()?.putBoolean(BridgeContract.KEY_ENABLED, v)?.commit()
        SettingsState.enabled = v
    }

    fun writeMode(v: String) {
        editor()?.putString(BridgeContract.KEY_MODE, v)?.commit()
        SettingsState.mode = v
    }

    fun writeWindowSeconds(v: Int) {
        editor()?.putInt(BridgeContract.KEY_WINDOW_SECONDS, v)?.commit()
        SettingsState.windowSeconds = v
    }

    fun writePollIntervalSec(seconds: Int) {
        editor()?.putInt(BridgeContract.KEY_POLL_INTERVAL_MS, seconds * 1000)?.commit()
        SettingsState.pollIntervalSec = seconds
    }

    fun writeDataAgeLimitSec(v: Int) {
        editor()?.putInt(BridgeContract.KEY_DATA_AGE_LIMIT_SEC, v)?.commit()
        SettingsState.dataAgeLimitSec = v
    }

    /**
     * NFC 方案开关（`docs/30` §3.1）。**独立于 [writeEnabled]** ——
     * 14:2x 拍板后 `enabled` 只代表 APP 方案，不再兼任总开关。
     */
    fun writeNfcEnabled(v: Boolean) {
        editor()?.putBoolean(BridgeContract.KEY_NFC_ENABLED, v)?.commit()
        SettingsState.nfcEnabled = v
    }

    /** NFC 方案模式（M1/M2）。 */
    fun writeNfcMode(v: String) {
        editor()?.putString(BridgeContract.KEY_NFC_MODE, v)?.commit()
        SettingsState.nfcMode = v
    }

    /**
     * 选择触发卡 —— **三键原子提交**（一次 commit）。
     *
     * 三个键缺一不可：`aid` 是唯一判据；`cid` 供 UI 回显选中态（事件层拿不到 cid，
     * 只能靠 aid 匹配；cid 存下来是为了同产品多卡时能区分是哪一张，见 `docs/29` §2.3）；
     * `name` 是显示文案。**改卡时三个一起换，不允许只改其中两个。**
     */
    fun writeNfcCard(aid: String, cid: String, name: String) {
        editor()
            ?.putString(BridgeContract.KEY_NFC_SELECTED_AID, aid)
            ?.putString(BridgeContract.KEY_NFC_SELECTED_CID, cid)
            ?.putString(BridgeContract.KEY_NFC_SELECTED_NAME, name)
            ?.commit()
        SettingsState.nfcSelectedAid = aid
        SettingsState.nfcSelectedCid = cid
        SettingsState.nfcSelectedName = name
    }

    /**
     * 授权成功（T05 网络流）：凭据写入**两处**。
     *
     * ① **模块侧自存**（`docs/31` 第 1 步，2026-11-12）：加密落模块自己的
     *    `files/bydhooker/cred.bin`（Keystore 归模块 UID）。执行层迁移后模块要能
     *    在自己进程里发 watch 请求，凭据必须先在本进程拿得到。
     * ② **写 remote prefs**（原路径，保留）：宿主收到后加密落宿主那份，维持现状不变。
     *
     * 双写是迁移期的刻意选择：任一侧失效都能退回另一侧，不需要回滚 APK。
     * 稳定后（第 3 步）会清除宿主那份，届时只剩模块。
     */
    fun persistCredentials(cred: Map<String, String>) {
        // v3.1.1：授权时间戳**兜底补齐**。
        // 授权流程（`WatchQrAuth`）构造的 Credential 里 `acquireTokenTimestamp = 0L`，
        // 于是这个键从未被写过；随后 `republishFromVault` 又把 vault 里的 0
        // 以字符串形式写回 remote prefs，把 `AuthController` 刚写的正确值覆盖掉
        // ⇒ 首页授权卡永远显示「—」。这里统一补：没有或 <=0 就取当前时间。
        val full: Map<String, String> =
            if (parseTs(cred[BridgeContract.KEY_CRED_ACQUIRE_TS]) > 0L) {
                cred
            } else {
                HashMap(cred).apply {
                    put(
                        BridgeContract.KEY_CRED_ACQUIRE_TS,
                        System.currentTimeMillis().toString(),
                    )
                }
            }

        // ① 先自存模块侧——**放在 editor() 之前**：桥不可用时也要能落模块（否则白授权一次）
        saveToModuleVault(full)

        // ② remote prefs：**15 个 CRED_KEYS 全部明码常驻**（架构 C · D-34-2 用户拍板）
        //
        // 【为什么恢复明码】架构 C 之前，执行层在模块、宿主不需要 token，
        // 所以敏感键（watchImei / signToken / encryToken / passWord / identifier）
        // 一律不进 remote prefs，宿主那份 cred.bin 还会被主动删掉（旧 B 架构做法，已废）。
        // 现在 **APP 链也改为就地执行**（官方 App 进程自己发 HTTPS），
        // 宿主必须能拿到完整凭据 —— 而它唯一的入口就是 remote prefs
        // （`RemotePrefs.init` 的启动快照会把 CRED_KEYS 喂给宿主自己的 vault）。
        //
        // 【风险，明说】token 明码躺在 LSPosed 的 remote prefs 里（模块 APK 的私有目录）：
        // · root 可读；LSPosed 管理器可见；模块 APK 本身用户也能取
        // · 换来的是「三个进程各持一份凭据、互不依赖、宿主不必被唤醒」（D-34-2）
        // 这是用户 2026-11-13 10:5x 明确拍板的取舍，不是实现偷懒。
        val ed = editor() ?: return
        for (k in BridgeContract.CRED_KEYS) {
            full[k]?.let { ed.putString(k, it) }
        }
        ed.putBoolean(BridgeContract.KEY_CRED_PRESENT, true)
        // 架构 C 第三期：`cred.hostRevoked` 键与宿主的清除指令处理已整条删除。
        // 遗留的旧值会被 ALL_KEYS 过滤掉，不再有任何读取方，无需再显式置 false。
        ed.commit()

        // v3.0.4：授权后**立即**把凭据同步到 system 侧。
        // 原行为只在模块 App 启动时发布（SystemAssetPublisher.publish），且
        // system_server 的凭据只在开机 EXEC_INIT 载入一次 ⇒ 重新授权后到重启
        // 手机前，三击一直用旧 token（没暴露只是因为 token 有效期长）。
        Thread({
            try {
                com.bydhooker.autostart.BydApp.appCtx?.let {
                    com.bydhooker.autostart.core.SystemAssetPublisher.publishCredentialsOnly(it)
                }
            } catch (_: Throwable) {
            }
        }, "bydhooker-sys-cred").apply { isDaemon = true }.start()
    }

    /**
     * 用模块侧 vault 里的凭据**重新发布**一份完整明文到 remote prefs（架构 C · D-34-2）。
     *
     * 【什么时候需要】旧版本授权时只有非敏感键进了 remote prefs，敏感键被主动删掉
     * （`docs/31` 第 3 步）。架构 C 下**宿主进程也要就地执行**（APP 链），
     * ⇒ 它必须有完整凭据，而唯一的入口就是 remote prefs。
     * 重新授权一次当然也行，但用户未必想再扫一次码，所以这里做一次自动补齐。
     *
     * 【幂等】没有变化就什么都不写（避免每次开 App 都推一遍 token）。
     */
    fun republishFromVault(): Boolean {
        return try {
            val cred = com.bydhooker.autostart.core.CredentialVault.current() ?: return false
            val m = HashMap<String, String>()
            m[BridgeContract.KEY_CRED_WATCH_IMEI] = cred.watchImei
            m[BridgeContract.KEY_CRED_SIGN_TOKEN] = cred.signToken
            m[BridgeContract.KEY_CRED_ENCRY_TOKEN] = cred.encryToken
            m[BridgeContract.KEY_CRED_PASSWORD] = cred.passWord
            m[BridgeContract.KEY_CRED_VIN] = cred.vin
            m[BridgeContract.KEY_CRED_IDENTIFIER] = cred.identifier
            m[BridgeContract.KEY_CRED_IDENTIFIER_TYPE] = cred.identifierType
            m[BridgeContract.KEY_CRED_VEHICLE_BRAND] = cred.vehicleBrand
            m[BridgeContract.KEY_CRED_APP_CHANNEL] = cred.appChannel
            m[BridgeContract.KEY_CRED_AUTO_TYPE] = cred.autoType ?: ""
            m[BridgeContract.KEY_CRED_TBOX_VERSION] = cred.tboxVersion ?: ""
            // v3.1.1：**只在 vault 里有正值时才写**。
            // 授权流程存进来的 acquireTokenTimestamp 可能是 0，写 0 会把
            // remote prefs 里已有的正确授权时间覆盖掉（首页显示「—」的根因之一）。
            if (cred.acquireTokenTimestamp > 0L) {
                m[BridgeContract.KEY_CRED_ACQUIRE_TS] = cred.acquireTokenTimestamp.toString()
            }

            // v3.0.4：**差异检测** —— remote prefs 与模块 vault 一致就跳过写。
            // 原行为每次打开模块都无条件重发 15 键，宿主/tsmclient 的 Listener
            // 各做一次无谓的 PBKDF2 加密落盘（日志里每次都有 CRED_PERSIST_OK 就是它）。
            // remote prefs 本身持久，宿主/tsmclient 的启动快照自会读取；
            // 只有「真的缺了 / 变了」才需要发。
            val all = try {
                prefs?.all
            } catch (_: Throwable) {
                null
            }
            if (all != null) {
                val same = all[BridgeContract.KEY_CRED_PRESENT] == true &&
                        m.all { (k, v) -> all[k] == v }
                if (same) {
                    EventLog.info("CRED", "remote prefs 凭据与模块 vault 一致，跳过重发")
                    return true
                }
            }

            persistCredentials(m)
            EventLog.info("CRED", "已把完整凭据重新发布到 remote prefs（宿主就地执行需要）")
            true
        } catch (t: Throwable) {
            EventLog.warn("CRED_ERR", "重新发布凭据失败：" + t.javaClass.simpleName)
            false
        }
    }

    /** 把凭据写进模块自己的 Keystore 加密库（失败只记日志，不影响宿主那条路）。 */
    private fun saveToModuleVault(cred: Map<String, String>) {
        val dir = com.bydhooker.autostart.BydApp.moduleFilesDir
        if (dir == null) {
            com.bydhooker.autostart.core.EventLog.warn(
                "CRED_MODULE_ERR", "模块侧 filesDir 不可用，跳过自存")
            return
        }
        try {
            val m = HashMap<String, Any?>(cred.size + 1)
            m.putAll(cred)
            m[BridgeContract.KEY_CRED_PRESENT] = true
            // bringToFront=false：模块自己就是设置页，不需要再拉一次
            com.bydhooker.autostart.core.CredentialVault.onCredChanged(dir, m, false)
        } catch (t: Throwable) {
            com.bydhooker.autostart.core.EventLog.warn(
                "CRED_MODULE_ERR", "模块侧自存失败：" + t.javaClass.simpleName)
        }
    }

    /**
     * 写「三击电源键解锁」开关（`docs/32` §4.5）。
     *
     * 【为什么不在 remote prefs 里】三击的判断发生在 **system_server**，它读不到模块的
     * remote prefs（那是宿主 App 的 SharedPreferences）。所以开关必须落到 system_server
     * 能读的两处：`Settings.Global` 与 `/data/system/bydhk_triple_on`。
     *
     * 乐观更新 + 写完回读：`su` 可能失败（root 被撤、KernelSU 未授权），
     * 失败时把 UI 改回去，不能让开关显示"开着"而实际没写。
     */
    fun setTripleEnabled(on: Boolean) {
        val ctx = com.bydhooker.autostart.BydApp.appCtx
        com.bydhooker.autostart.ui.SettingsState.tripleEnabled = on
        Thread({
            val ok = com.bydhooker.autostart.core.TripleSwitch.setEnabled(on)
            val actual = com.bydhooker.autostart.core.TripleSwitch.isEnabled(ctx)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                com.bydhooker.autostart.ui.SettingsState.tripleEnabled = actual
                if (!ok) {
                    com.bydhooker.autostart.core.EventLog.warn(
                        "TRIPLE_SWITCH_ERR",
                        "写入失败（root 不可用？），开关已回滚为实际值 $actual",
                    )
                }
            }
        }, "bydhooker-triple-switch").apply { isDaemon = true }.start()
    }

    fun writeAcquireTs(ts: Long) {
        // 写 String 与其余 cred.* 键保持一致：
        // ① remote prefs 的 `all` 快照里类型统一，`republishFromVault` 的差异检测
        //    （`all[k] == v`，v 恒为 String）才不会因 Long/String 不等而每次都重发；
        // ② `parseTs` 两种类型都认，向后兼容历史写入的 Long 值。
        editor()?.putString(BridgeContract.KEY_CRED_ACQUIRE_TS, ts.toString())?.commit()
        SettingsState.credAcquireTs = ts
    }

    /** 把 remote prefs 里的值解析成时间戳（框架可能给 String，也可能给 Number）。 */
    private fun parseTs(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    /**
     * 授权时间的最终兜底：`cred.bin` 的最后修改时间（≈ 授权时刻）。
     *
     * 【为什么需要】老版本授权时 `cred.acquireTokenTimestamp` 恒为 0，
     * remote prefs 里存的也是 "0"。用户不希望为了「显示一个时间」重新扫一次码，
     * 所以用凭据文件的 mtime 顶上 —— 语义正确（这份凭据是什么时候拿到的），
     * 且只用于展示，不参与任何业务判断。
     */
    private fun fallbackAcquireTs(): Long {
        val dir = try {
            com.bydhooker.autostart.BydApp.appCtx?.filesDir
        } catch (_: Throwable) {
            null
        } ?: return 0L
        val f = java.io.File(dir, "cred.bin")
        return if (f.exists()) f.lastModified() else 0L
    }

    fun writeAutoType(v: String) {
        editor()?.putString(BridgeContract.KEY_CRED_AUTO_TYPE, v)?.commit()
        SettingsState.credAutoType = v
    }

    /**
     * 清除凭据（`18` §5.5）：写 `cred.present=false` → 宿主 ModuleBridgeHost 检测后
     * CredentialVault.clear（Keystore + 私有文件）；本页移除其余 cred.* 明文键
     * （裁定 E 允许明码 + 仅本地，但不留无用明文）。
     */
    fun clearCredentials() {
        val ed = editor() ?: return
        // ★ 2026-11-09 修复：CRED_KEYS **包含** KEY_CRED_PRESENT，原实现先写
        // `present=false` 再遍历 remove 全部 CRED_KEYS，把刚写的 false 一并删掉 →
        // 结果是「present 键不存在」而非「false」。宿主离线时该清除意图无法持久化，
        // 重启后 `CredentialVault.init` 又从 Keystore 文件把凭据装回来，
        // 现象 = 清除后仍能解锁上电 / 车况查询正常。
        // 现在：先删其余凭据键，最后写 present=false（保留键，宿主重启仍可读到）。
        for (k in BridgeContract.CRED_KEYS) {
            if (k != BridgeContract.KEY_CRED_PRESENT) ed.remove(k)
        }
        ed.putBoolean(BridgeContract.KEY_CRED_PRESENT, false)
        val ok = ed.commit()
        // 同步清模块侧那份（`docs/31` 双写后必须两边一起清，否则「清除」只清了一半）
        com.bydhooker.autostart.BydApp.moduleFilesDir?.let { dir ->
            try {
                com.bydhooker.autostart.core.CredentialVault.clear(dir)
            } catch (t: Throwable) {
                com.bydhooker.autostart.core.EventLog.warn(
                    "CRED_MODULE_ERR", "模块侧清除失败：" + t.javaClass.simpleName)
            }
        }
        com.bydhooker.autostart.core.EventLog.info("CRED", "用户请求清除凭据：present=false 已写入（commit=$ok）")
        SettingsState.credPresent = false
        SettingsState.credAcquireTs = 0L
        SettingsState.credAutoType = ""

        // v3.0.4：**同时清 system 侧残留**。旧行为只清模块 + 宿主，
        // /data/system/bydhk/cred.bin 残留 + system_server 内存 cached 未刷新 ⇒
        // 清除后 NFC/三击仍能用旧凭据上电（2026-11-13 21:1x 核查发现）。
        // publishCredentialsOnly 无条件 rm system 侧两份文件；
        // system_server 内存里的 cached 由 `InjectableExec` 每次执行前
        // `reloadIfFileChanged` 跟进（文件没了 → 重载 → current()=null → 拒发）。
        Thread({
            try {
                com.bydhooker.autostart.BydApp.appCtx?.let {
                    com.bydhooker.autostart.core.SystemAssetPublisher.publishCredentialsOnly(it)
                }
            } catch (_: Throwable) {
            }
        }, "bydhooker-sys-clear").apply { isDaemon = true }.start()
        SettingsState.credAutoPlate = ""   // 修复：此前漏清，导致车牌掩码仍残留（清除不工作现象）
        SettingsState.auth = AuthUi.NONE
        SettingsState.authFailReason = ""
        // 清除即作废任何进行中的扫码会话，回到授权初始态
        SettingsState.qrPayload = ""
        SettingsState.qrRemainingSec = 0L
    }
}

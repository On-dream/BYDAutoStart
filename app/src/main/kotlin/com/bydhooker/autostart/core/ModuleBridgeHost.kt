package com.bydhooker.autostart.core


/**
 * 桥宿主侧动作收口（裁定 G 架构 A）。
 *
 * 数据流（真机已验证，P1-2）：
 * - 配置：设置页写 remote prefs → 本宿主 ConfigStore（RemotePrefs listener）
 * - 凭据：设置页写 cred.* → CredentialVault.onCredChanged（加密落盘）
 * - **清除凭据**：设置页写 `cred.present=false` → 本类检测 → CredentialVault.clear
 * - 导出：宿主把 EventLog 快照写私有文件；**分享面板由 T05 经
 *   application context + FLAG_ACTIVITY_NEW_TASK 触发**（T03 只生成文件）
 */
object ModuleBridgeHost {

    @Volatile private var filesDir: java.io.File? = null

    fun init(hostFilesDir: java.io.File) {
        filesDir = hostFilesDir
        EventLog.init(hostFilesDir)
        // ⚠️ 宿主侧**不用 Keystore**，改用软件密钥 + CBC（架构 C · 2026-11-13）。
        //
        // 【为什么】宿主进程里的 Keystore 一直不稳定：同一份 cred.bin，
        // `selfCheck` 能解开、`ensureLoaded` 却抛 `IllegalBlockSizeException`
        // （真机 17:37:54 现场日志）。模块侧与 system_server 侧早就因为同一类
        // 故障（AEADBadTagException / 密钥被意外覆盖）改成了「软件派生密钥 + CBC+HMAC」，
        // 这里对齐 —— **三个进程用同一套格式与同一套派生方式**（seed 已硬编码为模块包名）。
        //
        // 【代价】凭据明文经过 PBKDF2 派生后存在宿主私有目录，不再有 Keystore 的第二层保护。
        // 但文件本身在 App 私有目录（要读必须有 root），与 Keystore 的实际差距不大。
        CredentialVault.attach(hostFilesDir, keystore = false)
    }

    /** RemotePrefs listener 分派：cred.present 非 true = 清除请求。 */
    fun onCredKeyChanged(prefs: Map<String, *>) {
        val present = prefs[com.bydhooker.autostart.bridge.BridgeContract.KEY_CRED_PRESENT]
        val dir = filesDir ?: return
        if (present == false || present == null) {
            if (CredentialVault.current() != null || CredentialVault.tokenState !=
                com.bydhooker.autostart.model.TokenState.NONE) {
                CredentialVault.clear(dir)
            }
            return
        }
        // ⚠️ `bringToFront = false`：本方法跑在**宿主进程**里。
        // 默认的 true 会把模块设置页拉到前台 —— 而宿主收到凭据推送的时机是
        // 「模块 App 打开 → republishFromVault」，此时用户可能正用着官方 App，
        // 被切走就是一次莫名其妙的跳转（架构 C 2026-11-13）。
        CredentialVault.onCredChanged(dir, prefs, bringToFront = false)
        // T05：凭据到达即跑一次 remoteControl 内层字段名自检（只读字段名，不发送）
        SelfCheck.checkCommandInnerKeys(CredentialVault.current())
    }

    /** 白盒初始化（需要 module APK 路径与宿主 dataDir；entry 装配时调用）。 */
    fun ensureWhitebox(moduleApkPath: String, hostDataDir: String) {
        Whitebox.ensureLoaded(moduleApkPath, hostDataDir)
    }

    /** 诊断自检（设置页诊断按钮经配置流触发请求键时调用；T05 细化）。 */
    fun runSelfCheck(): SelfCheck.Result? =
        SelfCheck.run(filesDir ?: return null, CredentialVault.current())

    /** 导出文件生成（返回宿主私有文件；分享 Intent 由 T05 接入）。 */
    fun exportLog(): java.io.File? = filesDir?.let { EventLog.export(it) }
}

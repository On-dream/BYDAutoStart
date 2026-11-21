package com.bydhooker.autostart.core

import java.io.File
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.model.Credential
import com.bydhooker.autostart.model.TokenState
import org.json.JSONObject
import android.provider.Settings
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据库（`15` §6.1 / §8）。
 *
 * **2026-11-12 起两个进程各有一份**（`docs/31` 执行层迁移）：
 * - 宿主进程：现状路径，remote prefs 收到凭据 → 加密落 **宿主** `files/bydhooker/cred.bin`
 * - 模块进程：授权页直接双写 → 加密落 **模块** `files/bydhooker/cred.bin`
 *
 * 两份互不相干：Keystore 按 UID 隔离（同名 alias 不冲突），文件路径也各在自己的 data 目录。
 * 迁移完成后宿主那份会被清除，届时凭据只存在于模块。
 *
 * 数据流（现状，架构 A）：
 *   设置页 AuthFlow（模块 App 进程）拿到凭据 → **① 模块 vault 自存** →
 *   ② 写 remote prefs → 宿主 ModuleBridgeHost → 宿主 vault
 *   → 两个进程各自内存持有 Credential 明文（`signToken` 等**不再明文跨进程落盘**）。
 *
 * 一键清除（设置页 → 桥）：删文件 + 删 Keystore 条目 + 内存清零。
 * Keystore 不可用 → 降级为不落盘（每次宿主重启需等设置页重推凭据），
 * **不静默明文存储**（15 §8）。
 */
object CredentialVault {

    private const val FILE_NAME = "cred.bin"

    /**
     * 参与凭据 seed 派生的包名（**硬编码**，见 [softwareKey] 注释）。
     *
     * 不能取 `BydApp.appCtx?.packageName`：system_server 里没有 Application，
     * 那儿取到 null 会让 seed 退化，三进程派生出不同的 key。
     */
    private const val PKG_NAME = "com.bydhooker.autostart"
    private const val GCM_TAG_BITS = 128
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile private var cached: Credential? = null
    @Volatile var tokenState: TokenState = TokenState.NONE
        private set

    /** 凭据文件所在目录（由 Application 通过 [attach] 注入；两个进程各自一份）。 */
    @Volatile private var dir: File? = null

    /**
     * 装载状态：0 = 未开始，1 = 正在解密，2 = 已结束（成功或失败）。
     *
     * 【2026-11-12 23:5x 修「首次三击空转」】
     * 原实现用 `loadTried` 在**进入**时就置 true，等于「有人开始读」而不是「读完了」。
     * 三击链路恰好踩中这个窗口：Provider 冷启动进程 → 主线程在 `BydApp.onCreate` 里解密，
     * 同一时刻 binder 线程收到 `triple_click` → `current()` 看到 `loadTried=true` → 直接返回 null
     * → `NFC_EXEC_NO_CRED` → **本次三击彻底作废**。
     * 真车日志 23:49:23 / 23:50:04 两次都是这个时序（`TRIPLE_CLICK` 夹在
     * `CRED_PROVIDER` 与 `CRED_INIT` 之间），表现就是「第一次三击没反应，要再击一次」。
     *
     * 现在改成「后来者阻塞等结果」：`synchronized` 保证只有一个线程真去解密，
     * 其余线程在锁上等待到 `loadState = 2`，再拿 `cached` 的最终值。
     * 解密本身实测几百毫秒（PBKDF2 20000 轮），且发生在三击自己的
     * `bydhooker-triple-exec` 线程里，不影响 UI、不影响 Provider 返回。
     */
    @Volatile private var loadState = 0

    /** 上次载入时 cred.bin 的指纹（`reloadIfFileChanged` 的变更判据）。 */
    @Volatile private var lastFileStamp: String? = null

    /** 解密串行锁（见 [loadState] 注释）。 */
    private val loadLock = Any()

    /**
     * 是否用 AndroidKeyStore 加密（2026-11-12 起**只有宿主进程用**）。
     *
     * 【为什么模块进程不用 Keystore】真机实测：模块进程写入的 `cred.bin`，
     * 同一进程重启后解密必然抛 `IllegalBlockSizeException` —— 怀疑 Keystore 在
     * 「被 Provider 冷启动 / 无 Activity」的进程里拿到的 key 与写入时不是同一把。
     * 排查两轮未定位到根因（`containsAlias` 已加保护、解密已延后到进程稳定后），
     * 于是模块侧改用**软件派生密钥**：可预测、可自测、不依赖 Keystore 的进程行为。
     *
     * 安全权衡：文件本就在 app 私有目录（UID + SELinux 隔离），要读它必须先有 root；
     * 有 root 的场景下 Keystore 与软件密钥的实际防护差距很小。
     */
    @Volatile private var useKeystore = true

    /**
     * 软件密钥的盐文件（随机 16 字节，首次使用时生成并持久化）。
     *
     * 【为什么不用 ANDROID_ID】2026-11-12 实测：用 `ANDROID_ID` 派生密钥时，
     * 写入（授权回调）与读取（进程重启后）派生出的密钥不一致，解密报
     * `AEADBadTagException: BAD_DECRYPT`。改用**随机 salt 文件**后密钥只取决于
     * 「salt 内容 + 包名」，与本进程拿到的系统标识无关，行为可预测。
     * 盐不是秘密（和密文同目录），但读它同样需要 root —— 与读密文的门槛一致。
     */
    private const val SALT_FILE = "cred.salt"

    /** 文件头版本字节：0x01 = Keystore 加密，0x02 = 软件密钥加密。 */
    private const val HDR_KEYSTORE: Byte = 1
    private const val HDR_SOFTWARE: Byte = 2

    /**
     * 0x03 = 软件密钥 + **AES-CBC + HMAC-SHA256**（2026-11-12 起模块侧默认）。
     *
     * 【为什么放弃 GCM】同一份文件、同一个 key 指纹、同一个 provider(AndroidOpenSSL)，
     * 一个进程 selfCheck 能解、另一个进程 ensureLoaded 报 AEADBadTagException；
     * 文件字节(fp)、iv/ct 切法、PBKDF2 provider(BC) 全部排除后仍无法解释。
     * CBC+HMAC 是最普通的模式，行为可预测，且能自行校验完整性。
     */
    private const val HDR_CBC: Byte = 3

    /**
     * 记录凭据文件目录（Application.onCreate 调用）。**此处不做解密**。
     *
     * 【为什么不在这里解密】2026-11-12 真机踩到：`Application.onCreate` 早期
     * `AndroidKeyStore` 还没完全就绪，`KeyStore.getKey()` 可能返回 null；
     * 而旧实现在 null 时会**直接生成一把新 key 覆盖旧的** —— 旧密文从此永久解不开
     * （现象：`cred.bin` 明明存在，解密却抛 `IllegalBlockSizeException`）。
     * 现在改成「先记目录、真正要用时再解密」，让 Keystore 在进程稳定后再工作。
     */
    fun attach(filesDir: File, keystore: Boolean = true) {
        dir = filesDir
        useKeystore = keystore
    }

    /** 兼容旧调用点：等价于 [attach]（不再立即解密）。 */
    fun init(filesDir: File) {
        attach(filesDir)
    }

    /**
     * 真正解密载入（幂等，首次访问时触发）。
     *
     * 【并发语义】第一个进来的线程负责解密，其余线程**阻塞等待结果**（见 [loadState]）。
     * 这条对三击很关键：进程刚被 Provider 冷启动时，主线程和 binder 线程必然撞车。
     */
    private fun ensureLoaded() {
        if (loadState == 2) return
        // 还没 attach 时不要消耗「只试一次」的机会（attach 之后还要能读）
        if (dir == null) return
        val t0 = System.currentTimeMillis()
        synchronized(loadLock) {
            if (loadState == 2) return
            val waited = System.currentTimeMillis() - t0
            if (waited >= 5L) {
                EventLog.debug("CRED_WAIT", "等另一线程解密完成 ${waited}ms（本次直接复用结果）")
            }
            loadState = 1
            // 无论 doLoad 成败都记录文件指纹：`reloadIfFileChanged` 靠它区分
            // 「这个版本的文件已处理过」与「文件被改过（授权/清除）需要重载」
            val stamp = fileStamp()
            try {
                doLoad()
            } finally {
                loadState = 2
                lastFileStamp = stamp
            }
        }
    }

    /** cred.bin 的轻量指纹（length:lastModified；不存在 = absent）。 */
    private fun fileStamp(): String {
        val f = File(dir ?: return "absent", FILE_NAME)
        return if (f.exists()) f.length().toString() + ":" + f.lastModified() else "absent"
    }

    /**
     * cred.bin 文件变更时强制重新载入（v3.0.4，system 侧同步用）。
     *
     * 【为什么需要】system_server 进程常驻，`CredentialVault` 只在开机 `EXEC_INIT`
     * 时载入一次。授权后模块会把新 cred.bin 发布到 `/data/system/bydhk/`，
     * 但进程内存里的 `cached` 还停留在旧凭据 ⇒ 三击一直用旧 token
     * （之前没暴露只是因为 token 有效期长）。清除凭据同理：文件被 rm 后
     * 内存里的旧凭据仍在，三击仍能下发。
     *
     * 本方法比对 `length:lastModified` 指纹，变了就重置载入状态并重新解密。
     * 轻量（一次 stat），幂等，可在每次执行前调用。
     *
     * @return 是否发生了重载（调用方一般不需要关心结果，`current()` 自会反映）
     */
    fun reloadIfFileChanged(): Boolean {
        if (dir == null) return false
        val stamp = fileStamp()
        if (stamp == lastFileStamp && loadState == 2) return false
        synchronized(loadLock) {
            // 双检：别的线程可能已经重载完
            if (stamp == lastFileStamp && loadState == 2) return false
            loadState = 0
            cached = null
            lastFileStamp = null
        }
        ensureLoaded()
        EventLog.info("CRED_RELOAD", "cred.bin 已变更，重新载入（stamp=$stamp）")
        return true
    }

    /** 实际解密（只在 [loadLock] 内被调用一次）。 */
    private fun doLoad() {
        val filesDir = dir ?: return
        val f = File(filesDir, FILE_NAME)
        if (!f.exists()) {
            // 2026-11-13：原先是静默 return —— 文件不在时**一条日志都没有**，
            // 现场只剩一句 `NFC_EXEC_NO_CRED`，分不清是「没授权过」还是「文件读不到」。
            // 开机后未解锁（CE 存储未挂载）就属于后者，必须能看见。
            EventLog.warn("CRED_INIT_ERR", "cred.bin 不存在：" + f.absolutePath)
            return
        }
        try {
            // 宿主走 Keystore；模块走软件密钥（见 useKeystore 注释）。
            // Keystore 拿不到时**不再直接放弃** —— 若文件是软件密钥格式仍可解。
            val key = if (useKeystore) keyStoreKey() else null
            if (useKeystore && key == null) {
                EventLog.warn("CRED_INIT_ERR", "Keystore 不可用，尝试按软件密钥解")
            }
            val blob = f.readBytes()
            if (blob.size < 13) {
                EventLog.warn("CRED_INIT_ERR", "cred.bin 长度异常（${blob.size} 字节）")
                return
            }
            // 文件头：0x01/0x02 = 带版本字节；否则是旧格式（无头，按 Keystore 解）
            val head = blob[0]
            val hasHeader = head == HDR_KEYSTORE || head == HDR_SOFTWARE || head == HDR_CBC
            val off = if (hasHeader) 1 else 0
            val mode = if (hasHeader) head.toInt() else HDR_KEYSTORE.toInt()
            // ⚠️ 本进程只支持 CBC 格式（head=0x03）。别的格式（Keystore 0x01 / GCM 0x02 /
            //    无头旧格式）**不要盲解** —— 用错 key 只会抛一个看不懂的
            //    `IllegalBlockSizeException`，现场根本看不出是「格式不对」还是「真解不开」。
            //    （2026-11-13 17:37 真机踩过：宿主进程里读 Keystore 格式，就是这么迷惑的。）
            if (mode != HDR_CBC.toInt()) {
                EventLog.warn("CRED_INIT_ERR", "cred.bin 格式不符（head=" + mode
                        + "，本进程只支持 0x03/CBC）→ 需重新授权或重新发布凭据");
                return
            }
            if (blob.size < off + 13) {
                EventLog.warn("CRED_INIT_ERR", "cred.bin 长度异常（${blob.size} 字节）")
                return
            }
            val ivLenRead = if (mode == HDR_CBC.toInt()) 16 else 12
            if (blob.size < off + ivLenRead + 17) {
                EventLog.warn("CRED_INIT_ERR", "cred.bin 长度异常（${blob.size} 字节）")
                return
            }
            val iv = blob.copyOfRange(off, off + ivLenRead)
            val ct = blob.copyOfRange(off + ivLenRead, blob.size)
            // Keystore 模式用 Keystore key；软件模式（0x02 GCM / 0x03 CBC）用派生密钥
            val realKey = if (mode == HDR_KEYSTORE.toInt()) key else (softwareKey() ?: key)
            if (realKey == null) {
                // 旧格式（无头 / Keystore）在模块进程里解不了 —— 属预期，重新授权一次即可
                EventLog.warn("CRED_INIT_ERR", "无可用密钥（mode=$mode），需重新授权")
                return
            }
            EventLog.debug(
                "CRED_INIT_FMT",
                "head=${if (hasHeader) mode else -1}（0=旧格式）|ivLen=${iv.size}|ctLen=${ct.size}"
                    + "|path=${f.absolutePath}|fp=${blobFp(blob)}",
            )
            if (mode == HDR_CBC.toInt()) {
                val tagLen = 16
                if (blob.size < off + 16 + tagLen + 1) {
                    EventLog.warn("CRED_INIT_ERR", "CBC 密文长度异常（${blob.size}）")
                    return
                }
                val cbcIv = blob.copyOfRange(off, off + 16)
                val cbcCt = blob.copyOfRange(off + 16, blob.size - tagLen)
                val cbcTag = blob.copyOfRange(blob.size - tagLen, blob.size)
                val plain = cbcDecrypt(realKey, cbcIv, cbcCt, cbcTag)
                if (plain == null) {
                    EventLog.warn("CRED_INIT_ERR", "CBC 校验或解密失败")
                    return
                }
                cached = fromJson(plain)
                if (cached == null) {
                    EventLog.warn("CRED_INIT_ERR", "CBC 解密成功但 JSON 解析失败")
                }
                tokenState = if (cached != null) TokenState.AUTHORIZED else TokenState.NONE
                if (cached != null) EventLog.info("CRED_INIT", "cred.bin 解密载入成功（CBC）")
                return
            }
            val c = newCipher()
            c.init(Cipher.DECRYPT_MODE, realKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cached = fromJson(String(c.doFinal(), Charsets.UTF_8))
            if (cached == null) {
                // 解密成功但解析失败 = 旧版本手写 JSON 未转义导致内容损坏（见 toJson 注释）
                EventLog.warn("CRED_INIT_ERR", "cred.bin 解密成功但解析失败（内容可能已损坏，需重新授权）")
            }
            tokenState = if (cached != null) TokenState.AUTHORIZED else TokenState.NONE
            if (cached != null) {
                EventLog.info("CRED_INIT", "cred.bin 解密载入成功")
            }
        } catch (t: Throwable) {
            // 密文损坏 / Keystore 变化 → 按无凭据处理（不静默明文存储）
            EventLog.warn("CRED_INIT_ERR", t.javaClass.simpleName + "：" + t.message)
            cached = null
            tokenState = TokenState.NONE
        }
    }

    fun current(): Credential? {
        ensureLoaded()
        return cached
    }

    /**
     * **不触发解密、不阻塞**的快照（2026-11-13 加）。
     *
     * 给 `NfcWakeProvider` 用：三击到达时先看凭据在不在，不在就返回 `ok=false`
     * 让 system_server 重试 —— 而不是「先回 ok=true、再在后台线程里发现没凭据」。
     * 后者是**假成功**：调用方以为送达了，不会重试，本次三击彻底作废。
     */
    fun peek(): Credential? = cached

    /**
     * 装载阶段：0 = 未开始，1 = 正在解密，2 = 已结束（成功或失败）。
     *
     * 与 [peek] 配合区分两种「没有凭据」：
     * · phase=1 ⇒ 再等一两百毫秒就有了（**值得重试**）
     * · phase=2 ⇒ 确实没有（文件不存在 / 解密失败，重试也没用，只能报出来）
     */
    fun loadPhase(): Int = loadState

    /**
     * 收到凭据（设置页授权流程）→ 加密落盘 + 内存更新。
     *
     * @param bringToFront 是否顺带把模块设置页拉到前台。
     *   **宿主侧传 true**（授权完成时官方 App 在前台，不受 Android 10+ 后台启动限制，
     *   由它去拉模块页最稳）；**模块侧必须传 false** —— 模块自己就是设置页，
     *   再拉一次纯属多余，且会在授权瞬间出现一次闪屏。
     */
    @JvmOverloads
    fun onCredChanged(filesDir: File, prefs: Map<String, *>, bringToFront: Boolean = true) {
        if (prefs[BridgeContract.KEY_CRED_PRESENT] != true) return
        val cred = Credential(
            watchImei = prefs[BridgeContract.KEY_CRED_WATCH_IMEI] as? String ?: return,
            signToken = prefs[BridgeContract.KEY_CRED_SIGN_TOKEN] as? String ?: return,
            encryToken = prefs[BridgeContract.KEY_CRED_ENCRY_TOKEN] as? String ?: return,
            passWord = prefs[BridgeContract.KEY_CRED_PASSWORD] as? String ?: return,
            vin = prefs[BridgeContract.KEY_CRED_VIN] as? String ?: "",
            identifier = prefs[BridgeContract.KEY_CRED_IDENTIFIER] as? String ?: "",
            identifierType = prefs[BridgeContract.KEY_CRED_IDENTIFIER_TYPE] as? String ?: "",
            vehicleBrand = prefs[BridgeContract.KEY_CRED_VEHICLE_BRAND] as? String ?: "",
            appChannel = prefs[BridgeContract.KEY_CRED_APP_CHANNEL] as? String ?: "",
            autoType = prefs[BridgeContract.KEY_CRED_AUTO_TYPE] as? String,
            tboxVersion = prefs[BridgeContract.KEY_CRED_TBOX_VERSION] as? String,
            acquireTokenTimestamp =
                // ⚠️ 兼容 String：架构 C 起凭据经 remote prefs **明码**传递，
                // 而 remote prefs 的值全是 String ⇒ 只认 Number 会永远拿不到这个时间戳。
                (prefs[BridgeContract.KEY_CRED_ACQUIRE_TS] as? Number)?.toLong()
                    ?: (prefs[BridgeContract.KEY_CRED_ACQUIRE_TS] as? String)?.toLongOrNull()
                    ?: 0L,
        )
        cached = cred
        tokenState = TokenState.AUTHORIZED
        persist(filesDir, cred)
        EventLog.info("CRED", "凭据已接收并加密落盘（来源：设置页授权流程，bringToFront=$bringToFront）")
        // 授权完成 → 拉回模块设置页。
        // 在**宿主侧**做：官方 App 此刻在前台，不受 Android 10+ 后台启动限制。
        // 必须用 **setComponentName(字符串)** 而不是 Intent(Context, Class)：
        // 后者要求目标类在调用方 classloader 里，模块的类不在官方 App 的 dex 中，
        // 会抛 ActivityNotFoundException；显式 Component 不受包可见性限制。
        if (bringToFront) {
            bringModuleToFront()
        }
    }

    /** 拉起模块设置页（宿主侧执行；模块 Activity 已 exported=true）。 */
    private fun bringModuleToFront() {
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (app == null) {
                EventLog.warn("CRED", "拉起模块失败：取不到 Application")
                return
            }
            val i = android.content.Intent().apply {
                component = android.content.ComponentName(
                    "com.bydhooker.autostart",
                    "com.bydhooker.autostart.ui.SettingsActivity",
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            app.startActivity(i)
            EventLog.info("CRED", "已拉起模块设置页（授权完成，自动返回）")
        } catch (t: Throwable) {
            EventLog.warn("CRED", "拉起模块设置页失败：" + t.javaClass.simpleName)
        }
    }

    /** 一键清除（设置页 → 桥动作；宿主执行：删文件 + 删 Keystore 条目）。 */
    fun clear(filesDir: File) {
        cached = null
        tokenState = TokenState.NONE
        try { File(filesDir, FILE_NAME).delete() } catch (_: Throwable) {}
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(BridgeContract.KEYSTORE_ALIAS)) ks.deleteEntry(BridgeContract.KEYSTORE_ALIAS)
        } catch (_: Throwable) {}
        EventLog.info("CRED", "凭据已清除（含 Keystore 条目）")
    }

    private fun persist(filesDir: File, cred: Credential) {
        try {
            val ksKey = if (useKeystore) keyStoreKey() else null
            val key = ksKey ?: softwareKey() ?: run {
                EventLog.warn("CRED_PERSIST_ERR", "既无 Keystore key 也无软件密钥")
                return
            }
            // 模块侧（无 Keystore）走 CBC+HMAC；宿主侧维持原 GCM 路径不变
            val head: Byte
            val payload: ByteArray
            if (ksKey != null) {
                head = HDR_KEYSTORE
                val c = newCipher()
                c.init(Cipher.ENCRYPT_MODE, key)
                payload = c.iv + c.doFinal(toJson(cred).toByteArray(Charsets.UTF_8))
            } else {
                head = HDR_CBC
                payload = cbcEncrypt(key, toJson(cred).toByteArray(Charsets.UTF_8))
            }
            File(filesDir, FILE_NAME).writeBytes(byteArrayOf(head) + payload)
            EventLog.info("CRED_PERSIST_OK", "head=$head|payloadLen=${payload.size}")
            selfCheck(filesDir, head)
        } catch (t: Throwable) {
            // Keystore 不可用 → 不落盘（15 §8：降级为每次重启需重推凭据）
            // 2026-11-12：原来静默，导致「授权成功但重启后没凭据」查不出原因
            EventLog.warn("CRED_PERSIST_ERR", t.javaClass.simpleName + "：" + t.message)
        }
    }

    /**
     * Keystore AES-256-GCM 密钥（不存在则生成；alias = 15 §10.3 设计命名）。
     *
     * ⚠️ **已存在时绝不重建**（2026-11-12 修复）：原写法是
     * `(ks.getKey(alias) as? SecretKey) ?: 生成新 key` —— 一旦 `getKey` 因任何原因
     * 返回 null（例如 Application.onCreate 早期 Keystore 未就绪），就会**用新 key 覆盖旧的**，
     * 此前写下的 `cred.bin` 再也解不开。现在只要 alias 存在就直接返回（失败就返回 null，
     * 交给调用方按「无凭据」处理），宁可这次读不到，也不毁掉已有数据。
     */
    private fun keyStoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(BridgeContract.KEYSTORE_ALIAS)) {
            ks.getKey(BridgeContract.KEYSTORE_ALIAS, null) as? SecretKey
        } else {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(KeyGenParameterSpec.Builder(
                BridgeContract.KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            gen.generateKey()
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 软件派生密钥（仅模块进程使用；见 [useKeystore]）。
     *
     * `PBKDF2(ANDROID_ID | 包名 | 固定盐)` → AES-256-GCM。
     * ANDROID_ID 自 Android 8 起按「应用+用户」隔离，同一 App 的不同进程取到同一个值。
     */
    private fun softwareKey(): SecretKey? = try {
        val filesDir = dir ?: run {
            EventLog.warn("CRED_SWKEY_ERR", "未 attach 目录，无法派生软件密钥")
            return null
        }
        // 盐：首次随机生成并落盘，之后一直复用（保证跨进程/跨重启派生结果一致）
        val saltFile = File(filesDir, SALT_FILE)
        val salt = if (saltFile.exists() && saltFile.length() >= 16) {
            saltFile.readBytes()
        } else {
            val s = ByteArray(16)
            java.security.SecureRandom().nextBytes(s)
            try {
                saltFile.writeBytes(s)
            } catch (_: Throwable) {
            }
            s
        }
        // ⚠️ 2026-11-13 架构 C：seed 里的包名**硬编码**，不再取 `BydApp.appCtx?.packageName`。
        // system_server 里没有 Application，`appCtx` 恒为 null ⇒ seed 会退化成 "bydhooker"
        // ⇒ 派生出的 key 与写入时不同 ⇒ 静默解不开（且日志只报 HMAC 不匹配）。
        // 硬编码后各进程（模块 / 官方 App / system_server）派生结果一致。
        val pkg = PKG_NAME
        val seed = "bydhooker-cred-v1|" + pkg
        val spec = javax.crypto.spec.PBEKeySpec(seed.toCharArray(), salt, 20000, 256)
        val f = newPbkdf2()
        val key = SecretKeySpec(f.generateSecret(spec).encoded, "AES")
        // 指纹：写入与读取两次若不一致，日志里一眼可见（比等到解密失败再猜强）
        val fp = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val fpHex = fp.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        EventLog.debug("CRED_SWKEY_FP", "fp=$fpHex|saltLen=${salt.size}")
        key
    } catch (t: Throwable) {
        EventLog.warn("CRED_SWKEY_ERR", t.javaClass.simpleName + "：" + t.message)
        null
    }

    /**
     * **写完立刻回读自检**：当场就能知道「下次能不能解开」，不必等重启。
     *
     * 2026-11-12 加：凭据持久化连续踩了三个坑（JSON 未转义、Keystore 覆盖、派生密钥不稳），
     * 每次都要用户重新授权 + 重启才能发现问题。加自检后，授权当场就能在日志里看到结论。
     */
    private fun selfCheck(filesDir: File, expectHead: Byte) {
        try {
            val blob = File(filesDir, FILE_NAME).readBytes()
            if (blob.isEmpty() || blob[0] != expectHead) {
                EventLog.error("CRED_SELFCHECK", "文件头不符（期望 $expectHead）")
                return
            }
            val key = if (expectHead == HDR_KEYSTORE) keyStoreKey() else softwareKey()
            if (key == null) {
                EventLog.error("CRED_SELFCHECK", "无可用密钥，回读无法验证")
                return
            }
            EventLog.debug("CRED_SELFCHECK_FP", "path=${File(filesDir, FILE_NAME).absolutePath}|fp=${blobFp(blob)}")
            val plain: String = if (expectHead == HDR_CBC) {
                val tagLen = 16
                val iv = blob.copyOfRange(1, 17)
                val ct = blob.copyOfRange(17, blob.size - tagLen)
                val tag = blob.copyOfRange(blob.size - tagLen, blob.size)
                cbcDecrypt(key, iv, ct, tag) ?: run {
                    EventLog.error("CRED_SELFCHECK", "回读失败：CBC 校验/解密不通过")
                    return
                }
            } else {
                val iv = blob.copyOfRange(1, 13)
                val ct = blob.copyOfRange(13, blob.size)
                val c = newCipher()
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(c.doFinal(ct), Charsets.UTF_8)
            }
            val ok = fromJson(plain) != null
            if (ok) {
                EventLog.info("CRED_SELFCHECK", "回读解密成功（下次启动应能自动恢复）")
            } else {
                EventLog.error("CRED_SELFCHECK", "回读可解密但 JSON 解析失败")
            }
        } catch (t: Throwable) {
            EventLog.error("CRED_SELFCHECK", "回读失败：" + t.javaClass.simpleName + "：" + t.message)
        }
    }

    /**
     * 固定 JCE provider（2026-11-12 排查用）。
     *
     * 现象：同一份 cred.bin、同一个 key 指纹，一个进程能解、另一个进程报 `AEADBadTagException`。
     * 最可疑的是**两次调用拿到的 provider 不同**（Android 上 `AES/GCM/NoPadding` 与
     * `PBKDF2WithHmacSHA256` 同时可能由 Conscrypt 和 BC 提供，顺序受运行时影响）。
     * 这里显式优先 Conscrypt，并在日志里打印实际 provider，便于确认。
     */
    private fun newCipher(): Cipher {
        val c = try {
            Cipher.getInstance("AES/GCM/NoPadding", "Conscrypt")
        } catch (_: Throwable) {
            // 不能调 newCipher() 自己（会无限递归）
            Cipher.getInstance("AES/GCM/NoPadding")
        }
        EventLog.debug("CRED_PROVIDER", "cipher=" + c.provider.name)
        return c
    }

    private fun newPbkdf2(): javax.crypto.SecretKeyFactory {
        val f = try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "Conscrypt")
        } catch (_: Throwable) {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        }
        EventLog.debug("CRED_PROVIDER", "pbkdf2=" + f.provider.name)
        return f
    }

    /** AES-256-CBC 加密 + HMAC-SHA256(iv||ct) 前 16 字节；返回 `iv(16) + ct + tag(16)`。 */
    private fun cbcEncrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
        val ct = c.doFinal(plain)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        mac.update(iv)
        mac.update(ct)
        return iv + ct + mac.doFinal().copyOf(16)
    }

    /** 先校验 HMAC，再解密；任何一步失败返回 null（不抛）。 */
    private fun cbcDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ct: ByteArray,
        tag: ByteArray,
    ): String? = try {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        mac.update(iv)
        mac.update(ct)
        val expect = mac.doFinal().copyOf(16)
        if (!MessageDigest.isEqual(expect, tag)) {
            EventLog.warn("CRED_CBC_MAC", "HMAC 校验不通过（key 不匹配或数据损坏）")
            null
        } else {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        EventLog.warn("CRED_CBC_ERR", t.javaClass.simpleName + "：" + t.message)
        null
    }

    /** 文件内容指纹（前 4 字节 hex）：排查「同 key 却解不开」时用来确认两次读的是不是同一份字节。 */
    private fun blobFp(b: ByteArray?): String {
        if (b == null) return "null"
        return try {
            val d = MessageDigest.getInstance("SHA-256").digest(b)
            "len=${b.size}:" + d.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Throwable) {
            "len=${b.size}:?"
        }
    }

    // ---- JSON 序列化 ----
    //
    // ⚠️ 2026-11-12 修复：原实现用手写 `String.format("{\"k\":\"%s\"}")` 拼接，
    // **完全没有转义**。凭据里只要出现一个 `"` 或 `\\`，产出的就不是合法 JSON；
    // 而 fromJson 用的正则 `"k"\\s*:\\s*"([^"]*)"` 会在内部引号处提前截断。
    //
    // 后果：**授权当下内存里有凭据（能用），一重启就解密/解析失败（用不了）**。
    // 宿主侧一直没暴露，是因为它每次启动还会从 remote prefs 快照里重新灌一次；
    // 模块侧没有那条后路，cred.bin 坏了就是彻底没凭据。
    // 改用 org.json（项目里已在用），由它负责转义与解析。
    private fun toJson(c: Credential): String = JSONObject().apply {
        put("watchImei", c.watchImei)
        put("signToken", c.signToken)
        put("encryToken", c.encryToken)
        put("passWord", c.passWord)
        put("vin", c.vin)
        put("identifier", c.identifier)
        put("identifierType", c.identifierType)
        put("vehicleBrand", c.vehicleBrand)
        put("appChannel", c.appChannel)
        put("autoType", c.autoType ?: "")
        put("tboxVersion", c.tboxVersion ?: "")
        put("acquireTokenTimestamp", c.acquireTokenTimestamp)
    }.toString()

    private fun fromJson(s: String): Credential? = try {
        val o = JSONObject(s)
        Credential(
            watchImei = o.optString("watchImei"),
            signToken = o.optString("signToken"),
            encryToken = o.optString("encryToken"),
            passWord = o.optString("passWord"),
            vin = o.optString("vin"),
            identifier = o.optString("identifier"),
            identifierType = o.optString("identifierType"),
            vehicleBrand = o.optString("vehicleBrand"),
            appChannel = o.optString("appChannel"),
            autoType = o.optString("autoType").ifEmpty { null },
            tboxVersion = o.optString("tboxVersion").ifEmpty { null },
            acquireTokenTimestamp = o.optLong("acquireTokenTimestamp", 0L),
        )
    } catch (_: Throwable) {
        null
    }
}

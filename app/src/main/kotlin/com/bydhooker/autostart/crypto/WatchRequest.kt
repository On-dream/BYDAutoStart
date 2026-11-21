package com.bydhooker.autostart.crypto

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.core.StatusPoller
import com.bydhooker.autostart.core.Whitebox
import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.model.Credential
import com.bydhooker.autostart.model.StatusFrame
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * watch 通道请求构造 + HTTPS（`15` §7.1 / §7.2 十接口）。
 *
 * - 域名/路径【实测】E 路线（裁定 E 明码本地；**不得上传/公开**）
 * - 外层：wrapDevice → in0 → so.aesEncrypt → out0 → b64 → 前缀 F
 * - 内层：encryData(AES-256-CBC) + sign(HmacSHA256 sortKv+password=)
 * - 响应：in1 → so.decrypt → out1 → UTF-8 JSON（M-Q3 已证 so.decrypt 零偏差）
 * - Header：Content-Type / X-Request-id: android-<uuid> / User-Agent: okhttp/4.9.1
 */
object WatchRequest {

    /** 车云域名与接口路径（【实测】E 路线；仅本地明码）。 */
    internal const val BASE = "https://dilinkwatchapp-cn.byd.auto"
    private const val PATH_REMOTE_CONTROL = "/watch/control/rc/remoteControl"
    private const val PATH_STATUS_NOW = "/watch/control/rc/getStatusNow"
    private const val PATH_AWAKE = "/watch/control/rc/remoteAwakeRequest"
    private const val PATH_RT_REQ = "/watch/vehicleInfo/vehicle/vehicleRealTimeRequest"
    private const val PATH_RT_RES = "/watch/vehicleInfo/vehicle/vehicleRealTimeResult"

    /** 请求/响应外层信封首字符（E 路线【实测】：请求前缀 F）。 */
    private const val REQ_PREFIX = "F"

    @Volatile var statusSource: StatusPoller.StatusSource? = null
        private set

    /**
     * StatusSource 注入（SelfCheck/装配时调用）。
     *
     * M1 轮询取帧：改走 [fetchFreshStatus]（rt 两步 + 短轮询），不再用
     * [getStatusNow]。原路径单次约 3 s（getStatusNow 一跳 + `sleep(500)` +
     * `sleep(2000)`），叠加 1 s 固定延迟后**实际轮询周期 4 s**（2026-11-09
     * 00:11 真机实测），与设计 G-2 的 1 s 差 4 倍。
     * 门字段本来只有 rtResult（105 字段）才有，getStatusNow 那跳对 M1 也是浪费。
     */
    fun asStatusSource(): StatusPoller.StatusSource = object : StatusPoller.StatusSource {
        override fun fetch(cred: Credential?, budgetMs: Long): StatusFrame? {
            if (cred == null) return null
            return fetchFreshStatus(
                cred,
                com.bydhooker.autostart.bridge.ConfigStore.dataAgeLimitSec,
                pollMs = 150L,
                maxTries = 2,
                budgetMs = budgetMs,
            )
        }
    }

    fun initStatusSource() {
        if (statusSource == null) {
            statusSource = asStatusSource()
            StatusPoller.source = statusSource
        }
    }

    // ---- 外层构造 ----

    internal fun wrapAndEncrypt(innerJson: String, serviceTime: String): String? {
        if (!WrapDevice.tablesAllReady()) {
            EventLog.error("WHITEBOX_FAIL", "in0/out0/iv0 表未初始化")
            return null
        }
        val keys = WbKeys.load() ?: run {
            EventLog.error("WHITEBOX_FAIL", "wb_keys 未加载")
            return null
        }
        return try {
            val wrapped = WrapDevice.wrap(innerJson, serviceTime)
            val plain = WrapDevice.dataIn(wrapped)
            // E 路线 so_bridge 实测参数形态：key = wb_enc0 完整 360 hex 字符串
            // （parse 在 so 内部）；iv = IV0 表映射的 16 字节（非全零）
            val cipher = Whitebox.aesEncrypt(plain, keys.enc0Hex, WrapDevice.iv0())
            REQ_PREFIX + Base64.getEncoder()
                .encodeToString(WrapDevice.dataOut(cipher))
        } catch (t: Throwable) {
            EventLog.error("SEND_FAIL", "外层构造失败：${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 响应解密（**v3_decrypt.py 逐字口径**，2026-11-08 逐字节对比修正）：
     * `F/S 剥离 → b64(raw) → in1 查表(raw)+256 零 → so.aesDecrypt(wb_dec0, [IV1[0]]×16)
     *  → out1 查表(raw 长度) + 尾字节 PKCS7`。
     * 旧版漏 in1 整步、误用 OUT0/IV0（IN0/OUT0/IV0 是**请求方向**表；响应用 IN1/OUT1/IV1）。
     */
    internal fun decryptResponse(body: String): JSONObject? = try {
        val payload = body.trim()
        // 全接口统一：响应为 {code, response:"F…", message} JSON 包装（v3_verify_token.py:60
        // 对所有接口同口径），信封在 response 字段；无该字段时（纯信封）按原样处理
        val env = try {
            val obj = JSONObject(payload)
            if (obj.has("response")) obj.optString("response") else payload
        } catch (_: Throwable) {
            payload
        }
        val b64 = if (env.firstOrNull() == 'F' || env.firstOrNull() == 'S') {
            env.substring(1)
        } else env
        val raw = Base64.getDecoder().decode(b64)
        val n0 = raw.size
        val x = ByteArray(n0 + 256) { i ->
            if (i < n0) WbTables.IN1[raw[i].toInt() and 0xFF].toByte() else 0
        }
        val keys = WbKeys.load() ?: return null
        val iv1 = ByteArray(16) { WbTables.IV1[0].toByte() }
        val y = Whitebox.aesDecrypt(x, keys.dec0Hex, iv1)
        val arr = ByteArray(n0) { i -> WbTables.OUT1[y[i].toInt() and 0xFF].toByte() }
        var pad = arr[n0 - 1].toInt() and 0xFF
        if (pad == 0) pad = 256
        val n = if (n0 > pad) n0 - pad else n0
        JSONObject(String(arr, 0, n, StandardCharsets.UTF_8))
    } catch (t: Throwable) {
        EventLog.error("VERIFY", "响应解密失败：${t.javaClass.simpleName}")
        null
    }

    // ---- 内层构造 ----

    /**
     * buildInnerRaw：v3_build.build_inner:162 逐字口径——内层 plain 只含 inner_map，
     * identity 只进签名串与外层 body。getStatusNow 实测内层 = {vin} 单字段，
     * 塞 commandType 全家必 226（2026-11-08 诊断实测）。
     */
    internal fun buildInnerRaw(
        innerMap: List<Pair<String, Any?>>,
        cred: Credential,
        withAppChannel: Boolean = true,
    ): Triple<String, String, String>? {
        val reqTs = System.currentTimeMillis().toString()
        val identityAll = listOf(
            "identifier" to cred.identifier,
            "identifierType" to cred.identifierType,
            "objective" to cred.vin,
            "vehicleBrand" to cred.vehicleBrand,
            "appChannel" to cred.appChannel,
        )
        val identity = if (withAppChannel) identityAll else identityAll.filter { it.first != "appChannel" }
        // v3_build.build_inner:162 逐字口径：
        //   encryData = AES(py_value(inner_map))            ← plain 只含 inner_map
        //   sign      = HMAC(sortKv(inner_map + watchImei
        //               + reqTimestamp + identity) + password=signToken)
        //   body      = {encryData, reqTimestamp, watchImei, sign} + identity
        val plain = InnerCipher.watchPyValue(innerMap)
        val encryData = InnerCipher.aes256CbcEncrypt(
            plain.toByteArray(StandardCharsets.UTF_8), InnerCipher.deriveKey(cred.encryToken))
        val signEntries = innerMap +
            listOf("watchImei" to cred.watchImei, "reqTimestamp" to reqTs) + identity
        val sign = InnerCipher.hmacSha256(
            InnerCipher.sortKv(signEntries) + "password=" + cred.signToken, cred.signToken)
        val inner = listOf(
            "encryData" to encryData,
            "reqTimestamp" to reqTs,
            "watchImei" to cred.watchImei,
            "sign" to sign,
        ) + identity
        return Triple(InnerCipher.watchPyValue(inner), encryData, sign)
    }

    // ---- 内层构造（`15` §7.1；appChannel 双进 A15）----

    private fun buildInner(
        commandType: String,
        cred: Credential,
        extra: List<Pair<String, Any?>> = emptyList(),
        withAppChannel: Boolean = true,
    ): Triple<String, String, String>? {
        val reqTs = System.currentTimeMillis().toString()
        // appChannel 双进（A15）仅 remoteControl 实测要求；getStatusNow 探针实测
        // 6 次成功样本外层/内层均无 appChannel，且带了必 226（E-A2.8 实验二）
        val identityAll = listOf(
            "vin" to cred.vin, "identifier" to cred.identifier,
            "identifierType" to cred.identifierType,
            "vehicleBrand" to cred.vehicleBrand, "appChannel" to cred.appChannel,
        )
        val identity = if (withAppChannel) identityAll else identityAll.filter { it.first != "appChannel" }
        val bodyEntries = listOf(
            "commandType" to commandType,
            "commandPwd" to cred.passWord,
            "controlParamsMap" to "{}",
            "appVersion" to BridgeContract.APP_VERSION,
            "source" to "watch",
        ) + extra + identity
        val (encryData, sign) = InnerCipher.buildEncryptedBody(
            bodyEntries, cred.encryToken, cred.signToken)
        val inner = listOf(
            "encryData" to encryData,
            "reqTimestamp" to reqTs,
            "watchImei" to cred.watchImei,
            "sign" to sign,
        ) + identity
        return Triple(InnerCipher.watchPyValue(inner), encryData, sign)
    }

    // ---- HTTPS（`15` §3.4：connect 5s / read 8s；单次失败不重试 G-9）----

    /**
     * 本线程的超时覆盖（毫秒，[0]=connect、[1]=read）；null = 用配置值。
     *
     * 【为什么用 ThreadLocal 而不是加参数】`post` 被 rtRequest / rtResult / 十接口共用，
     * 一层层加参数会污染所有签名；而窗口执行全程在**同一个线程**里同步跑
     * （M2 是 `BYDHK-NFC-M2`，M1 是 NetExecutor 单线程），线程局部变量刚好够用。
     * 用完必须清（见 [withTimeout]），否则线程复用时会带着旧值。
     */
    private val timeoutOverride = ThreadLocal<IntArray?>()

    /**
     * 在指定超时下执行一段可能发 HTTP 的代码；结束后恢复。
     *
     * ⚠️ **异步代码不能用**（ThreadLocal 不跨线程传播）。当前调用点都是同步链路。
     */
    internal fun <T> withTimeout(connectMs: Int, readMs: Int, block: () -> T): T {
        val old = timeoutOverride.get()
        timeoutOverride.set(intArrayOf(connectMs, readMs))
        return try {
            block()
        } finally {
            if (old == null) timeoutOverride.remove() else timeoutOverride.set(old)
        }
    }

    /**
     * 当前生效的 (connect, read) 超时。
     *
     * 【为什么改成读配置】原来写死 5000/8000，而 `ConfigStore` 里明明有这两个配置项，
     * 设置页改了也不生效。默认值与旧硬编码一致，行为不变。
     */
    private fun timeouts(): Pair<Int, Int> {
        val ov = timeoutOverride.get()
        if (ov != null) {
            return ov[0] to ov[1]
        }
        val c = try {
            com.bydhooker.autostart.bridge.ConfigStore.connectTimeoutMs
        } catch (_: Throwable) {
            com.bydhooker.autostart.bridge.BridgeContract.DEF_CONNECT_TIMEOUT_MS
        }
        val r = try {
            com.bydhooker.autostart.bridge.ConfigStore.readTimeoutMs
        } catch (_: Throwable) {
            com.bydhooker.autostart.bridge.BridgeContract.DEF_READ_TIMEOUT_MS
        }
        return c to r
    }

    internal fun post(path: String, requestPayload: String): Pair<Int, String>? = try {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        val (ct, rt) = timeouts()
        conn.connectTimeout = ct
        conn.readTimeout = rt
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("X-Request-id", "android-" + UUID.randomUUID())
        conn.setRequestProperty("User-Agent", "okhttp/4.9.1")
        conn.outputStream.use { it.write(requestPayload.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(CharsetReader.UTF8)?.use { it.readText() } ?: ""
        code to body
    } catch (t: Throwable) {
        EventLog.warn("NET_TIMEOUT", "HTTPS ${t.javaClass.simpleName}")
        null
    }

    private object CharsetReader {
        val UTF8 = StandardCharsets.UTF_8
    }

    // ---- 十接口中本模块用到的四个（其余 6 个在授权/诊断流程，T05 接入）----

    /** getStatusNow：轮询/取 requestSerial（105 字段 → StatusFrame 消费子集）。 */
    fun getStatusNow(cred: Credential?): StatusFrame? {
        if (cred == null) return null
        val (inner, _, _) = buildInnerRaw(listOf("vin" to cred.vin), cred, withAppChannel = false)
            ?: return null
        val st = (System.currentTimeMillis() / 1000).toString()
        val payload = wrapAndEncrypt(inner, st) ?: return null
        val (code, body) = post(PATH_STATUS_NOW, "{\"request\":\"$payload\"}") ?: return null
        if (code !in 200..299) {
            EventLog.warn("NET", "getStatusNow HTTP $code")
            return null
        }
        val json = decryptResponse(body) ?: return null
        // respondData 为固定回执（14 F-A）——车况在 respondData（AES key=**encryToken**，
        // v3_verify_token.py:82 实测口径；注意与 watchLogin 族的 watchImei key 不同）。
        // 响应字段顶层平铺（rt-result-1.json 实测：leftFrontDoor/powerGear/leftFrontDoorLock/vehicleState）
        var data = json
        val rd = json.optString("respondData", "")
        if (rd.isNotEmpty()) {
            try {
                data = JSONObject(String(
                    InnerCipher.aes256CbcDecrypt(rd, InnerCipher.deriveKey(cred.encryToken)),
                    StandardCharsets.UTF_8))
            } catch (t: Throwable) {
                EventLog.warn("VERIFY", "statusNow respondData 解密失败：${t.javaClass.simpleName}")
            }
        }
        val base = StatusFrame(
            timeSec = (data.opt("time") as? Number)?.toLong()
                ?: (data.optLong("time", System.currentTimeMillis() / 1000)),
            leftFrontDoor = data.optInt("leftFrontDoor", -1).takeIf { it >= 0 },
            powerGear = data.optInt("powerGear", -1).takeIf { it >= 0 },
            requestSerial = json.optString("requestSerial", null),
            doorLockFL = data.optInt("leftFrontDoorLock", -1).takeIf { it >= 0 },
            vehicleState = data.optInt("vehicleState", -1).takeIf { it >= 0 },
        )
        // getStatusNow 66 字段无门/锁——vehicleRealTimeRequest/Result 两步补
        //（v3_verify_token.py main 实测：req {vin,energyType,version,tboxVersion} →
        //  serial → 2s → result → 105 字段平铺）
        if (base.leftFrontDoor == null) {
            try {
                Thread.sleep(500)
                val serial = rtRequest(cred) ?: return base
                Thread.sleep(2000)
                return rtResult(cred, serial) ?: base
            } catch (_: Throwable) {
            }
        }
        return base
    }

    /** vehicleRealTimeRequest → requestSerial（v3_verify_token.py:99 内层实测）。 */
    private fun rtRequest(cred: Credential): String? {
        val (inner, _, _) = buildInnerRaw(
            listOf(
                "vin" to cred.vin,
                "energyType" to "0",
                "version" to "570",
                "tboxVersion" to (cred.tboxVersion ?: "3"),
            ),
            cred,
        ) ?: return null
        val st = System.currentTimeMillis().toString()
        val payload = wrapAndEncrypt(inner, st) ?: return null
        val reqBody = InnerCipher.watchPyValue(listOf("request" to payload))
        val (code, body) = post(PATH_RT_REQ, reqBody) ?: return null
        if (code !in 200..299) return null
        val json = decryptResponse(body) ?: return null
        var d: JSONObject = json
        val rd = json.optString("respondData", "")
        if (rd.isNotEmpty()) {
            try {
                d = JSONObject(String(
                    InnerCipher.aes256CbcDecrypt(rd, InnerCipher.deriveKey(cred.encryToken)),
                    StandardCharsets.UTF_8))
            } catch (_: Throwable) {
            }
        }
        val s1 = d.optString("requestSerial", "")
        if (s1.isNotEmpty()) return s1
        return d.optJSONObject("data")?.optString("requestSerial", "")?.ifEmpty { null }
    }

    /** vehicleRealTimeResult → 105 字段平铺 StatusFrame（v3_verify_token.py:105 内层实测）。 */
    private fun rtResult(cred: Credential, serial: String): StatusFrame? {
        val (inner, _, _) = buildInnerRaw(
            listOf(
                "vin" to cred.vin,
                "energyType" to "0",
                "tboxVersion" to (cred.tboxVersion ?: "3"),
                "requestSerial" to serial,
                "version" to "570",
                "support" to "",
            ),
            cred,
        ) ?: return null
        val st = System.currentTimeMillis().toString()
        val payload = wrapAndEncrypt(inner, st) ?: return null
        val reqBody = InnerCipher.watchPyValue(listOf("request" to payload))
        val (code, body) = post(PATH_RT_RES, reqBody) ?: return null
        if (code !in 200..299) return null
        val json = decryptResponse(body) ?: return null
        var d: JSONObject = json
        val rd = json.optString("respondData", "")
        if (rd.isNotEmpty()) {
            try {
                d = JSONObject(String(
                    InnerCipher.aes256CbcDecrypt(rd, InnerCipher.deriveKey(cred.encryToken)),
                    StandardCharsets.UTF_8))
            } catch (_: Throwable) {
            }
        }
        val dd = d.optJSONObject("data") ?: d
        return StatusFrame(
            timeSec = (dd.opt("time") as? Number)?.toLong()
                ?: (dd.optLong("time", System.currentTimeMillis() / 1000)),
            leftFrontDoor = dd.optInt("leftFrontDoor", -1).takeIf { it >= 0 },
            powerGear = dd.optInt("powerGear", -1).takeIf { it >= 0 },
            requestSerial = serial,
            doorLockFL = dd.optInt("leftFrontDoorLock", -1).takeIf { it >= 0 },
            vehicleState = dd.optInt("vehicleState", -1).takeIf { it >= 0 },
        )
    }

    /**
     * remoteControl 结果：HTTP 状态 + 解密后 JSON。
     * `body == null` 表示 HTTP 非 2xx 或响应解密失败（此时用 [httpCode] 报错，0 = 网络异常）。
     */
    data class RemoteControlResult(val httpCode: Int, val body: JSONObject?)

    /**
     * remoteControl 下发（`15` §7.1；**唯一对外入口**，core 只调本方法）。
     *
     * 内层 = `buildInnerRaw(innerMap, withAppChannel = true)` —— remoteControl 是
     * 「双进」（A15：appChannel 同时进 body 与签名串），与 getStatusNow
     * （`withAppChannel = false`）相反，**不得混用**。
     * 外层 = wrapAndEncrypt(inner, serviceTime=now) → POST PATH_REMOTE_CONTROL。
     *
     * 只做「发一次 + 解密」，**不判定成功、不重试**（判定在 CommandSender，G-9）。
     *
     * @return null = 报文构造失败；否则携带 HTTP 状态与响应 JSON（可能为 null）
     */
    fun remoteControl(
        innerMap: List<Pair<String, Any?>>,
        cred: Credential,
    ): RemoteControlResult? {
        val (inner, _, _) = buildInnerRaw(innerMap, cred, withAppChannel = true) ?: return null
        val st = System.currentTimeMillis().toString()
        val payload = wrapAndEncrypt(inner, st) ?: return null
        val res = post(PATH_REMOTE_CONTROL, "{\"request\":\"$payload\"}")
            ?: return RemoteControlResult(0, null)
        val (code, body) = res
        if (code !in 200..299) return RemoteControlResult(code, null)
        return RemoteControlResult(code, decryptResponse(body))
    }

    /**
     * **M2 专用取帧（B 优化，2026-11-08 真机实测后）**：
     * 跳过 `getStatusNow` 那一跳，**直接 rt 两步**，并用 `pollMs` 轮询替代
     * 原来 `getStatusNow` 里的 `sleep(500)` + `rtRequest→rtResult` 的 `sleep(2000)`。
     *
     * 为什么可以跳过：M2 只消费 `time`（新鲜度）+ `powerGear`（G-6 已上电），
     * **不需要 `leftFrontDoor`**（那是 M1 门开判据）。原路径先查 66 字段的
     * `getStatusNow`、发现无门字段才补 rt 两步，对 M2 是纯浪费一跳。
     *
     * 数据源一致性：`powerGear` 在 `getStatusNow`（座舱）与 `vehicleRealTimeResult`
     * （TBOX）取值不同（E 路线 C.4 实测 0 vs 1），**必须固定用 rtResult 这一源**，
     * 故本方法不使用 `getStatusNow` 的帧。
     *
     * @param maxAgeSec 新鲜度阈值（M2 固定 30，见 `BridgeContract.M2_STATUS_MAX_AGE_SEC`）
     * @param pollMs    轮询间隔（默认 250 ms）
     * @param maxTries  最多查询次数（默认 8 → 上限约 4 s，通常 1~2 次即新鲜）
     * @return 最新帧（可能仍陈旧，由调用方判定）；`null` = rtRequest 失败
     */
    /**
     * 取一帧新鲜车况（rt 两步 + 短轮询）。
     *
     * @param budgetMs **总时间预算**（毫秒）。0 = 不设限（旧行为，授权/诊断流程用）。
     *
     * 【为什么需要预算】2026-11-12 22:5x 核对：单帧最多 3 次 HTTP
     * （`rtRequest` + 两次 `rtResult`），而 `post` 默认 connect 5s / read 8s
     * ⇒ **单帧最坏 39 秒**。而 NFC-M2 的窗口只有 **15 秒** —— 只要第一次请求卡住，
     * 整个窗口就废了，日志上表现为 `NFC_M2_TIMEOUT`，与「判据不成立」无法区分。
     *
     * 传了 budget 之后：每步超时按剩余时间动态收缩（connect ≤ left/3、read ≤ left/2），
     * 预算耗尽立即返回已拿到的帧（可能为 null），**绝不把整个窗口耗在一次请求上**。
     */
    fun fetchFreshStatus(
        cred: Credential,
        maxAgeSec: Int,
        pollMs: Long = 250L,
        maxTries: Int = 8,
        budgetMs: Long = 0L,
    ): StatusFrame? {
        val t0 = System.currentTimeMillis()
        // Long.MAX_VALUE 表示「不限」，避免 left() 参与除法时溢出
        val unlimited = budgetMs <= 0L
        fun left(): Long =
            if (unlimited) Long.MAX_VALUE else (budgetMs - (System.currentTimeMillis() - t0))

        val serial = guardedStep(left()) { rtRequest(cred) } ?: return null
        var last: StatusFrame? = null
        var i = 0
        while (i < maxTries) {
            if (left() <= 700L) {
                // 预算不足再来一次：把已有的（可能是陈旧帧）交回去，让上层自己判新鲜度
                return last
            }
            val f = guardedStep(left()) { rtResult(cred, serial) }
            if (f != null) {
                last = f
                val age = System.currentTimeMillis() / 1000 - f.timeSec
                if (age <= maxAgeSec) return f
            }
            if (i < maxTries - 1) {
                val l = left()
                if (l <= 900L) return last
                try {
                    Thread.sleep(if (unlimited) pollMs else minOf(pollMs, l - 600L).coerceAtLeast(0L))
                } catch (_: InterruptedException) {
                    return last
                }
            }
            i++
        }
        return last
    }

    /**
     * 在剩余预算内执行一次 HTTP 步骤。
     *
     * - 预算耗尽（≤700ms）直接放弃，不再发请求
     * - 单步超时按预算收缩：connect ≤ 3000 且 ≤ left/3、read ≤ 5000 且 ≤ left/2
     *   （留出余量给另一步，避免第一步就把预算吃光）
     * - 不限预算时保持原行为（走 [timeouts] 的配置值）
     */
    private fun <T> guardedStep(leftMs: Long, block: () -> T): T? {
        if (leftMs == Long.MAX_VALUE) {
            return block()
        }
        if (leftMs <= 700L) {
            return null
        }
        val conn = minOf(3000L, leftMs / 3).toInt().coerceAtLeast(600)
        val read = minOf(5000L, leftMs / 2).toInt().coerceAtLeast(900)
        return withTimeout(conn, read, block)
    }

    /** remoteAwakeRequest：G-4 唤醒（本窗最多 1 次；有效性【不知道】）。 */
    fun awake(cred: Credential?): Boolean {
        if (cred == null) return false
        val (inner, _, _) = buildInner("remoteAwakeRequest", cred) ?: return false
        val st = (System.currentTimeMillis() / 1000).toString()
        val payload = wrapAndEncrypt(inner, st) ?: return false
        val (code, _) = post(PATH_AWAKE, "{\"request\":\"$payload\"}") ?: return false
        return code in 200..299
    }
}


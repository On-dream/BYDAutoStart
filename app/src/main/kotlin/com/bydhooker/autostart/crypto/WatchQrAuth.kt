package com.bydhooker.autostart.crypto

import com.bydhooker.autostart.core.EventLog
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * 扫码授权三接口（`15` §7.2 #1–#3；T05 授权部分前移，2026-11-08 用户拍板）。
 *
 * **逐字复刻 E 路线实测实现**（`research/车控逆向/E-路线/evidence/v3/v3_qr_v2.py`，
 * 2026-11-08 02:02 真机打通授权；路径与 body 形态见 `evidence/v2/probe-qr-raw.log`
 * 抓包 + `E-A2-扫码获取token流程还原.md` §A2.9.6）。授权前无 token →
 * **签名与内层 AES 的 key 均为 watchImei 字符串**（与 token 阶段的
 * encryToken/signToken 不同源，勿混用）。
 *
 * 实测铁律（E-A2.9，违反即 226/61/500）：
 * - getToken 的 appChannel **必须同时进 body 与签名串**（缺一 → 226）；
 * - appChannel 从 getQRCodeStatus 响应 `data.appChannel` 取（确认后出现）；
 * - codeStatus：10000 待扫码 / 20000 已扫码 / **30000 确认中=唯一可换窗口** /
 *   40000 已授权（uuid 已消费，换必 61）；
 * - 成功判据 = getToken 响应 `data.signToken` 非空（A2.9.3）。
 */
object WatchQrAuth {

    private const val P_CREATE = "/watch/watchApp/login/watchLogin/createQrCode"
    private const val P_STATUS = "/watch/watchApp/login/watchLogin/getQRCodeStatus"
    private const val P_TOKEN = "/watch/watchApp/login/watchLogin/getToken"
    private const val P_VEHICLE_INFO = "/watch/watchApp/login/watchLogin/getVehicleInfo"

    /** 二维码内容前缀（E-A2.1【实测】官方 App 可识别）。 */
    const val QR_PREFIX = "watchQRCode://yangwang=SEC_PRE"

    /** 授权阶段 watchAppVersion（抓包实测 "470"；与 token 阶段 APP_VERSION=570 不同源）。 */
    private const val WATCH_APP_VERSION = "470"

    /** 单轮出码窗口（E-A2.1 ROUND_SECS=110）。 */
    const val ROUND_SECS = 110L

    /** 轮询间隔（E-A2.1 POLL_MS=2500）。 */
    private const val POLL_MS = 2500L

    /** 自建 watchImei = b64(32 随机字节)（E-A2.9.2【实测】可自建、不顶掉车控 App 授权）。 */
    fun newWatchImei(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Base64.getEncoder().encodeToString(b)
    }

    /** 授权阶段签名：sign 串 = sortKv(entries) + "password=" + wi，HMAC key = wi。 */
    private fun authSign(entries: List<Pair<String, Any?>>, wi: String): String =
        InnerCipher.hmacSha256(InnerCipher.sortKv(entries) + "password=" + wi, wi)

    /** 授权阶段内层 AES：key = b64d(watchImei)（44 字符 b64 → 恰 32 字节）。 */
    private fun authEncryptHex(plain: String, wi: String): String =
        InnerCipher.aes256CbcEncrypt(plain.toByteArray(StandardCharsets.UTF_8), InnerCipher.deriveKey(wi))

    /** 统一发送（v3_qr.py send_raw 逐字口径）：
     *  - 外层 serviceTime = **毫秒**（v3_qr.py:60；秒是未实测的错误口径）；
     *  - 响应 = `{code, response:"F…", message}` JSON 包装，信封在 response 字段；
     *  - respondData（AES-256-CBC key=watchImei）二次解出 data。 */
    private fun send(path: String, innerJson: String, wi: String): JSONObject? {
        val st = System.currentTimeMillis().toString()
        val payload = WatchRequest.wrapAndEncrypt(innerJson, st) ?: return null
        // HTTP body 用 py_value 风格（`": "` 带空格）——v3_qr.py send_raw 实测口径
        val reqBody = InnerCipher.watchPyValue(listOf("request" to payload))
        val (code, body) = WatchRequest.post(path, reqBody) ?: return null
        if (code !in 200..299) {
            EventLog.warn("AUTH_NET", "$path HTTP $code")
            return null
        }
        val obj = try {
            JSONObject(body)
        } catch (_: Throwable) {
            null
        }
        val env = if (obj != null && obj.has("response")) obj.optString("response") else body
        val inner = WatchRequest.decryptResponse(env)
        if (inner == null) {
            // 明码授权范围：落响应原文前 160 字符定位格式差异（仅本地）
            EventLog.error("AUTH_NET", "$path 解密失败 HTTP=$code body[:160]=${body.take(160)}")
            return null
        }
        val rd = inner.optString("respondData", "")
        if (rd.isNotEmpty()) {
            try {
                val plain = InnerCipher.aes256CbcDecrypt(rd, InnerCipher.deriveKey(wi))
                inner.put("data", JSONObject(String(plain, StandardCharsets.UTF_8)))
            } catch (t: Throwable) {
                EventLog.warn("AUTH_NET", "$path respondData 解密失败：${t.javaClass.simpleName}")
            }
        }
        return inner
    }

    /**
     * createQrCode：body 为**明文字段**（无 encryData——抓包实证），
     * 返回 96 hex uuid；失败返回 null。
     */
    fun createQr(wi: String): String? {
        val ts = System.currentTimeMillis().toString()
        val body = arrayListOf(
            "watchImei" to wi,
            "watchModel" to "WATCH",
            "watchBrand" to "byd",
            "watchName" to "WATCH",
            "reqTimestamp" to ts,
            "watchAppVersion" to WATCH_APP_VERSION,
            "watchOs" to "ANDROID",
        )
        val sign = authSign(body, wi)
        body.add("sign" to sign)
        val json = send(P_CREATE, InnerCipher.watchPyValue(body), wi) ?: return null
        val uuid = json.optJSONObject("data")?.optString("uuid")?.takeIf { it.isNotEmpty() }
        if (uuid == null) {
            EventLog.warn("AUTH", "createQrCode 无 uuid：code=${json.opt("code")} msg=${json.opt("message")}")
        }
        return uuid
    }

    data class Status(val codeStatus: Int, val appChannel: String?)

    /**
     * getQRCodeStatus：encryData 只包 `{uuid}`（key=watchImei）；签名串无 appChannel。
     */
    fun qrCodeStatus(uuid: String, wi: String): Status? {
        val ts = System.currentTimeMillis().toString()
        val encry = authEncryptHex(InnerCipher.watchPyValue(listOf("uuid" to uuid)), wi)
        val sign = authSign(listOf("uuid" to uuid, "watchImei" to wi, "reqTimestamp" to ts), wi)
        val body = listOf(
            "encryData" to encry,
            "reqTimestamp" to ts,
            "watchImei" to wi,
            "sign" to sign,
        )
        val json = send(P_STATUS, InnerCipher.watchPyValue(body), wi) ?: return null
        val d = json.optJSONObject("data") ?: return null
        val cs = (d.opt("codeStatus") as? Number)?.toInt() ?: d.optInt("codeStatus", -1)
        return Status(cs, d.optString("appChannel", "").takeIf { it.isNotEmpty() })
    }

    /**
     * getToken：appChannel 双进（body + 签名串；A15 同源铁律）。appChannel 未知时
     * 不带（v3_qr_v2 实测口径：appChannel=null 的轮次不加）。
     * 返回 token 响应 data 节点（signToken/encryToken/passWord/identifier/objective/…），
     * 未成功返回 null。
     */
    fun getToken(uuid: String, wi: String, appChannel: String?): JSONObject? {
        val ts = System.currentTimeMillis().toString()
        val encry = authEncryptHex(InnerCipher.watchPyValue(listOf("uuid" to uuid)), wi)
        val signed = arrayListOf(
            "uuid" to uuid,
            "watchImei" to wi,
            "reqTimestamp" to ts,
        )
        val body = arrayListOf(
            "encryData" to encry,
            "reqTimestamp" to ts,
            "watchImei" to wi,
        )
        if (appChannel != null) {
            signed.add("appChannel" to appChannel)
        }
        val sign = authSign(signed, wi)
        body.add("sign" to sign)
        if (appChannel != null) {
            body.add("appChannel" to appChannel)
        }
        val json = send(P_TOKEN, InnerCipher.watchPyValue(body), wi) ?: return null
        val d = json.optJSONObject("data") ?: return null
        // 成功判据 = signToken 非空（E-A2.9.3；codeStatus 语义不作门槛）
        return if (d.optString("signToken", "").isNotEmpty()) d else null
    }

    /**
     * getVehicleInfo（token 后；v3_verify_token.send / v3_keymaterial.py 实测口径）：
     * 内层 = {appVersion, softType, terminalType} + identity 5 字段；
     * respondData 用 **encryToken** 解（v3_verify_token.py:82——与 watchLogin 族
     * 的 watchImei key 不同源！）。返回 data 节点（含 vehicleInfo.vehicleBasic）。
     */
    fun getVehicleInfo(
        encryToken: String,
        signToken: String,
        wi: String,
        identifier: String,
        identifierType: String,
        vin: String,
        vehicleBrand: String,
        appChannel: String,
    ): JSONObject? {
        val tmpCred = com.bydhooker.autostart.model.Credential(
            watchImei = wi, signToken = signToken, encryToken = encryToken,
            passWord = "", vin = vin, identifier = identifier,
            identifierType = identifierType, vehicleBrand = vehicleBrand,
            appChannel = appChannel, autoType = null, tboxVersion = null,
            acquireTokenTimestamp = 0L,
        )
        // v3_keymaterial.py（**成功取到车型/车牌**）实测口径：identity 5 字段含
        // appChannel 且双进 body + 签名串（v3_build.build_inner:167-174：
        // out.update(identity) + signed.update(identity)）。车控 App vehicleInfo()
        // 同样带 appChannel。22:22「带则 226」是把 remoteControl 接口 E-A2.8 铁律
        // 误用到本接口，与跑通 python 直接矛盾 → 改回 true。
        EventLog.info("AUTH",
            "getVehicleInfo appChannel=" + (if (appChannel.isNotEmpty() && appChannel != "99") "有值" else "空/兜底") + " withAppChannel=true")
        val (inner, _, _) = WatchRequest.buildInnerRaw(
            listOf(
                "appVersion" to "570",
                "softType" to "0",
                "terminalType" to "0",
            ),
            tmpCred,
            withAppChannel = true,
        ) ?: return null
        val json = send(P_VEHICLE_INFO, inner, wi) ?: return null
        val rd = json.optString("respondData", "")
        if (rd.isEmpty()) {
            // 明码授权范围：落业务响应定位（仅本地）
            EventLog.warn("AUTH", "vehicleInfo 无 respondData：code=" + json.opt("code")
                + " msg=" + json.opt("message") + " keys=" + json.keys().asSequence().toList())
            return null
        }
        return try {
            JSONObject(String(
                InnerCipher.aes256CbcDecrypt(rd, InnerCipher.deriveKey(encryToken)),
                StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            EventLog.warn("AUTH", "vehicleInfo respondData 解密失败：" + t.javaClass.simpleName)
            null
        }
    }

    /** 深度优先找 key（keymaterial.json 层级不定，vehicleBasic 包装层不假设）。 */
    fun deepFind(obj: Any?, key: String): String? {
        when (obj) {
            is JSONObject -> {
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.opt(k)
                    if (k == key && v !is JSONObject && v !is org.json.JSONArray) {
                        return v?.toString()
                    }
                    deepFind(v, key)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) deepFind(obj.opt(i), key)?.let { return it }
            }
        }
        return null
    }
}

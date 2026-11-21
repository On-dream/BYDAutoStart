package com.bydhooker.autostart.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * watch 通道内层加密（`15` §7.1 / §4.3 A15）。
 *
 * 算法与参数**逐字复刻 E 路线实测实现**（`research/车控逆向/E-路线/evidence/v2`，
 * 13/13 逐字节复算；`v3/v3_decrypt.py` 解密方向 3/3）。不引入任何第三方库。
 *
 * - encryData = hexUpper(AES-256-CBC(plain, key=b64d(encryToken) 补零/截断至 32B, iv=0))
 * - sign      = hexUpper(HmacSHA256(sortKv(body) + "password=" + signToken, key=signToken))
 *   * sortKv：按键名字典序 `k=v&` 连接，**body 全部业务字段**，
 *     `appChannel` 必须同时出现在 body 与签名串（缺一 → 226/61，E 路线 F-E6）。
 * - WatchPy.value：Python 风格 JSON 序列化（`", "` / `": "` 分隔）——与服务端解析
 *   强耦合，**不得**改用紧凑分隔符。
 */
object InnerCipher {

    /** Python `json.dumps(obj)` 默认形态：键保持插入序、`, ` 与 `: ` 分隔。 */
    fun watchPyValue(entries: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("{")
        entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(", ")
            sb.append('"').append(escape(k)).append("\": ")
            sb.append(pyValue(v))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun pyValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> pyDouble(v)
        is Float -> pyDouble(v.toDouble())
        is String -> '"'.toString() + escape(v) + '"'
        is Map<*, *> -> watchPyValue(v.entries.map { it.key.toString() to it.value })
        is List<*> -> "[" + v.joinToString(", ") { pyValue(it) } + "]"
        else -> '"' + escape(v.toString()) + '"'
    }

    /** Python 浮点 repr 尽量贴近（车端字段多为整型，此分支兜底）。 */
    private fun pyDouble(d: Double): String =
        if (d == d.toLong().toDouble() && !d.isInfinite()) "%d.0".format(d.toLong()) else d.toString()

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** 按键名字典序 `k=v&` 连接（签名串前置段；A15：覆盖 body 全部业务字段）。 */
    fun sortKv(entries: List<Pair<String, Any?>>): String =
        entries.sortedBy { it.first }.joinToString("&") { (k, v) ->
            "$k=${stringValue(v)}"
        } + "&"

    private fun stringValue(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }

    /** b64d(encryToken) → AES-256 密钥（补零 / 截断至 32 字节，【实测】E 路线）。 */
    fun deriveKey(encryTokenB64: String): ByteArray {
        val raw = Base64.getDecoder().decode(encryTokenB64)
        return ByteArray(32).also { out ->
            System.arraycopy(raw, 0, out, 0, minOf(raw.size, 32))
        }
    }

    fun aes256CbcEncrypt(plain: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return hexUpper(cipher.doFinal(plain))
    }

    fun aes256CbcDecrypt(cipherHex: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(hexToLower(cipherHex))
    }

    fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return hexUpper(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
    }

    fun hexUpper(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789ABCDEF"[v ushr 4])
            append("0123456789ABCDEF"[v and 0x0F])
        }
    }

    private fun hexToLower(s: String): ByteArray {
        val clean = if (s.length % 2 == 1) "0$s" else s
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4)
                or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** 组装一次请求的 encryData + sign（`15` §7.1；appChannel 双进由调用方保证）。 */
    fun buildEncryptedBody(
        bodyEntries: List<Pair<String, Any?>>,
        encryTokenB64: String,
        signToken: String,
    ): Pair<String, String> {
        val plain = watchPyValue(bodyEntries)
        val encryData = aes256CbcEncrypt(plain.toByteArray(StandardCharsets.UTF_8), deriveKey(encryTokenB64))
        val sign = hmacSha256(sortKv(bodyEntries) + "password=" + signToken, signToken)
        return encryData to sign
    }

    /** sha256（诊断用）。 */
    fun sha256Hex(data: ByteArray): String =
        hexUpper(MessageDigest.getInstance("SHA-256").digest(data))
}

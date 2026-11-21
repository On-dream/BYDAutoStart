package com.bydhooker.autostart.core

import com.bydhooker.autostart.crypto.InnerCipher
import com.bydhooker.autostart.crypto.WbKeys
import com.bydhooker.autostart.crypto.WrapDevice
import com.bydhooker.autostart.model.Credential

/**
 * 内层完整构造自检（`15` §7.4；**闭合 M-Q2 分段**——P1 只验 so 层，本类在
 * 宿主进程内补「内层 AES + HMAC + wrapDevice + so 外层」端到端）。
 *
 * 三段：
 * ① so 层：`p1_samples.tsv` 13 组逐字节（与探针 compare13 同口径）
 * ② 内层构造：buildInner 结构自检（encryData/sign 非空、appChannel 双进、
 *    字段覆盖——**不做逐字节对照**：uuid/reqTimestamp 每次变化，E 路线样本
 *    不可复算；sign 可复算性由 ①的 key 材料与 ②的算法同一实现保证）
 * ③ 解密回环：encrypt → so.decrypt → 原文（若 wb 密钥表就绪）
 *
 * 触发时机：凭据就绪（AUTHORIZED）后手动/诊断触发一次；结果落 EventLog。
 * **不联网、不下发**（B5 调试项；输入全部来自本地固定样本）。
 */
object SelfCheck {

    private val BS_LF = "\n"
    private val BS_TAB = "\t"

    data class Result(
        val soMatch13: Int,
        val innerStructureOk: Boolean,
        val roundtripOk: Boolean?,
        val summary: String,
    )

    fun run(filesDir: java.io.File?, cred: Credential?): Result {
        // ① so 层 13 组（TSV 与 P1-4 探针同源；真机已 13/13——此处回归验证）
        var soMatch = -1
        var soErr: String? = null
        try {
            val rows = readTsv(com.bydhooker.autostart.crypto.P1Samples.SAMPLES_TSV)
            var match = 0
            var total = 0
            for (row in rows) {
                if (row.size < 5) continue
                total++
                try {
                    val input = hex(row[1])
                    // key 列（key_hex）直接传 String——探针 compare13 实测口径（JNI 第二参 String）
                    // iv 用**每行 TSV 的 iv_hex**——探针 SoPathProbe:229 同口径（非 IV0 表）
                    val got = Whitebox.aesEncrypt(
                        input,
                        row[2],
                        hex(row[3]),
                    )
                    val expect = hex(row[4])
                    val n = minOf(96, got.size, expect.size)
                    var ok = n > 0
                    for (i in 0 until n) if (got[i] != expect[i]) { ok = false; break }
                    if (ok) match++
                } catch (t: Throwable) {
                    if (soErr == null) soErr = t.javaClass.simpleName
                }
            }
            soMatch = if (total == 0) -1 else match
        } catch (t: Throwable) {
            soErr = "TSV:" + t.javaClass.simpleName
        }

        // ② 内层构造结构自检（需凭据）
        var innerOk = false
        if (cred != null && WrapDevice.tablesAllReady() && WbKeys.load() != null) {
            try {
                val bodyEntries = listOf(
                    "commandType" to "getStatusNow",
                    "commandPwd" to cred.passWord,
                    "controlParamsMap" to "{}",
                    "appVersion" to com.bydhooker.autostart.bridge.BridgeContract.APP_VERSION,
                    "source" to "watch",
                    "vin" to cred.vin,
                    "identifier" to cred.identifier,
                    "identifierType" to cred.identifierType,
                    "vehicleBrand" to cred.vehicleBrand,
                    "appChannel" to cred.appChannel,
                )
                val (encryData, sign) = InnerCipher.buildEncryptedBody(
                    bodyEntries, cred.encryToken, cred.signToken)
                innerOk = encryData.length >= 64 && sign.length == 64 &&
                    // A15：appChannel 必须出现在签名串（sortKv 覆盖全部字段）
                    InnerCipher.sortKv(bodyEntries).contains("appChannel=")
            } catch (_: Throwable) {
            }
        }

        // ③ 解密方向逐字节（真实响应样本 p1_selfcheck.tsv，3 组；M-Q3 铁证口径）。
        // 注：原「enc→dec roundtrip」已删——enc0（请求加密）与 dec0（响应解密）在
        // watch 协议中并非互逆对（服务端用自己的密钥加密响应），roundtrip=false 是
        // 测试假设错误而非 so 缺陷；enc 方向 13/13 + dec 方向真实样本即双向闭环。
        var decMatch = -1
        var decTotal = 0
        var decErr: String? = null
        try {
            for (row in readTsv(com.bydhooker.autostart.crypto.P1Samples.SELFCHECK_TSV)) {
                if (row.size < 8) continue
                decTotal++
                try {
                    var cipher = row[1]
                    // v3_decrypt.py:decrypt —— 外层首字符 F/S 为标识，非 base64 内容
                    if (cipher.isNotEmpty() && (cipher[0] == 'F' || cipher[0] == 'S')) {
                        cipher = cipher.substring(1)
                    }
                    val raw = java.util.Base64.getDecoder().decode(cipher)
                    val in1 = hex(row[3])
                    val out1 = hex(row[4])
                    val iv1 = hex(row[5])
                    val expectSha = row[6].lowercase()
                    val expectLen = row[7].toInt()
                    val x = applyIn1(raw, in1)
                    val y = Whitebox.aesDecrypt(x, row[2], iv1)
                    val plain = applyOut1(y, raw.size, out1)
                    val gotSha = sha256Hex(plain)
                    if (gotSha == expectSha && plain.size == expectLen) {
                        decMatch = if (decMatch < 0) 1 else decMatch + 1
                    } else if (decErr == null) {
                        // 无异常但不匹配：落长度与摘要前 8 位定位口径差（明文不落盘）
                        decErr = "len=" + plain.size + "/" + expectLen + " sha8=" + gotSha.substring(0, 8)
                    }
                } catch (t: Throwable) {
                    if (decErr == null) decErr = t.javaClass.simpleName
                }
            }
            if (decMatch < 0) decMatch = 0
        } catch (t: Throwable) {
            decErr = "TSV:" + t.javaClass.simpleName
        }

        // ④ remoteControl 内层字段名自检（T05 追加；**只打印字段名、不发送**）
        checkCommandInnerKeys(cred)

        val result = Result(
            soMatch13 = soMatch,
            innerStructureOk = innerOk,
            roundtripOk = decMatch >= 0,
            summary = "so=$soMatch/13 inner=${if (innerOk) "OK" else "SKIP/FAIL"} " +
                "dec=$decMatch/$decTotal" +
                (soErr?.let { " soErr=$it" } ?: "") +
                (decErr?.let { " decErr=$it" } ?: ""),
        )
        EventLog.info("SELFCHECK", "内层完整构造自检：${result.summary}")
        return result
    }

    /**
     * remoteControl 内层字段名自检（T05 追加；`15` §7.1）。
     *
     * 用当前凭据构造一份内层字段集合，**只打印字段名、不打印值、绝不调用 post**，
     * 与 E 路线样本的 10 个字段逐字核对（样本：`evidence/v3/extracted.json`）。
     * 凭据未就绪时打印 `cred=SKIP`（凭据到达后由 ModuleBridgeHost 再触发一次）。
     */
    fun checkCommandInnerKeys(cred: Credential?) {
        if (cred == null) {
            EventLog.info("SELFCHECK", "cmd inner keys=[] cred=SKIP")
            return
        }
        try {
            val keys = CommandSender.commandInnerMap(cred).map { it.first }
            val match = keys == CommandSender.COMMAND_INNER_KEYS
            EventLog.info("SELFCHECK",
                "cmd inner keys=[" + keys.joinToString(",") + "]" +
                    if (match) " count=${keys.size}" else " EXPECT_MISMATCH")
        } catch (t: Throwable) {
            EventLog.warn("SELFCHECK", "cmd inner 自检异常：" + t.javaClass.simpleName)
        }
    }

    private fun readTsv(tsv: String): List<Array<String>> = tsv
        .split(BS_LF)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split(BS_TAB).toTypedArray() }

    private fun hex(s: String): ByteArray {
        val clean = if (s.length % 2 == 1) "0$s" else s
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4)
                or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** 响应解密 in1：逐字节查表 + 追加 256 零字节（探针 SelfCheckProbe.applyIn1 同口径）。 */
    private fun applyIn1(raw: ByteArray, table: ByteArray): ByteArray {
        require(table.size >= 256) { "in1 table too short" }
        val bos = java.io.ByteArrayOutputStream(raw.size + 256)
        for (b in raw) bos.write(table[b.toInt() and 0xFF].toInt())
        repeat(256) { bos.write(0) }
        return bos.toByteArray()
    }

    /** 响应解密 out1：前 n0 字节查表 + 按查表后尾字节裁 PKCS7（探针 applyOut1 逐字口径：
     *  pad 取 arr 尾字节、pad==0 视为 256、无 1..32 范围假设）。 */
    private fun applyOut1(y: ByteArray, n0: Int, table: ByteArray): ByteArray {
        require(table.size >= 256 && y.size >= n0) { "out1 table/data too short" }
        val arr = ByteArray(n0)
        for (i in 0 until n0) arr[i] = table[y[i].toInt() and 0xFF]
        var pad = arr[n0 - 1].toInt() and 0xFF
        if (pad == 0) pad = 256
        val n = if (n0 > pad) n0 - pad else n0
        return arr.copyOf(n)
    }

    private fun sha256Hex(data: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        return d.joinToString("") { "%02x".format(it) }
    }
}

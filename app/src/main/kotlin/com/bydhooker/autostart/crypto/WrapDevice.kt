package com.bydhooker.autostart.crypto

import com.bydhooker.autostart.core.EventLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * wrapDevice 信封 + in0/out0 字节表变换（`15` §7.1；逐字复刻 E 路线
 * `wbc_selftest.py` `wrap_device`/`obf_md5`/`DATA_IN0`/`DATA_OUT0`——13/13 实测口径）。
 *
 * - 字节表来自 **`车控.apk`**（`com.qinl.car.pub`）的 `assets/byd/predata_tables.json`
 *   （已复制进本模块 assets，文件名 `byd_predata_tables.json`；
 *   键 `T_PRE_DATA_IN0` / `T_PRE_DATA_OUT0`；与 APK 原件逐字节相同）。
 * - `obf_md5`：MD5 字节**重排**（`d[12:16]+d[4:8]+d[8:12]+d[0:4]` hex）——不是标准 MD5。
 * - 设备表固定值（`DEVICE_DEFAULT`）：`ostype=and / imei=BANGCLE01234 /
 *   mac=00:00:00:00:00:00 / model=WATCH / sdk=35 / mod=Xiaomi`（探针实测同值）。
 */
object WrapDevice {

    private const val DEVICE_DEFAULT_OSTYPE = "and"
    private const val DEVICE_DEFAULT_IMEI = "BANGCLE01234"
    private const val DEVICE_DEFAULT_MAC = "00:00:00:00:00:00"
    private const val DEVICE_DEFAULT_MODEL = "WATCH"
    private const val DEVICE_DEFAULT_SDK = "35"
    private const val DEVICE_DEFAULT_MOD = "Xiaomi"

    /**
     * 白盒 IV：**IV0 表映射的 16 字节**（E 路线 `so_bridge.py` 实测：
     * `bytes(IV0[b] for b in bytes(16))`——**不是全零**，全零会导致密文错误）。
     */
    /**
     * IV（**wbc_selftest.py:182 权威口径**）：`bytes(IV0[b] for b in (0x00×16))`
     * = **[IV0[0]]×16**（对 16 个零字节查表），不是 IV0[0..15]——首装对比
     * python 管线逐字节 diff 定位的第 4 处复刻偏差。
     */
    fun iv0(): ByteArray = ByteArray(16) { WbTables.IV0[0].toByte() }

    fun tablesAllReady(): Boolean = WbTables.IN0.size == 256 && WbTables.OUT0.size == 256

    /** 字节经 in0 表映射（表为编译期常量，恒可用）。 */
    private fun mapIn(data: ByteArray): ByteArray =
        ByteArray(data.size) { i -> WbTables.IN0[data[i].toInt() and 0xFF].toByte() }

    private fun mapOut(data: ByteArray): ByteArray =
        ByteArray(data.size) { i -> WbTables.OUT0[data[i].toInt() and 0xFF].toByte() }

    /** 逐字段追加（Python append_field 逐字：`,"k":"v"` 拼接，处理 `]`/`}` 收尾）。 */
    private fun appendField(body: String, key: String, value: String): String = when {
        body.endsWith("]") ->
            body.substring(0, body.length - 2) + ",\"$key\":\"$value\"}]"
        body.endsWith("}") ->
            body.substring(0, body.length - 1) + ",\"$key\":\"$value\"}"
        else -> body
    }

    /** checkcode：MD5 字节重排 hex（**非标准 MD5 hex**，E 路线 obf_md5 逐字）。 */
    private fun obfMd5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
        return hex(d[12], d[13], d[14], d[15], d[4], d[5], d[6], d[7],
            d[8], d[9], d[10], d[11], d[0], d[1], d[2], d[3])
    }

    private fun hex(vararg bytes: Byte): String = buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }

    /**
     * wrapDevice：innerJson 逐字段追加 ostype/imei/mac/model/sdk/serviceTime/mod/checkcode。
     * @param serviceTime 服务时间（epoch ms 字符串；E 路线用固定值亦可——【实测】同值接受）
     */
    fun wrap(innerJson: String, serviceTime: String): String {
        var body = innerJson
        body = appendField(body, "ostype", DEVICE_DEFAULT_OSTYPE)
        body = appendField(body, "imei", DEVICE_DEFAULT_IMEI)
        body = appendField(body, "mac", DEVICE_DEFAULT_MAC)
        body = appendField(body, "model", DEVICE_DEFAULT_MODEL)
        body = appendField(body, "sdk", DEVICE_DEFAULT_SDK)
        body = appendField(body, "serviceTime", serviceTime)
        body = appendField(body, "mod", DEVICE_DEFAULT_MOD)
        return appendField(body, "checkcode", obfMd5(body))
    }

    /** in0 变换后的待加密字节（so.aesEncryptByteArr 输入）。 */
    fun dataIn(wrapped: String): ByteArray = mapIn(wrapped.toByteArray(StandardCharsets.UTF_8))

    /** out0 变换后的密文（再 Base64 + 前缀 F = 请求 body）。 */
    fun dataOut(cipher: ByteArray): ByteArray = mapOut(cipher)

}


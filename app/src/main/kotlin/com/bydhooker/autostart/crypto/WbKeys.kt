package com.bydhooker.autostart.crypto



/**
 * 白盒密钥材料（`wb_keys.json`：wb_enc0 / wb_enc1 / wb_dec0 / wb_dec1，各 360 hex 字符）。
 *
 * 来源：**`车控.apk`**（`com.qinl.car.pub`，第三方车控 App）的 `assets/byd/wb_keys.json`
 * （apktool 提取，与随包 so `libwbsk_crypto_tool.so` 配套的固定表——**不随凭据变化**），
 * 已复制进本模块 assets（`byd_wb_keys.json`，裁定 E 明码本地）。
 *
 * ⚠️ 2026-11-20 更正：旧注释写「来源：官方 App」，**与实测不符**——
 * 官方 `比亚迪.apk` 内不存在任何 `assets/byd*` json（0 命中），其同名 so 也只有 50,600 B 的
 * `laes*` 变体；本模块用的是 `车控.apk` 里 451,976 B 的 `aes*` 变体（sha256 `7b81bdc7…`）。
 * 证据见 `docs/41` §8。
 *
 * 参数形态（E 路线 `so_bridge.py` 实测）：so 的 aesEncrypt/Decrypt 接收
 * **完整 360 hex 字符串的 bytes**——parse（4 字节头 + b[i%3] 异或）在 so 内部完成。
 */
object WbKeys {

    data class Keys(
        val enc0Hex: String,   // 请求方向（360 hex 字符串，**直接传 so**——JNI 第二参 String）
        val dec0Hex: String,   // 响应方向
    )

    /** 编译期常量（WbTables），无需初始化。 */
    fun load(): Keys = Keys(WbTables.WB_ENC0, WbTables.WB_DEC0)
}

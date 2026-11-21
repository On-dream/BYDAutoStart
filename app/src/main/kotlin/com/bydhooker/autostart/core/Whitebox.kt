package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.model.Credential
import com.wbsk.CryptoTool
import java.io.File

/**
 * 白盒 so 桥（`15` §3.2 ⑩ / §7.1；加载路径照 P1-3 实测结论重写）。
 *
 * 路径顺序（B2 多路径对照，逐条尝试）：
 * ① `System.loadLibrary`（runtime 从模块 base.apk!/lib/arm64-v8a mmap；
 *    P1 首轮实证 extractNativeLibs=false 下 dlopen not found，保留对照）
 * ③ **宿主 code_cache 解出 → System.load（P1-3 实测 13/13 ✅）**
 * 路径②（APK 内直接映射）与①③物理重叠（同一 mmap 机制），不重复尝试。
 *
 * so 的 JNI 符号绑定 `com.wbsk.CryptoTool`（E 路线类名，不得改动）。
 * 加载失败 → state=FAILED → Gate 拒绝下发（15 §9「整个功能禁用」）。
 */
object Whitebox {

    private const val SO_NAME = "libwbsk_crypto_tool.so"
    private const val SO_ENTRY = "lib/arm64-v8a/$SO_NAME"

    @Volatile var state: com.bydhooker.autostart.model.WhiteboxState =
        com.bydhooker.autostart.model.WhiteboxState.UNKNOWN
        private set

    /** 懒加载；返回是否可用。 */
    @Synchronized
    fun ensureLoaded(moduleApkPath: String?, hostDataDir: String?): Boolean {
        if (CryptoTool.isLoaded()) {
            state = com.bydhooker.autostart.model.WhiteboxState.OK
            return true
        }
        // 路径①：loadLibrary（对照保留）
        if (CryptoTool.loadViaSystemLibrary()) {
            state = com.bydhooker.autostart.model.WhiteboxState.OK
            EventLog.info("WHITEBOX", "so 经 loadLibrary（APK 内 mmap）加载成功")
            return true
        }
        // 路径③：宿主 dataDir 派生 code_cache 解出（P1-3 ✅；宿主 UID 可写）
        if (moduleApkPath != null && hostDataDir != null) {
            val dir = File(hostDataDir, "code_cache" + File.separator + "bydhooker")
            val out = File(dir, SO_NAME)
            try {
                dir.mkdirs()
                extractTo(moduleApkPath, out)
                if (CryptoTool.loadFrom(out.absolutePath)) {
                    state = com.bydhooker.autostart.model.WhiteboxState.OK
                    EventLog.info("WHITEBOX", "so 经宿主 code_cache 加载成功（P1-3 路径③）")
                    return true
                }
                EventLog.error("WHITEBOX_FAIL", "so 解出成功但 System.load 失败")
            } catch (t: Throwable) {
                EventLog.error("WHITEBOX_FAIL", "so 解出/加载失败：${t.javaClass.simpleName}")
            }
        } else {
            EventLog.error("WHITEBOX_FAIL", "无可用的模块 APK 路径或宿主数据目录")
        }
        state = com.bydhooker.autostart.model.WhiteboxState.FAILED
        EventLog.error("WHITEBOX_FAIL", "加密组件加载失败，功能不可用（三路径全败，R-1 复核）")
        return false
    }

    /** 外层白盒加密（对 watch 请求 wrapped 信封）。key = hex 字符串（探针实测口径）。 */
    fun aesEncrypt(input: ByteArray, keyHex: String, iv: ByteArray): ByteArray =
        CryptoTool.aesEncryptByteArr(input, keyHex, iv)

    /** 外层白盒解密（对 watch 响应信封；M-Q3 实证与 E 路线逐字节一致）。 */
    fun aesDecrypt(input: ByteArray, keyHex: String, iv: ByteArray): ByteArray =
        CryptoTool.aesDecryptByteArr(input, keyHex, iv)

    private fun extractTo(apkPath: String, out: File) {
        java.util.zip.ZipFile(apkPath).use { zf ->
            val e = zf.getEntry(SO_ENTRY) ?: throw IllegalStateException("SoEntryMissing")
            zf.getInputStream(e).use { ins ->
                out.outputStream().use { fos -> ins.copyTo(fos) }
            }
        }
    }
}

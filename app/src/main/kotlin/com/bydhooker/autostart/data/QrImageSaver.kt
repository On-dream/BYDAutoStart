package com.bydhooker.autostart.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.bydhooker.autostart.core.EventLog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/**
 * 把二维码内容画成 PNG 并写入**系统相册**（2026-11-09）。
 *
 * 用途：官方 App 的扫码页支持「从相册选择」，那条路径是官方完整流程
 * （识别 → 上报云端 → 跳转登录页），不像裸启动 `ScanActivity` 那样缺调用方上下文。
 *
 * 写入位置：
 * - API 29+：MediaStore `Pictures/BYDAutoStart`（无需任何权限）
 * - API 26~28：App 外部私有目录（官方 App 读不到，仅作占位，实际只在本机调试用）
 */
object QrImageSaver {

    /** 与设置页 QrContainer 完全一致的尺寸（截图能被官方 App 识别，说明这组参数可用）。 */
    private const val SIZE = 400
    private const val ALBUM = "BYDAutoStart"

    /**
     * 生成二维码图片并保存。
     *
     * @param content 二维码内容（`watchQRCode://yangwang=SEC_PRE` + uuid）
     * @return 保存成功返回可读的 Uri 字符串，失败返回 null
     */
    fun saveToGallery(context: Context, content: String): String? {
        val bmp = encode(content) ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bmp)
            } else {
                saveToAppDir(context, bmp)
            }
        } catch (t: Throwable) {
            EventLog.warn("QUICK_AUTH", "保存二维码图片失败：" + t.javaClass.simpleName)
            null
        }
    }

    /** 用 zxing 生成黑白位图（720×720，留 1 模块白边避免识别失败）。 */
    private fun encode(content: String): Bitmap? = try {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,  // 与 QrContainer 一致（该参数实测可被官方 App 识别）
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE, hints)
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        }
    } catch (t: Throwable) {
        EventLog.warn("QUICK_AUTH", "生成二维码位图失败：" + t.javaClass.simpleName)
        null
    }

    /** API 29+：写 MediaStore，其它 App（含官方 App）在相册里能直接看到。 */
    private fun saveViaMediaStore(context: Context, bmp: Bitmap): String? {
        val name = "bydhk_qr_" + System.currentTimeMillis() + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        EventLog.info("QUICK_AUTH", "二维码图片已存入相册（Pictures/$ALBUM）")
        return uri.toString()
    }

    /** API 26~28 兜底：写 App 外部私有目录。 */
    private fun saveToAppDir(context: Context, bmp: Bitmap): String? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
        val f = File(dir, "bydhk_qr_" + System.currentTimeMillis() + ".png")
        FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        EventLog.info("QUICK_AUTH", "二维码图片已存 App 目录：" + f.absolutePath)
        return f.absolutePath
    }
}

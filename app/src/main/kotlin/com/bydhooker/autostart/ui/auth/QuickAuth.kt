package com.bydhooker.autostart.ui.auth

import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.data.RootShell

/**
 * 「快速授权」最终实现（2026-11-09，按用户要求：让官方 App 真实走扫码，只是不用摄像头）。
 *
 * ## 原理（四轮探针实证）
 * 官方 App 首页点「扫一扫」的真实链路：
 * ```
 * TabManager → FlexibleScan.startScan(activity, bool, bool, ScanCodeProviderImpl$b)
 *              （第 4 个参数 = 扫码结果处理者，含 onModuleResultSuccess）
 *   → ScanActivity 摄像头识别 → handleScanResultToModule(str) → 交给那个 b 实例
 *   → 官方 App 上报云端（uuid 推进到「已扫描」）
 *   → ARouter 跳 WatchKeyLoginActivity（extras={watchUuid}）→ 用户点「登录」
 * ```
 * 其中**摄像头识别这一步**完全可以用「直接调用 provider 的
 * onModuleResultSuccess(context, 二维码字符串, null)」替代——官方 App 之后的
 * 上报、跳转全部照常，云端状态机正常推进（这是 v0.9.24 直接传 watchUuid 失败的原因）。
 *
 * ## 流程
 * 1. 点「快速授权」→ 把二维码字符串写进宿主私有目录 → `am start` 官方 App 首页
 * 2. 官方 App 打开（TabManager.onResume）→ `ScanActivityHook` 读到注入文件，
 *    用缓存的 provider 调 `onModuleResultSuccess(...)` → 跳转登录页
 * 3. 用户点「登录」→ 完成
 *
 * 依赖：用户至少打开过一次扫码页（使 provider 实例被缓存，进程内有效）。
 */
object QuickAuth {

    private const val HOST_PKG = "com.byd.aeri.caranywhere"
    private const val HOST_DIR = "/data/user/0/" + HOST_PKG + "/files/bydhooker"

    /** 宿主扫码 Hook 读取的注入文件（base64）。 */
    private const val INJECT_FILE = "$HOST_DIR/qr_inject.txt"

    /** 官方 App 首页（Tab 容器）—— 注入宿主，Manifest 实测。 */
    private const val TAB_ACTIVITY =
        "$HOST_PKG/com.byd.aeri.splash.module_splash.activity.TabManager"

    /**
     * 触发快速授权。
     *
     * @param payload 二维码内容（`watchQRCode://yangwang=SEC_PRE` + uuid）
     * @return 给用户看的一句话结果（UI 用 Toast 展示）
     */
    fun launch(payload: String): String {
        if (payload.isBlank()) {
            return "二维码尚未生成，请稍候重试"
        }
        val b64 = try {
            android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            return "二维码内容编码失败"
        }

        // ① 写注入文件（宿主 Hook 在 TabManager.onResume 时读取并调用 provider）
        val wrote = RootShell.execOk("mkdir -p $HOST_DIR && echo '$b64' > $INJECT_FILE")
        if (!wrote) {
            EventLog.warn("QUICK_AUTH", "写入注入文件失败（root 未授权？）")
            // 2026-11-19 用户逐字指定：不点名具体 root 管理器，兼容 KernelSU / Magisk 等
            return "模块未获取root权限，请前往root管理器授权"
        }

        // ② 打开官方 App 首页（clear-top/single-top 确保触发 onResume/onNewIntent）
        val started = RootShell.execOk(
            "am start -n $TAB_ACTIVITY --activity-clear-top --activity-single-top",
        )
        EventLog.info(
            "QUICK_AUTH",
            if (started) "已写入 payload 并拉起官方 App 首页（注入将自动发生）"
            else "am start 失败",
        )
        return if (started) {
            "已打开官方 App：请进入「我的」页，点击右上角扫一扫"
        } else {
            "启动官方 App 失败：请检查 root 授权"
        }
    }
}

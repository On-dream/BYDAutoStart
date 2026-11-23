package com.bydhooker.autostart.ui.auth

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.data.XposedServiceGate
import com.bydhooker.autostart.ui.AuthUi
import com.bydhooker.autostart.ui.SettingsState
import java.util.concurrent.Executors

/**
 * 授权网关结果（T04 接缝；`18` §5.1：授权流程在设置页执行）。
 */
sealed class AuthGatewayResult {
    /** 二维码已生成（payload 由宿主回传口径改为设置页直连后由本页生成——T05）。 */
    data class Qr(val payload: String, val deadlineSec: Long) : AuthGatewayResult()

    /** 失败（reason 为 `18` §5.3 逐字文案之一）。 */
    data class Failure(val reason: String) : AuthGatewayResult()
}

/**
 * 授权网络执行器（createQrCode / getQRCodeStatus / getToken / getVehicleInfo，
 * `15` §7.2 #1–#4）。
 *
 * 【T05 接入】：T03 `WatchRequest` 头注已声明「其余 6 个接口在授权/诊断流程，T05 接入」；
 * 这 4 个授权接口的请求构造格式无 E 路线实测样本，**不得凭猜测实现**（18 §11 红线）。
 * T04 交付的默认实现返回降级文案；T05 替换本实现即可，UI 状态机无需改动。
 */
interface AuthExecutor {
    fun createQr(): AuthGatewayResult
    fun cancel() {}
}

/** T04 默认实现：未接入网络层 → 降级文案（`18` §5.3 桥不可用行，逐字）。 */
object T05PendingAuthExecutor : AuthExecutor {
    override fun createQr(): AuthGatewayResult =
        AuthGatewayResult.Failure("请在LSPosed框架激活模块、勾选作用域，并前往root管理器授予模块root权限")
}

/**
 * 授权状态机（UI 侧；状态源 = 本设置页，架构 A）。
 *
 * 状态转移严格按 `18` §5.2 / §5.7：
 * startAuth → CREATING → (gateway) → WAITING_SCAN | FAILED(→重试回 CREATING)；
 * CREATING/WAITING_SCAN/SCANNED/CONFIRMING 退出 → cancelAuth → NONE。
 * 网络步骤经 [AuthExecutor] 接缝（T05 替换实现）。
 */
class AuthController {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BYDHK-Auth").apply { isDaemon = true }
    }

    fun startAuth() {
        if (SettingsState.auth in
            setOf(AuthUi.CREATING, AuthUi.WAITING_SCAN, AuthUi.SCANNED, AuthUi.CONFIRMING)
        ) return
        if (!SettingsState.bridgeAvailable) return  // 降级态：按钮已禁用，双保险
        SettingsState.authFailReason = ""
        SettingsState.auth = AuthUi.CREATING
        io.execute {
            when (val r = runCatching { executor().createQr() }.getOrElse {
                AuthGatewayResult.Failure("请在LSPosed框架激活模块、勾选作用域，并前往root管理器授予模块root权限")
            }) {
                is AuthGatewayResult.Qr -> {
                    SettingsState.qrPayload = r.payload
                    SettingsState.qrRemainingSec = r.deadlineSec
                    SettingsState.auth = AuthUi.WAITING_SCAN
                }
                is AuthGatewayResult.Failure -> {
                    SettingsState.authFailReason = r.reason
                    SettingsState.auth = AuthUi.FAILED
                }
            }
        }
    }

    fun cancelAuth() {
        io.execute { runCatching { executor().cancel() } }
        if (SettingsState.auth in
            setOf(AuthUi.CREATING, AuthUi.WAITING_SCAN, AuthUi.SCANNED, AuthUi.CONFIRMING)
        ) {
            SettingsState.auth = AuthUi.NONE
        }
    }

    /** FAILED → 重试（`18` §5.2：二维码作废、剩余时间重置 → CREATING）。 */
    fun retry() = startAuth()

    /**
     * 授权成功（T05 网络流拿到凭据后调用）：凭据键写入 remote prefs →
     * 宿主 Keystore 加密落盘（架构 A）。
     */
    fun persistCredentials(cred: Map<String, String>) {
        XposedServiceGate.persistCredentials(cred)
        XposedServiceGate.writeAcquireTs(System.currentTimeMillis())
        SettingsState.qrPayload = ""
        SettingsState.auth = AuthUi.AUTHORIZED
        // 授权完成 → 把设置页拉回前台（用户在官方 App 那边点的登录，本页在后台）。
        // 用 NEW_TASK|REORDER_TO_FRONT：目标 Activity 已在栈中，属于「把已有任务移到前台」，
        // 不触发 Android 10+ 的后台启动限制。
        bringSelfToFront()
        SettingsState.credPresent = true
        // UI 立即回填（原实现只写 prefs 不更新本页状态 → 车型/车牌显示 — 直到重进页面）
        SettingsState.credAutoType = cred[BridgeContract.KEY_CRED_AUTO_TYPE] ?: ""
        SettingsState.credAutoPlate = cred[BridgeContract.KEY_CRED_AUTO_PLATE] ?: ""
    }

    /** 单例执行器：cancel() 必须能取消**进行中的**轮询线程（每次新建会失联旧线程）。 */
    private val exec: AuthExecutor by lazy {
        RealAuthExecutor { map -> persistCredentials(map) }
    }

    private fun executor(): AuthExecutor = exec

    /** 供 T05 接线参考：全部凭据键。 */
    fun credentialKeys(): Set<String> = BridgeContract.CRED_KEYS

    /** 把设置页拉回前台（授权完成后调用）。 */
    private fun bringSelfToFront() {
        try {
            val ctx = SettingsState.appContext ?: return
            val i = android.content.Intent(ctx, com.bydhooker.autostart.ui.SettingsActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ctx.startActivity(i)
            EventLog.info("AUTH", "授权完成，已把设置页拉回前台")
        } catch (t: Throwable) {
            EventLog.warn("AUTH", "拉回设置页失败：" + t.javaClass.simpleName)
        }
    }
}

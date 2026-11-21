package com.bydhooker.autostart.ui.auth

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.crypto.WatchQrAuth
import com.bydhooker.autostart.ui.AuthUi
import com.bydhooker.autostart.ui.SettingsState

/**
 * 真授权执行器（T05 授权部分前移，2026-11-08 用户拍板；`18` §5.1/§5.2 状态机）。
 *
 * 逐字复刻 E 路线实测实现（`v3_qr_v2.py`，2026-11-08 02:02 真机打通）：
 * createQr 出码 → 后台轮询 2.5s → codeStatus ≥20000 即试换 token →
 * `data.signToken` 非空 = 成功 → 凭据经 [onCredentials] 落 CredentialVault。
 * 状态回写：WAITING_SCAN(出码) → SCANNED(20000) → CONFIRMING(30000) → AUTHORIZED。
 *
 * 失败口径（`18` §5.3 文案；超时=单轮 110s + 6 轮上限的保守合并 → 180s）。
 */
class RealAuthExecutor(
    private val onCredentials: (Map<String, String>) -> Unit,
) : AuthExecutor {

    @Volatile private var cancelled = false

    override fun createQr(): AuthGatewayResult {
        cancelled = false
        val wi = WatchQrAuth.newWatchImei()
        val uuid = WatchQrAuth.createQr(wi)
            ?: return AuthGatewayResult.Failure("二维码生成失败，请重试")
        // 出码即起轮询（E-A2.6：减少时间差；唯一换 token 窗口在 30000）
        Thread({ pollLoop(wi, uuid) }, "BYDHK-AuthPoll").apply {
            isDaemon = true
            start()
        }
        return AuthGatewayResult.Qr(WatchQrAuth.QR_PREFIX + uuid, WatchQrAuth.ROUND_SECS)
    }

    override fun cancel() {
        cancelled = true
    }

    private fun pollLoop(wi: String, uuid: String) {
        val t0 = System.currentTimeMillis()
        val deadlineMs = 180_000L   // 单轮 110s 窗口 + 6 轮重出码口径的保守合并（18 §5.2）
        var appChannel: String? = null
        var lastCs = -1
        var tokenTries = 0
        while (!cancelled && System.currentTimeMillis() - t0 < deadlineMs) {
            // 倒计时逐轮刷新（T04 交接用户反馈：倒计时不动——原实现只在出码时赋值）
            SettingsState.qrRemainingSec =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toSeconds(deadlineMs - (System.currentTimeMillis() - t0))
            val st = try {
                WatchQrAuth.qrCodeStatus(uuid, wi)
            } catch (t: Throwable) {
                null
            }
            if (st != null) {
                if (st.appChannel != null) appChannel = st.appChannel
                if (st.codeStatus != lastCs) {
                    lastCs = st.codeStatus
                    when (st.codeStatus) {
                        20000 -> SettingsState.auth = AuthUi.SCANNED
                        30000 -> SettingsState.auth = AuthUi.CONFIRMING
                    }
                }
                // ≥20000 即试换（A2.9.3：30000 是唯一成功窗口；20000 必 500、40000 必 61，
                // 与 v3_qr_v2 相同——每轮一试，代价一次请求，收益是不错过窗口）
                if (st.codeStatus >= 20000) {
                    tokenTries++
                    val d = try {
                        WatchQrAuth.getToken(uuid, wi, appChannel)
                    } catch (t: Throwable) {
                        null
                    }
                    if (d != null) {
                        // 凭据先落（保底）；车型/车牌经 getVehicleInfo 补齐（失败不阻塞授权）
                        val credMap = credentialMap(d, wi, appChannel).toMutableMap()
                        try {
                            val vi = WatchQrAuth.getVehicleInfo(
                                encryToken = credMap[BridgeContract.KEY_CRED_ENCRY_TOKEN] ?: "",
                                signToken = credMap[BridgeContract.KEY_CRED_SIGN_TOKEN] ?: "",
                                wi = wi,
                                identifier = credMap[BridgeContract.KEY_CRED_IDENTIFIER] ?: "",
                                identifierType = credMap[BridgeContract.KEY_CRED_IDENTIFIER_TYPE] ?: "0",
                                vin = credMap[BridgeContract.KEY_CRED_VIN] ?: "",
                                vehicleBrand = credMap[BridgeContract.KEY_CRED_VEHICLE_BRAND] ?: "1",
                                appChannel = credMap[BridgeContract.KEY_CRED_APP_CHANNEL] ?: "99",
                            )
                            if (vi != null) {
                                WatchQrAuth.deepFind(vi, "autoType")?.let {
                                    credMap[BridgeContract.KEY_CRED_AUTO_TYPE] = it
                                }
                                WatchQrAuth.deepFind(vi, "autoPlate")?.let {
                                    credMap[BridgeContract.KEY_CRED_AUTO_PLATE] = it
                                }
                                WatchQrAuth.deepFind(vi, "tboxVersion")?.let {
                                    credMap[BridgeContract.KEY_CRED_TBOX_VERSION] = it
                                }
                            }
                        } catch (t: Throwable) {
                            com.bydhooker.autostart.core.EventLog.warn(
                                "AUTH", "vehicleInfo 获取失败：" + t.javaClass.simpleName)
                        }
                        onCredentials(credMap)
                        return
                    }
                }
            }
            try {
                Thread.sleep(2500)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!cancelled) {
            SettingsState.authFailReason = "授权未完成（轮询 ${tokenTries} 次未换到凭据）——请重新扫码"
            SettingsState.auth = AuthUi.FAILED
        }
    }

    /** token 响应 data → 凭据桥键 map（persistCredentials 按 CRED_KEYS 过滤落盘）。 */
    private fun credentialMap(d: org.json.JSONObject, wi: String, appChannel: String?): Map<String, String> {
        fun s(k: String, def: String = ""): String = d.optString(k, def).ifEmpty { def }
        return mapOf(
            BridgeContract.KEY_CRED_WATCH_IMEI to wi,
            BridgeContract.KEY_CRED_SIGN_TOKEN to s("signToken"),
            BridgeContract.KEY_CRED_ENCRY_TOKEN to s("encryToken"),
            BridgeContract.KEY_CRED_PASSWORD to s("passWord"),
            BridgeContract.KEY_CRED_VIN to s("objective"),
            BridgeContract.KEY_CRED_IDENTIFIER to s("identifier"),
            BridgeContract.KEY_CRED_IDENTIFIER_TYPE to s("identifierType", "0"),
            BridgeContract.KEY_CRED_VEHICLE_BRAND to s("vehicleBrand", "1"),
            BridgeContract.KEY_CRED_APP_CHANNEL to s("appChannel", appChannel ?: "99"),
            BridgeContract.KEY_CRED_APP_VERSION to s("appVersion", "570"),
        )
    }
}

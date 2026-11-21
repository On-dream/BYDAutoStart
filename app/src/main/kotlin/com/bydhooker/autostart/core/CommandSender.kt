package com.bydhooker.autostart.core

import com.bydhooker.autostart.bridge.BridgeContract
import com.bydhooker.autostart.bridge.ConfigStore
import com.bydhooker.autostart.crypto.WatchRequest
import com.bydhooker.autostart.island.IslandEvent
import com.bydhooker.autostart.island.IslandReporter
import com.bydhooker.autostart.model.Credential
import java.util.UUID

/**
 * 真下发执行器（`15` §3.2 ⑥ / §7.1 / §4.2；T05 首次接入）。
 *
 * 两条约束（硬）：
 * - **只发 `RUNENGINENODRIVE`**，一次解锁最多 1 次，**任何情况不重试**（G-9）
 * - 判定**只看受理**（响应 `code == "0"`）；`respondData` 是固定回执（E-b 实测），
 *   **不得用于判成功**，执行结果由 [verify] 用 `getStatusNow` 复核（A4）
 *
 * 报文保真：内层 10 字段逐字对齐 E 路线上锁成功样本
 * （`research/车控逆向/E-路线/evidence/v3/extracted.json`，
 *  `v3_build.extract_remote_control` 可复核），字段名/顺序/类型均不得改动。
 * `controlParamsMap` 在样本中是**紧凑 JSON 字符串**（不是 JSON 对象）——
 * `InnerCipher.watchPyValue` 对 String 做 `\"` 转义，与服务端解析口径一致。
 */
object CommandSender {

    /**
     * 17 个 AC 字段全 0，**逐字**取自 E 路线样本的 `controlParamsMap` 值
     * （必须保持紧凑分隔符：`:` / `,` 后无空格）。
     */
    private const val AC_PARAMS_MAP =
        "{\"acSwitch\":0,\"airControl\":0,\"airDefrost\":0,\"airFastTempDown\":0," +
            "\"airFastTempUp\":0,\"airVentilate\":0,\"circleMode\":0,\"copilotSettingTemp\":0," +
            "\"copilotVentilation\":0,\"defrost\":0,\"mainSettingTemp\":0," +
            "\"mainVentilation\":0,\"remoteMode\":0,\"timeSpan\":0,\"ventilate\":0," +
            "\"windLevel\":0,\"windMode\":0}"

    /** 内层字段名（顺序 = 抓包样本插入序；SelfCheck 逐字核对用）。 */
    val COMMAND_INNER_KEYS: List<String> = listOf(
        "vin", "tboxVersion", "requestSerial", "autoType", "commandPwd",
        "commandType", "controlParamsMap", "source", "version", "deviceType",
    )

    /**
     * remoteControl 内层字段集（10 个；每次调用生成新的 32 位大写 hex requestSerial）。
     * 公开供 SelfCheck 只读字段名使用——**不得**在 core 之外直接拼报文。
     */
    fun commandInnerMap(
        cred: Credential,
        commandType: String = BridgeContract.CMD_ENGINE_START,
        paramsMap: String = AC_PARAMS_MAP,
    ): List<Pair<String, Any?>> = listOf(
        "vin" to cred.vin,
        "tboxVersion" to (cred.tboxVersion ?: "3"),
        "requestSerial" to UUID.randomUUID().toString().replace("-", "").uppercase(),
        "autoType" to (cred.autoType ?: ""),
        "commandPwd" to cred.passWord,
        "commandType" to commandType,
        "controlParamsMap" to paramsMap,
        "source" to "watch",
        "version" to BridgeContract.APP_VERSION,
        "deviceType" to "0",
    )

    /**
     * 下发**解锁车门**指令（`docs/32` §5.5；`commandType = OPENDOOR`，`docs/13:73` 实证映射）。
     *
     * 与 [sendStart] 的区别：
     * · `commandType` = `OPENDOOR`（不是 `RUNENGINENODRIVE`）
     * · `controlParamsMap` = `{}` —— 解锁不需要空调那一套参数
     *
     * 同样**只看受理**：`code == "0"` → SEND；否则 SEND_FAIL。绝不重试。
     * 调用线程必须是网络线程（本方法同步阻塞）。
     */
    fun sendUnlock(cred: Credential): Boolean = try {
        val res = WatchRequest.remoteControl(
            commandInnerMap(cred, BridgeContract.CMD_UNLOCK, "{}"), cred)
        when {
            res == null -> {
                EventLog.error("SEND_FAIL", "解锁下发失败（码：报文构造失败），不会自动重试")
                false
            }
            res.body == null -> {
                val code = if (res.httpCode > 0) res.httpCode.toString() else "网络异常"
                EventLog.error("SEND_FAIL", "解锁下发失败（码：$code），不会自动重试")
                false
            }
            else -> {
                val code = res.body.optString("code", "")
                if (code == "0") {
                    EventLog.info("SEND", "已下发解锁指令（OPENDOOR）")
                    true
                } else {
                    EventLog.error("SEND_FAIL",
                        "解锁下发失败（码：${code.ifEmpty { "未知" }}），不会自动重试")
                    false
                }
            }
        }
    } catch (t: Throwable) {
        EventLog.error("SEND_FAIL", "解锁下发失败（码：${t.javaClass.simpleName}），不会自动重试")
        false
    }

    /**
     * 下发启动指令。**只看受理**：`code == "0"` → SEND；否则 SEND_FAIL。绝不重试。
     * 调用线程必须是 NetExecutor 的网络线程（本方法同步阻塞）。
     */
    fun sendStart(cred: Credential): Boolean {
        val ok = try {
            sendStartInternal(cred)
        } catch (t: Throwable) {
            false
        }
        // 岛 S1 起点 / S3：用户拍板「S1 存活严格按复核时长走」，起点是下发受理。
        // 展示链独立、失败静默，不改变任何判据与下发语义。
        IslandReporter.report(
            if (ok) IslandEvent.TYPE_SEND else IslandEvent.TYPE_FAIL,
            System.currentTimeMillis(), 0L, "", 0L,
        )
        return ok
    }

    private fun sendStartInternal(cred: Credential): Boolean = try {
        val res = WatchRequest.remoteControl(commandInnerMap(cred), cred)
        when {
            res == null -> {
                EventLog.error("SEND_FAIL", "下发失败（码：报文构造失败），不会自动重试")
                false
            }
            res.body == null -> {
                val code = if (res.httpCode > 0) res.httpCode.toString() else "网络异常"
                EventLog.error("SEND_FAIL", "下发失败（码：$code），不会自动重试")
                false
            }
            else -> {
                val code = res.body.optString("code", "")
                if (code == "0") {
                    EventLog.info("SEND", "已下发启动指令")
                    true
                } else {
                    EventLog.error("SEND_FAIL",
                        "下发失败（码：${code.ifEmpty { "未知" }}），不会自动重试")
                    false
                }
            }
        }
    } catch (t: Throwable) {
        EventLog.error("SEND_FAIL", "下发失败（码：${t.javaClass.simpleName}），不会自动重试")
        false
    }

    /**
     * A4 复核：下发后延时 [ConfigStore.verifyDelayMs] 起查，最多 [ConfigStore.verifyRetry] 次、
     * 间隔 3 s、总上限 [BridgeContract.DEF_VERIFY_TIMEOUT_MS_M2]（20 s）。
     * **同一线程串行**（不新建线程）；判定 = `powerGear == 3` 且数据龄 ≤ 30 s。
     *
     * 总上限说明：20 s 为 M2 实测修正（`getStatusNow` 单次 2.5-3 s，门缺失时走
     * rtRequest + rtResult 两步），`15` §4.1 的 12 s（[BridgeContract.DEF_VERIFY_TIMEOUT_MS]）
     * 为设计值，保留给 M1，待文档回写。
     *
     * @return true 已确认上电；false = 未确认（落 VERIFY_PENDING，不算成功、不重试）
     */
    fun verify(cred: Credential): Boolean {
        val wallTs = System.currentTimeMillis()
        val ok = try {
            verifyInternal(cred)
        } catch (t: Throwable) {
            false
        }
        // 岛 S2 / S3 的唯一触发源。包一层统一出口，覆盖 verifyInternal 的
        // 全部 3 个 return（含初延迟被 InterruptedException 中断、原本不落
        // VERIFY_PENDING 日志的那个分支），见 docs/37 §8.10。
        IslandReporter.report(
            if (ok) IslandEvent.TYPE_VERIFY_OK else IslandEvent.TYPE_VERIFY_PENDING,
            wallTs, 0L, "", System.currentTimeMillis() - wallTs,
        )
        return ok
    }

    /** 原复核逻辑，一个字未改；返回值由 [verify] 统一转成岛事件。 */
    private fun verifyInternal(cred: Credential): Boolean {
        val startTs = System.currentTimeMillis()
        val delayMs = ConfigStore.verifyDelayMs.toLong().coerceAtLeast(500L)
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            return false
        }
        // 窗口内**持续轮询**（不再受 verifyRetry 次数限制）：实测车端执行 6 s 内
        // 常常还没上电，固定 3 次会在车真正上电前就放弃 → 误报 VERIFY_PENDING。
        while (System.currentTimeMillis() - startTs <= BridgeContract.DEF_VERIFY_TIMEOUT_MS_M2) {
            try {
                // 复核走 M2 轻量取帧（rt 两步 + 短轮询），数据源与下发前判定一致
                val frame = WatchRequest.fetchFreshStatus(
                    cred, BridgeContract.M2_STATUS_MAX_AGE_SEC, pollMs = 200L, maxTries = 3)
                if (frame != null) {
                    val age = System.currentTimeMillis() / 1000 - frame.timeSec
                    // 观测日志：复核期间到底取到什么（2026-11-09 实测出现「车已上电但
                    // 20 s 未确认」，此前盲查无据——补上每轮探针）。
                    // 同时输出 doorLock / vehicleState：为「等解锁成功再上电」确定
                    // 「解锁已完成」的可观测判据（doorLock=2 为已锁，E 路线实测）。
                    // time 绝对值一并落日志：用于判断 age 是否可信
                    // （若车机休眠时 time 仍每秒变化 → 该 time 是服务端时间，
                    //  G-3 新鲜度判定失效；2026-11-09 09:00 出现 age=0s 而
                    //  powerGear 20 s 不变的可疑组合）
                    EventLog.debug(
                        "STATUS",
                        "复核：age=${age}s，time=${frame.timeSec}，powerGear=${frame.powerGear}" +
                            "，doorLock=${frame.doorLockFL}，vehicleState=${frame.vehicleState}",
                    )
                    if (frame.powerGear == 3 && age <= BridgeContract.M2_STATUS_MAX_AGE_SEC) {
                        val elapsed = System.currentTimeMillis() - startTs
                        EventLog.info("VERIFY_OK", "复核通过：车辆已上电（下发后 ${elapsed} ms）")
                        return true
                    }
                } else {
                    EventLog.debug("STATUS", "复核：取帧失败（rtRequest 无返回）")
                }
            } catch (_: Throwable) {
                // 单次查询失败：计入本轮，继续按节奏查（查询非下发，不重试语义不冲突）
            }
            val remain = BridgeContract.DEF_VERIFY_TIMEOUT_MS_M2 -
                (System.currentTimeMillis() - startTs)
            if (remain <= 800L) break
            try {
                Thread.sleep(minOf(1500L, remain))
            } catch (_: InterruptedException) {
                break
            }
        }
        val elapsed = System.currentTimeMillis() - startTs
        EventLog.warn(
            "VERIFY_PENDING",
            "指令已受理但未确认上电（${elapsed} ms 内）；不算成功、不会自动重试",
        )
        return false
    }
}

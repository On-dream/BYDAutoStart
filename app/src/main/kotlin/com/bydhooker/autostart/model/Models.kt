package com.bydhooker.autostart.model

/** 触发事件（`15` §3.2 ② TriggerSource 产出；B3：指令即起点，不等回执）。 */
data class UnlockEvent(
    val source: String,
    val ts: Long,
)

/** 车况帧（`15` §4.1 ④；105 字段中本模块只消费以下字段）。 */
data class StatusFrame(
    val timeSec: Long,          // 服务端数据时间戳（epoch 秒）
    val leftFrontDoor: Int?,    // 云端口：0=关 / 1=开（rt-result-1 实测 0=关）/ 其他=未知
    val powerGear: Int?,        // 3=已上电（【实测】）；0/1 语义【不知道】
    val requestSerial: String?, // 掩码后落日志
    val doorLockFL: Int? = null, // 左前门锁 2=已锁（rt-result-1 实测；诊断显示用）
    val vehicleState: Int? = null, // 车辆状态（rt-result-1 实测字段名）
)

/** 凭据快照（`15` §6.1；宿主内解密后的形态，永不出宿主进程）。 */
data class Credential(
    val watchImei: String,
    val signToken: String,
    val encryToken: String,
    val passWord: String,
    val vin: String,
    val identifier: String,
    val identifierType: String,
    val vehicleBrand: String,
    val appChannel: String,
    val autoType: String?,
    val tboxVersion: String?,
    val acquireTokenTimestamp: Long,
)

/** token 有效性（`18` §6.2；三态，UNKNOWN = 桥/宿主未就绪）。 */
enum class TokenState { NONE, AUTHORIZED, EXPIRING, UNKNOWN }

/** 白盒组件状态。 */
enum class WhiteboxState { OK, FAILED, UNKNOWN }

/** 事件日志条目（`15` §6.3；敏感字段由 EventLog 写入前统一掩码）。 */
data class LogEntry(
    val ts: Long,
    val level: String,   // INFO / DEBUG / WARN / ERROR
    val event: String,   // UNLOCK / WINDOW_OPEN / ...（英文事件名，18 §12.6）
    val summary: String, // 中文摘要（去代号化，18 §12.2）
)

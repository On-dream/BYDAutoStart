package com.bydhooker.autostart.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.ui.SettingsState
import com.bydhooker.autostart.wake.NfcLocalState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动拉起小米智能卡进程（v3.0.1，修「NFC 卡片经常不显示」）。
 *
 * 【问题与实证（2026-11-13 20:3x，设备 a58bef59）】
 * 首页 ①-N 卡的显示条件是 `hook.l1 && l2 && l3` 且 SP 里 `hook.ver == 当前版本码`
 * （版本绑定防假绿，`NfcLocalState.readHookLevel`）。而：
 *  · **tsmclient 主进程开机不自启** —— 实证开机 22 分钟 `pidof com.miui.tsmclient` 为空；
 *    它只在 NFC 服务绑定 `NfcEventMonitorService` / 刷卡事件到来时才被拉起。
 *  · **模块升级也不会让它重启** —— `adb install -r` 杀的是模块自己的进程。
 *  ⇒ `hook.ver` 停在旧值（真机：装 254 后 SP 里还是 253）⇒ 卡片长期不显示，
 *     重启系统也救不了；用户只能手动「结束小米智能卡 / NFC」把进程拉起来。
 *
 * 【修法】检测到 ver 过期 ⇒ root **先 force-stop 再广播拉起**（两步缺一不可）。
 * 真机实测（2026-11-13）：只 ping 活着的旧注入进程不会重新加载 dex（三态节流不发）；
 * `force-stop → PingReceiver 广播(--include-stopped-packages)` → 新 pid 重新注入
 * → 三态上报 → ver 刷新 → 卡片恢复。等价于用户手动「结束小米智能卡」，全自动。
 *
 * 【节流】60 s 一次（SP `wake.ts`）+ `inFlight` 防并发；失败只记日志，
 * 不影响任何主链路 —— 拉不起来时用户仍可手动结束 app（旧办法仍然有效）。
 */
object TsmclientWaker {

    private const val TSM_PKG = "com.miui.tsmclient"

    /** 拉起后等注入 + 三态上报 + Provider 落 SP 的预留时间。 */
    private const val SETTLE_MS = 8_000L

    /** 两次拉起的最小间隔：tsmclient 起不来时不要连环 su。 */
    private const val THROTTLE_MS = 60_000L

    private val inFlight = AtomicBoolean(false)

    /**
     * 检查并在需要时拉起。**任何线程可调、立即返回**（root 命令在后台线程）。
     * 调用点：设置页每次 `reloadNfcLocal`、模块进程启动（`BydApp`）。
     */
    fun wakeIfStale(ctx: Context?) {
        val c = ctx ?: return
        try {
            if (!NfcLocalState.isHookVerStale(c)) return
            val now = System.currentTimeMillis()
            if (now - NfcLocalState.readWakeTs(c) < THROTTLE_MS) return
            NfcLocalState.writeWakeTs(c, now)
            if (!inFlight.compareAndSet(false, true)) return
            Thread({
                try {
                    // 必须「先杀再拉」：如果 tsmclient 只是活着但还是旧版本注入
                    // （模块升级后最常见的状态），单纯 ping 它不会重新加载 dex，
                    // 三态没变 report() 又有节流 ⇒ ver 永远刷不过去。
                    // force-stop 后用显式组件广播（--include-stopped-packages）拉起，
                    // 新进程重新注入当前模块的 dex → 三态上报 → ver 刷新。
                    // 真机实测（2026-11-13 20:43）：force-stop → 广播 → 新 pid 注入
                    // → `TSM_UI_DATA_SEND kind=hook ok=true` → SP ver=当前版本。
                    val cmd = "am force-stop $TSM_PKG; sleep 1; " +
                            "am broadcast -n $TSM_PKG/" +
                            "com.xiaomi.push.service.receivers.PingReceiver" +
                            " -a com.xiaomi.push.PING --include-stopped-packages"
                    val ok = RootShell.execOk(cmd, 12)
                    EventLog.info(
                        "NFC_WAKE_TSM",
                        "root 重启小米智能卡（hook.ver 过期，杀旧注入进程再拉起）|ok=$ok",
                    )
                    // 等注入 + 三态上报走完（实测 ~1 s），然后把卡片刷回来
                    Thread.sleep(SETTLE_MS)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            SettingsState.reloadNfcLocal()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (t: Throwable) {
                    EventLog.warn("NFC_WAKE_TSM_ERR", t.javaClass.simpleName)
                } finally {
                    inFlight.set(false)
                }
            }, "bydhooker-wake-tsm").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            EventLog.warn("NFC_WAKE_TSM_ERR", t.javaClass.simpleName)
        }
    }
}

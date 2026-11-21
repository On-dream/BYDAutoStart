package com.bydhooker.autostart

import android.app.Application
import android.content.Context
import com.bydhooker.autostart.core.CredentialVault
import com.bydhooker.autostart.core.EventLog
import com.bydhooker.autostart.data.XposedServiceGate
import java.io.File

/**
 * 模块 App 的 Application。
 *
 * 【为什么需要】（2026-11-11 22:5x 建立）
 * `XposedServiceGate.register()` 原先只在 `SettingsActivity.onCreate` 调用。
 * 但 NFC 方案下模块会被 **ContentProvider 调用**唤醒，这种进程**没有 Activity** ⇒
 * 从未注册过 ⇒ `prefs` 恒 null ⇒ 写配置静默失败（现有 `editor()` 全是 `?: return` 风格，
 * 不报错）。表现就是「刷卡广播收到了、上电没反应」，属最难查的一类问题。
 *
 * 放到 Application 里，Activity / Provider 两条入口都能覆盖到。
 * `register()` 内部有 `registered` 幂等保护，`SettingsActivity` 里那句重复调用无害。
 *
 * 【2026-11-12 21:5x 扩展：执行层迁移基础设施（`docs/31` §7 第 1 步）】
 * 这一步**不改变任何行为**，只是让模块进程具备「执行上电」的前置条件：
 *   ① 载入模块自己的凭据库（Keystore 归模块 UID，与宿主那份互不相干）
 *   ② 把自己也加进后台联网 / 待机白名单（宿主那套已验证有效，模块同样会被 ROM 限网）
 * 架构 C 第三期已删除 `exec.location` 双轨开关：三源各自就地执行，无需切换。
 */
class BydApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appCtx = this

        // ① 先把日志落盘通道打开。
        // ⚠️ 顺序很重要：`EventLog` 未 init 前的写入只进内存 buffer、**不落盘**，
        // 等后面某个 Activity 才 init 的话，这几条启动期日志就永远丢了
        // （2026-11-12 21:46 实测：`CRED_MODULE` 一条都没进 events.log，就是这个原因）。
        try {
            EventLog.ensureInit(filesDir)
        } catch (_: Throwable) {
        }

        // ② 凭据库（模块侧）
        //
        // ⚠️ 只 attach，**解密封装进后台线程**（2026-11-13 改）：
        // · Application 早期 AndroidKeyStore 可能还没就绪（模块侧本就不用 Keystore，但同理）
        // · 更要紧的是**冷启动时三击就在等这一下**：PBKDF2 20000 轮 + AES，实测 200~400 ms，
        //   原来同步跑在 onCreate 主线程上，等于把进程启动硬拖长；而 `TRIPLE_TIMING`
        //   里 `fromKey` 的一段就是它。
        // 并发语义已由 `loadState` + `synchronized` 保证：后台线程负责解密，
        // 事件线程若先到会**阻塞等结果**，不会读到 null（这条比「在哪解密」重要得多）。
        try {
            // 模块进程**不用 Keystore**（该进程里 Keystore 解密不稳定，见 CredentialVault.useKeystore）
            CredentialVault.attach(filesDir, keystore = false)

            Thread({
                val c0 = try {
                    CredentialVault.current()
                } catch (t: Throwable) {
                    EventLog.warn("CRED_MODULE_ERR", t.javaClass.simpleName + "：" + t.message)
                    null
                }
                EventLog.info(
                    "CRED_MODULE",
                    if (c0 == null) "模块侧暂无凭据（尝试从 remote prefs 自愈）"
                    else "模块侧凭据已载入（执行层可在本进程工作；vin=${c0.vin}）",
                )

                // 架构 C（D-34-2，2026-11-13）：宿主进程也要**就地执行**（APP 链），
                // 所以 remote prefs 里必须有**完整明文凭据**。
                // 旧版本授权时只写了非敏感键（敏感键被主动删掉，宿主那份 cred.bin 也被清了）
                // ⇒ 这里用模块 vault 里的凭据补发一次。
                if (c0 != null) {
                    Thread({
                        try {
                            Thread.sleep(3000)
                            val ok = XposedServiceGate.republishFromVault()
                            EventLog.info("CRED_REPUBLISH",
                                    "已重新发布完整凭据给宿主（宿主就地执行需要）|ok=$ok")
                        } catch (_: Throwable) {
                        }
                    }, "bydhooker-republish").apply { isDaemon = true }.start()
                }

                // 自愈：它要等 XposedService 绑定（最多 5 s）
                if (c0 == null) {
                    try {
                        val c = bootstrapFromRemotePrefs(filesDir)
                        EventLog.info(
                            "CRED_MODULE",
                            if (c == null) "自愈未成功（remote prefs 里也没有凭据）"
                            else "自愈成功，模块侧凭据已就绪（vin=${c.vin}）",
                        )
                    } catch (t: Throwable) {
                        EventLog.warn("CRED_BOOTSTRAP_ERR", t.javaClass.simpleName)
                    }
                }
            }, "bydhooker-cred-init").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            EventLog.warn("CRED_MODULE_ERR", t.javaClass.simpleName)
        }

        // ③ 桥（原有逻辑，保持不变）
        try {
            XposedServiceGate.register()
        } catch (t: Throwable) {
            // 桥不可用不应阻止 App 启动（设置页会显示「桥不可用」并降级只读）
            android.util.Log.w("BYDHK", "XposedServiceGate.register failed: ${t.javaClass.simpleName}")
        }

        // ④ 架构 C：把 system 侧执行层需要的资产发布到 /data/system/bydhk/。
        // 用户 2026-11-13 拍板「先放弃所有保活」⇒ 原先同线程里的
        // netpolicy/deviceidle 网络白名单、greezer unmonitor、root 守护进程拉起
        // 已全部删除。这里**只剩资产发布**（so + chcon、凭据、配置），
        // 它是 system_server 就地执行的前置条件，不是保活。
        Thread({
            // 架构 C：把 system 侧执行层需要的资产发布到 /data/system/bydhk/
            // （so + chcon、凭据、配置）。发布前**必须先同步真实配置**——
            // ConfigStore 冷启动是默认值（enabled=true/M1），直接发布会把默认值带过去。
            try {
                val sp = XposedServiceGate.awaitPrefs(5000L)
                if (sp != null) {
                    com.bydhooker.autostart.bridge.ConfigStore.syncFromSnapshot(sp.getAll())
                }
            } catch (_: Throwable) {
            }
            try {
                com.bydhooker.autostart.core.SystemAssetPublisher.publish(this)
            } catch (_: Throwable) {
            }
        }, "bydhooker-publish").apply { isDaemon = true }.start()

        // ⑤ v3.0.1：hook.ver 过期（tsmclient 开机不自启 / 模块升级不重启它）
        //    ⇒ 首页 NFC 卡被版本校验隐藏。进程一起来就修一次，不等用户进设置页。
        try {
            com.bydhooker.autostart.data.TsmclientWaker.wakeIfStale(this)
        } catch (_: Throwable) {
        }
    }

    /**
     * 从 remote prefs 里的明文凭据重建模块侧 `cred.bin`（迁移期自愈，2026-11-12）。
     *
     * 用在「`cred.bin` 存在但解不开」的场景：`persistCredentials` 双写时也写过 remote prefs，
     * 那里面有全套 `cred.*` 明文（桥会持续维护），据此可重建而无需用户再扫一次码。
     *
     * 架构 C（2.0.50 起）remote prefs 重新常驻**完整明文凭据**（宿主/tsmclient
     * 就地执行需要），因此这条自愈在 C 下依然有效，与 B 架构的跨进程执行链无关，予以保留。
     */
    private fun bootstrapFromRemotePrefs(filesDir: java.io.File): com.bydhooker.autostart.model.Credential? {
        return try {
            val sp = XposedServiceGate.awaitPrefs(5000L) ?: run {
                EventLog.warn("CRED_BOOTSTRAP", "桥未就绪，跳过自愈")
                return null
            }
            val all = sp.all ?: return null
            if (all[com.bydhooker.autostart.bridge.BridgeContract.KEY_CRED_PRESENT] != true) {
                EventLog.warn("CRED_BOOTSTRAP", "remote prefs 里没有可用凭据")
                return null
            }
            EventLog.info("CRED_BOOTSTRAP", "从 remote prefs 重建模块侧凭据")
            CredentialVault.onCredChanged(filesDir, all, bringToFront = false)
            CredentialVault.current()
        } catch (t: Throwable) {
            EventLog.warn("CRED_BOOTSTRAP_ERR", t.javaClass.simpleName + "：" + t.message)
            null
        }
    }

    companion object {

        /** 模块进程的 Application（Provider 冷启动时也有值；`SettingsState.appContext` 未必有）。 */
        @Volatile
        var appCtx: Context? = null
            private set

        /** 模块私有 files 目录；未初始化时 null。 */
        val moduleFilesDir: File?
            get() = appCtx?.filesDir
    }
}

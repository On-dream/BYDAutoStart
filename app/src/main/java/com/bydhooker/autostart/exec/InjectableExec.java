package com.bydhooker.autostart.exec;

import com.bydhooker.autostart.bridge.ConfigStore;
import com.bydhooker.autostart.core.CommandSender;
import com.bydhooker.autostart.core.CredentialVault;
import com.bydhooker.autostart.core.EventLog;
import com.bydhooker.autostart.core.StatusPoller;
import com.bydhooker.autostart.core.TriggerSource;
import com.bydhooker.autostart.crypto.WatchRequest;
import com.bydhooker.autostart.model.Credential;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行层入口（架构 C，`docs/34` §5 N3）—— 跑在 **system_server** 里。
 *
 * 【为什么需要它】system_server 的 classloader 里没有本 APK 的任何类
 * （真机实测 `DIRECT|ok=0/4`，LSPosed 只注入入口链）。所以宿主侧的做法是：
 * `TripleKeyHook` 用 `DexClassLoader` 指向模块 base.apk 的 dex，把本类加载进来，
 * 再**反射**调它的入口方法。本类因此必须：
 * · 类名与方法名不被 R8 混淆（`proguard-rules.pro` 里已 keep `exec` 包）
 * · 入口方法**同步返回**（反射调用的结果要能直接判成败）
 *
 * 【能复用多少】执行层本体（`WatchRequest` / `CommandSender` / `StatusPoller` / `M2Runner`）
 * 是纯 JVM 逻辑，且与本类在**同一个 dex** 里 ⇒ DexClassLoader 加载本类时，
 * 它们会从同一个 classloader 解析得到，**直接引用即可，不必复制第二套**。
 * 真正需要替换的只有两处「宿主环境依赖」：
 * · `EventLog` 的落盘目录 —— 用 `init(dir)` 指向 DE 存储
 * · `ConfigStore` 的配置来源 —— 模块进程靠 remote prefs 推送，system 侧改成读文件
 *
 * 【与 `TripleKeyHook` 的边界】本类**不碰 UI、不起界面、不碰 Provider**。
 * 解锁指令走 watch 通道（`CommandSender`），和官方 App 那条路是同一条报文。
 */
public final class InjectableExec {

    /** system 侧工作目录（DE）：凭据、so、配置、日志都在这儿。 */
    public static final String DEFAULT_WORK_DIR = "/data/system/bydhk";

    /**
     * 解锁指令受理后，**延迟多久**再发上电指令（ms）。
     *
     * 【为什么需要这个延迟】两条指令几乎同时发时，上电指令到车机时它还没被解锁指令
     * 从休眠里唤醒 ⇒ 指令失效、车实际上电更晚（2026-11-13 17:1x 用户实测反馈）。
     * 1 s 是给车机留的唤醒时间；又远小于「云端可见门锁变化」的那 7 秒。
     *
     * 【为什么不是可配项】先用实测值固定下来；等有了「1 s 够 / 不够 / 几秒够」的
     * 样本，再决定要不要做成设置项（做成可配就得同步 `config.txt` 与设置页 UI）。
     */
    private static final long UP_DELAY_MS = 1000L;

    /** 日志目录（DE）：`EventLog` 会在其下建 `bydhooker/events.log`。 */
    public static final String DEFAULT_LOG_DIR = "/data/system/bydhk-exec";

    private static volatile File sWorkDir;

    private static volatile ExecConfig sConfig;

    private static volatile boolean sInited = false;

    /** A′：system_server 的 Context（由 TripleKeyHook 幂等反射注入，供岛事件使用）。 */
    private static volatile android.content.Context sHostContext;

    /**
     * A′（docs/37 §8.6）：把 system_server 的 Context 交给纯 JVM 展示链。
     *
     * 【为什么单独加这个方法而不是改 init 签名】{@code TripleKeyHook.initExec()}
     * 在开机装配时还有一个无参调用点（拿不到 Context）；这里只在 notifyModule
     * 里、真正有三击事件时由 ctx 反射注入一次，改动面最小。
     *
     * 【零项目依赖】方法参数只用框架类 Context，TripleKeyHook 按名字反射调用，
     * 不需要 import 本 APK 的任何类。本方法在 exec 包内（已 keep），不会被混淆。
     */
    public static void attachContext(android.content.Context ctx) {
        sHostContext = ctx;
        try {
            com.bydhooker.autostart.island.IslandContext.attach(ctx);
        } catch (Throwable ignored) {
            // 展示链不可用不影响解锁/上电
        }
    }

    private InjectableExec() {
    }

    /**
     * 初始化（宿主侧反射调用）。
     *
     * @param workDirPath 工作目录；null 用 {@link #DEFAULT_WORK_DIR}
     * @return true = 至少 so 与日志通道就绪（**凭据缺失不算失败**，
     *         那是「用户还没授权」，等到真有事件时再报更准）
     */
    public static boolean init(String workDirPath) {
        try {
            File dir = new File(workDirPath == null ? DEFAULT_WORK_DIR : workDirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            sWorkDir = dir;

            // ① 日志：EventLog 复用现成的掩码 / 裁剪 / 导出逻辑，只是换个落盘位置
            EventLog.INSTANCE.init(new File(DEFAULT_LOG_DIR));
            EventLog.INSTANCE.info("EXEC_INIT", "system_server 侧执行层初始化|workDir=" + dir);

            // ② 白盒 so：必须在第一次发请求之前就绪（WatchRequest 内部要用它加密）
            boolean so = InjectableSoLoader.ensureLoaded(null, dir);
            if (!so) {
                EventLog.INSTANCE.error("EXEC_INIT", "白盒 so 未就绪，本次不执行。"
                        + "（多半是 so 的 SELinux 上下文不是 system_file —— "
                        + "请**打开一次模块 App** 让它把资产发布到 " + dir
                        + "，或用 root 执行 chcon u:object_r:system_file:s0）");
                return false;
            }

            // ③ 配置：文件 → ConfigStore 快照
            sConfig = ExecConfig.load(dir);
            applyConfig();

            // ④ 凭据：软链接口径（不用 Keystore），seed 已硬编码成模块包名
            CredentialVault.INSTANCE.attach(dir, false);
            Credential c = CredentialVault.INSTANCE.current();
            if (c == null) {
                EventLog.INSTANCE.warn("EXEC_INIT", "凭据未就绪（等用户授权后再触发即可）");
            }

            // ⑤ 车况数据源（2026-11-13 20:0x 补，M1 的根因）
            //
            // 第一期 system 侧只支持 M2：解锁 → 直接 sendStart/verify，两者内部调
            // WatchRequest 的静态方法，**从不经过 StatusPoller** ⇒ 这里没注入也能跑。
            // 第三期把 M1 接回统一入口后，WindowManager.onUnlock → StatusPoller.start()
            // 需要 `source`，缺了它就报 `POLL | StatusSource 未注入`，
            // 60 s 窗口开着却一帧车况都不拉，开门自然判不到（真车 20:06:04 实测）。
            ensureStatusSource();

            sInited = true;
            return true;
        } catch (Throwable t) {
            ExecLog.error("EXEC_INIT_ERR", t.getClass().getName() + "|" + t.getMessage());
            return false;
        }
    }

    /**
     * 三击动作：解锁车门，并按「APP 解锁自动上电」的配置决定是否继续上电。
     *
     * 【同步阻塞】内部会发 HTTPS（解锁指令 ~500 ms），所以**调用方必须放在子线程**。
     * 宿主侧 `TripleKeyHook` 的 `bydhk-triple-fire` 线程正是干这个的。
     *
     * @param ts 事件时间戳（仅用于日志）
     * @return true = 解锁指令已受理（不代表车端已执行）
     */
    public static boolean onTriple(long ts) {
        try {
            if (!sInited && !init(null)) {
                return false;
            }
            // 配置可能刚被用户改过（文件通道没有推送，每次触发都重读）
            if (sConfig != null && sConfig.reloadIfChanged()) {
                applyConfig();
            }
            // v3.0.4：cred.bin 变更检测（轻量 stat，幂等）——
            // 授权后模块会立即发布新凭据到 /data/system/bydhk/，清除后会 rm 掉；
            // system_server 进程常驻、凭据只在开机 EXEC_INIT 载入一次，
            // 不做这一步的话授权后三击用旧 token、清除后三击还能下发。
            CredentialVault.INSTANCE.reloadIfFileChanged();
            Credential cred = CredentialVault.INSTANCE.current();
            if (cred == null) {
                EventLog.INSTANCE.warn("EXEC_TRIPLE", "凭据未就绪，无法下发解锁指令");
                return false;
            }
            boolean ok = CommandSender.INSTANCE.sendUnlock(cred);
            if (!ok) {
                EventLog.INSTANCE.error("EXEC_TRIPLE", "解锁未受理，**不再触发上电**");
                return false;
            }
            EventLog.INSTANCE.info("EXEC_TRIPLE", "解锁指令已受理（code=0）");

            // ── 上电方式：**直接照用「APP 解锁自动上电」里用户选的那套**（2026-11-13 19:2x）──
            //
            // 不新增任何三击专属档位。enabled / mode 两个值来自 system 侧 DE 配置快照
            // （模块 App 每次发布资产时写入 /data/system/bydhk/config.txt），语义与首页完全一致：
            //   · enabled=false → 只解锁，不上电
            //   · mode=M1      → 走 TriggerSource → WindowManager 开 60 s 窗口等主驾门开
            //   · mode=M2      → 下面这段已验证的「解锁后 1 s 直接上电」快路径
            //
            // 【为什么 M1 现在能跑】第一期不敢做，是因为 M1 窗口会调 WindowWakeLock /
            // HostWindowKeeper，两者都要 Context（system_server 没有 Application）。
            // 第三期已把整套 B 架构保活删除 ⇒ 窗口逻辑只剩纯 JVM 的 StatusPoller +
            // NetExecutor + Gate，system_server 里可原地运行，0.4 探针实测定时精度
            // avg=983ms / max=1000ms / over2s=0，不需要 Wakelock。
            if (!ConfigStore.INSTANCE.getEnabled()) {
                EventLog.INSTANCE.info("EXEC_TRIPLE", "自动上电总开关关闭：只解锁不上电");
                return true;
            }
            String mode = ConfigStore.INSTANCE.getMode();
            if (!"M2".equalsIgnoreCase(mode)) {
                // 幂等补一次：万一 init 阶段注入失败，这里还能救回来。
                // 没有数据源就别开窗 —— 开一个「永远不轮询」的 60 s 窗口只会让用户白等。
                if (!ensureStatusSource()) {
                    EventLog.INSTANCE.error("EXEC_TRIPLE",
                            "车况数据源不可用，M1 窗口无法判定开门，本次只解锁不上电");
                    return true;
                }
                EventLog.INSTANCE.info("EXEC_TRIPLE",
                        "按 APP 方案的「开门上电」开窗（mode=" + mode + "），复用统一入口");
                // 完整复用 APP 方案的门控与 M1 窗口实例（同一 T0 / alreadySent / 状态机），
                // 日志里的来源会是「电源键三击」。
                TriggerSource.INSTANCE.onUnlockCommand("triple", ts);
                return true;
            }
            // ── 解锁先出，固定延迟 1 s 后再发上电（2026-11-13 17:2x 用户拍板）──
            //
            // 【为什么不能几乎同时发】17:1x 那一版两条指令相隔约 0.4 s（一次 HTTPS 往返），
            // 用户实测反馈：**车实际上电反而更晚** —— 上电指令到车机时，它还没被解锁指令
            // 从休眠里唤醒，指令石沉大海。这正是 `docs/15` §4.2 记过的老问题
            // （「解锁瞬间立即下发，20 s 内 powerGear 恒为 1」）。
            //
            // 【为什么不等门锁边沿】真机实测（16:58:13 那次）：解锁指令 16:58:14 发出，
            // 云端整整 **7 秒**后才出现 `leftFrontDoorLock` 2→1（16:58:21）。
            // 日志里 `age` 一路 11s→14s，说明那段时间云端返回的**还是旧快照** ——
            // 「车端解锁 → TBOX 上报 → 云端可见」是车云链路延迟，不是我们的判定慢
            // （我们 0.6 s 一帧，doorLock 一变 1 立刻就抓到了，见 16:58:21 那帧）。
            // 等它等于白等 7 秒。
            //
            // 【1 秒的依据】给车机留出「被解锁指令唤醒」的时间；
            // 又远小于云端可见门锁的那 7 秒。指令仍按顺序发：解锁 → 1 s → 上电。
            //
            // 【代价，说清楚】车机深睡更久时 1 s 可能仍不够，那条上电指令会失效。
            // `verify()` 的复核日志（powerGear / doorLock / vehicleState）能看出车到底上没上电；
            // 若确实失效，下一步可加「门锁边沿出现且仍未上电则补发一次」的兜底
            //（会突破 G-9「一次解锁最多下发 1 次」，需用户另行拍板）。
            EventLog.INSTANCE.info("EXEC_TRIPLE",
                    "延迟下发：解锁已受理，" + UP_DELAY_MS + "ms 后下发启动指令（不等门锁边沿）");
            long tUp = System.currentTimeMillis();
            try {
                Thread.sleep(UP_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                EventLog.INSTANCE.warn("EXEC_TRIPLE", "等待被中断，跳过上电");
                return true;
            }
            EventLog.INSTANCE.info("EXEC_TRIPLE",
                    "已等待 " + (System.currentTimeMillis() - tUp) + "ms，开始下发启动指令");
            if (!CommandSender.INSTANCE.sendStart(cred)) {
                EventLog.INSTANCE.error("EXEC_TRIPLE", "启动指令未被受理（解锁已成功）");
                return true;
            }
            CommandSender.INSTANCE.verify(cred);
            return true;
        } catch (Throwable t) {
            EventLog.INSTANCE.error("EXEC_TRIPLE_ERR", t.getClass().getSimpleName() + "|" + t.getMessage());
            return false;
        }
    }

    /**
     * 注入车况数据源（`StatusPoller.source`）。幂等；init 与每次 M1 触发都调一次。
     *
     * 【为什么必须有】M1 的 60 s 窗口靠 `StatusPoller` 每秒拉一帧车况判 `leftFrontDoor`，
     * 而 `WindowManager.onUnlock()` 只调 `StatusPoller.start()`，它不会自己建数据源。
     * 宿主 / 模块进程由 `ModuleBridgeHost` 或 `ModuleExecutor.ensureReady()` 注入，
     * system 侧的 `InjectableExec` 是独立装配路径，漏了这一步 ⇒ `POLL StatusSource 未注入`。
     *
     * @return 注入后 `StatusPoller.source` 是否非空
     */
    private static boolean ensureStatusSource() {
        try {
            WatchRequest.INSTANCE.initStatusSource();
            boolean ok = StatusPoller.INSTANCE.getSource() != null;
            if (!ok) {
                EventLog.INSTANCE.error("EXEC_INIT", "车况数据源未注入（M1 窗口无法轮询）");
            } else {
                EventLog.INSTANCE.info("EXEC_INIT", "车况数据源已注入（M1 窗口轮询就绪）");
            }
            return ok;
        } catch (Throwable t) {
            EventLog.INSTANCE.error("EXEC_INIT",
                    "车况数据源注入异常：" + t.getClass().getName() + "|" + t.getMessage());
            return false;
        }
    }

    /** 把 ExecConfig 的值灌进 ConfigStore（执行层读的是后者）。 */
    private static void applyConfig() {
        if (sConfig == null) {
            return;
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("enabled", Boolean.valueOf(sConfig.enabled));
        m.put("mode", sConfig.mode);
        m.put("windowSeconds", Integer.valueOf(sConfig.windowSeconds));
        m.put("pollIntervalMs", Integer.valueOf(sConfig.pollIntervalMs));
        ConfigStore.INSTANCE.syncFromSnapshot(m);
        EventLog.INSTANCE.info("EXEC_CFG", "enabled=" + sConfig.enabled + "|mode=" + sConfig.mode
                + "|window=" + sConfig.windowSeconds + "|poll=" + sConfig.pollIntervalMs);
    }
}

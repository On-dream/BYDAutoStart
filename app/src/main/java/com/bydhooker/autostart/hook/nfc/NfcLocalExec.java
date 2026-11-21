package com.bydhooker.autostart.hook.nfc;

import android.content.Context;
import android.content.SharedPreferences;

import com.bydhooker.autostart.bridge.BridgeContract;
import com.bydhooker.autostart.bridge.ConfigStore;
import com.bydhooker.autostart.core.CommandSender;
import com.bydhooker.autostart.core.CredentialVault;
import com.bydhooker.autostart.core.EventLog;
import com.bydhooker.autostart.core.TriggerSource;
import com.bydhooker.autostart.core.Whitebox;
import com.bydhooker.autostart.crypto.WatchRequest;
import com.bydhooker.autostart.crypto.WbKeys;
import com.wbsk.CryptoTool;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 架构 C · 第二期：**NFC 链就地执行**（运行在 {@code com.miui.tsmclient} 进程）。
 *
 * 【改了什么】
 * B 架构：刷卡 → tsmclient 转发 → ContentProvider → 模块 App → HTTPS。
 *         模块 App 是纯后台进程，被冻结 / 被 LMK 回收 / 被关联启动拦截时整条链断掉，
 *         07:21 那次就是「开窗第 2 秒 pid 被换掉，NFC_M2_TICK 停在 n=2」。
 * C 架构：刷卡 → **本进程**比对 AID → 本进程读配置 / 凭据 / 白盒 so → 本进程发 HTTPS。
 *         执行者本来就在刷卡现场，不存在「唤醒另一个进程」这件事。
 *
 * 【为什么能直接用项目类】
 * system_server 里 LSPosed 只注入入口链（2.0.42 实测 CLASS_SUM ok=0/7），
 * 所以第一期必须用 DexClassLoader；而 tsmclient 是**普通应用进程**，
 * 与官方 App 同属 scope.list，LSPosed 会把整个模块 dex 注进去 ——
 * 2.0.50 的 UnlockHooker 在官方 App 里直调 TriggerSource 已经验证过这条路。
 *
 * 【AID 比对为什么前移】
 * 原先刻意放在模块侧（`docs/30` D6：AID 唯一硬判据），代价是每次刷卡都要唤醒模块。
 * C 下 selectedAid 从 remote prefs 快照直接读，比对留在 tsmclient，
 * 刷别的卡连一次跨进程调用都不产生。刷错卡但门恰好开 = 已知接受的残余风险，不变。
 *
 * 【保活】按用户 2026-11-13 拍板，架构 C **不启用任何保活**：
 * 第三期已把 HostWindowKeeper / WindowWakeLock / WindowKeeper / HostLauncher 整类删除。
 * 窗口期被冻结/被杀属于待观察项，不预先加补丁。
 *
 * 【降级】装配没完成时 {@link #onSwipe} 返回 false，由 NfcSwipeHook 回落
 * 到 B 架构的 NfcWakeClient —— 保证这一版即使 tsmclient 侧有未知限制，
 * 刷卡行为也不会比 2.0.51 更差。降级会在日志里明确标出。
 */
public final class NfcLocalExec {

    private static final String TAG = "NFC_LOCAL";

    /** system 侧发布好的 so（2.0.47 起）：tsmclient 解不出 / 加载不了时的兜底。 */
    private static final String SYSTEM_SO =
            "/data/system/bydhk/so/libwbsk_crypto_tool.so";

    /** 单线程：装配 + 每次刷卡串行，避免两次刷卡同时开窗。 */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BYDHK-NFC-LOCAL");
        t.setDaemon(true);
        return t;
    });

    private static volatile SharedPreferences sPrefs;
    private static volatile File sFilesDir;
    private static volatile boolean sReady = false;

    /** 必须持有强引用，否则 SharedPreferences 的 listener 会被 GC 掉。 */
    private static SharedPreferences.OnSharedPreferenceChangeListener sListener;

    private NfcLocalExec() {
    }

    /** 装配是否完成（三项前置全绿）。 */
    public static boolean isLocalMode() {
        return sReady;
    }

    /**
     * tsmclient 进程装配（由 {@code XposedModuleEntry.assembleNfc} 在 onPackageLoaded 调用）。
     *
     * 不阻塞注入回调：解密 / 解 so / 加载 dex 全在后台线程做；
     * 用户从开机到刷卡至少有几十秒，足够跑完。
     *
     * @param sp            getRemotePreferences(bydhooker_main) 的句柄
     * @param filesDir      tsmclient 自己的 files 目录（凭据与日志落这里）
     * @param moduleApkPath 模块 base.apk 路径（解白盒 so 用）
     * @param dataDir       tsmclient 的 dataDir（code_cache 解包用）
     */
    public static void init(final SharedPreferences sp, final File filesDir,
                            final String moduleApkPath, final String dataDir) {
        EXEC.execute(() -> {
            try {
                doInit(sp, filesDir, moduleApkPath, dataDir);
            } catch (Throwable t) {
                EventLog.INSTANCE.error("NFC_LOCAL_INIT_ERR",
                        t.getClass().getName() + "|" + t.getMessage());
            }
        });
    }

    private static void doInit(SharedPreferences sp, File filesDir,
                               String moduleApkPath, String dataDir) {
        sPrefs = sp;
        sFilesDir = filesDir;

        // ① 日志（tsmclient 私有目录；v3.0 起刷卡事件 TsmNfcLog 也委托写进这一份）
        EventLog.INSTANCE.init(filesDir);
        EventLog.INSTANCE.info(TAG, "tsmclient 就地执行层装配开始|dir=" + filesDir);

        if (sp == null) {
            EventLog.INSTANCE.error("NFC_LOCAL_INIT", "getRemotePreferences 返回 null，就地执行不可用");
            return;
        }
        Map<String, ?> all = sp.getAll();
        EventLog.INSTANCE.info(TAG, "remote prefs 快照|keys=" + (all == null ? -1 : all.size()));

        // ② 配置 + 凭据（同一份快照；与宿主侧 2.0.50 起完全同口径）
        //    ⚠️ 必须先 attach：① 决定 cred.bin 的读取目录（进程重启后靠它恢复）
        //    ② keystore=false ⇒ 写 CBC 格式（2.0.51 已证宿主 Keystore 读不开）
        CredentialVault.INSTANCE.attach(filesDir, false);
        ConfigStore.INSTANCE.syncFromSnapshot(all);
        loadCred(all);

        // ③ 配置/凭据变更监听（用户可能在 tsmclient 活着的时候改 NFC 模式、选卡、重新授权）
        sListener = (shared, key) -> {
            if (key == null) return;
            try {
                Map<String, ?> now = shared.getAll();
                ConfigStore.INSTANCE.syncFromSnapshot(now);
                if (BridgeContract.INSTANCE.getCRED_KEYS().contains(key)
                        || BridgeContract.KEY_CRED_PRESENT.equals(key)) {
                    loadCred(now);
                }
                EventLog.INSTANCE.debug("NFC_LOCAL_CFG", "配置更新：" + key);
            } catch (Throwable ignored) {
            }
        };
        sp.registerOnSharedPreferenceChangeListener(sListener);

        // ④ 白盒密钥表 + so
        try {
            WbKeys.INSTANCE.load();
        } catch (Throwable t) {
            EventLog.INSTANCE.error("NFC_LOCAL_WBKEYS", t.getClass().getSimpleName());
        }
        boolean so = ensureSo(moduleApkPath, dataDir);

        // ⑤ 车况数据源（StatusPoller.source）
        boolean src = false;
        try {
            WatchRequest.INSTANCE.initStatusSource();
            src = WatchRequest.INSTANCE.getStatusSource() != null;
        } catch (Throwable t) {
            EventLog.INSTANCE.error("NFC_LOCAL_SRC", t.getClass().getSimpleName());
        }

        // ⑥ 凭据预热：把 PBKDF2 的 200~400 ms 花在装配期，别压在刷卡那一下
        boolean cred = false;
        try {
            cred = CredentialVault.INSTANCE.current() != null;
        } catch (Throwable t) {
            EventLog.INSTANCE.error("NFC_LOCAL_CRED", t.getClass().getSimpleName());
        }

        sReady = cred && so && src;
        EventLog.INSTANCE.info("NFC_LOCAL_INIT",
                (sReady ? "就绪" : "未就绪，刷卡将降级到 B 架构")
                        + "|cred=" + cred + "|so=" + so + "|src=" + src
                        + "|nfcEnabled=" + ConfigStore.INSTANCE.getNfcEnabled()
                        + "|nfcMode=" + ConfigStore.INSTANCE.getNfcMode()
                        + "|selectedAid=" + maskAid(ConfigStore.INSTANCE.getNfcSelectedAid()));
    }

    /** 从 remote prefs 快照写一份凭据到 tsmclient 私有目录（软件密钥 + CBC，与另三进程同格式）。 */
    private static void loadCred(Map<String, ?> all) {
        if (all == null) return;
        Object present = all.get(BridgeContract.KEY_CRED_PRESENT);

        // v3.0.4：**清除分支**。present=false 是设置页「清除凭据」写入的持久化意图。
        // 旧行为：onCredChanged 开头 `present != true → return` ⇒ tsmclient 的
        // cred.bin 与内存 cached 残留，清除后 NFC 仍能用旧凭据上电（核查发现，
        // 与 2026-11-09 宿主那次同型）。
        if (!Boolean.TRUE.equals(present)) {
            boolean hasFile = sFilesDir != null
                    && new java.io.File(sFilesDir, "cred.bin").exists();
            boolean hasCached = CredentialVault.INSTANCE.peek() != null;
            if (hasFile || hasCached) {
                CredentialVault.INSTANCE.clear(sFilesDir);
                EventLog.INSTANCE.warn(TAG, "清除意图（present=false）→ tsmclient 凭据已清除");
            }
            return;
        }

        Map<String, Object> cred = new HashMap<>();
        for (String k : BridgeContract.INSTANCE.getCRED_KEYS()) {
            if (all.containsKey(k)) cred.put(k, all.get(k));
        }
        cred.put(BridgeContract.KEY_CRED_PRESENT, present);
        try {
            CredentialVault.INSTANCE.onCredChanged(sFilesDir, cred, false);
        } catch (Throwable t) {
            EventLog.INSTANCE.error("NFC_LOCAL_CRED", "写入失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 白盒 so：先在 tsmclient 自己的 code_cache 解包加载（与官方 App 同款 P1-3 路径），
     * 失败再退到模块 App 已经发布 + chcon 好的 /data/system/bydhk/so/ 那份。
     *
     * 后者是 2.0.47 为 system_server 准备的，SELinux 上下文是 system_file；
     * tsmclient 域能否 execute 它未验证，所以只当兜底，失败原因原样记日志。
     */
    private static boolean ensureSo(String moduleApkPath, String dataDir) {
        try {
            if (Whitebox.INSTANCE.ensureLoaded(moduleApkPath, dataDir)) {
                EventLog.INSTANCE.info(TAG, "白盒 so 就地加载成功（code_cache）");
                return true;
            }
            EventLog.INSTANCE.warn(TAG, "code_cache 路径失败，尝试 system 侧已发布的 so");
        } catch (Throwable t) {
            EventLog.INSTANCE.warn(TAG, "code_cache 异常：" + t.getClass().getSimpleName());
        }
        File f = new File(SYSTEM_SO);
        if (!f.exists()) {
            EventLog.INSTANCE.error(TAG, "兜底 so 不存在：" + SYSTEM_SO);
            return false;
        }
        try {
            System.load(f.getAbsolutePath());
            CryptoTool.markLoaded();
            EventLog.INSTANCE.info(TAG, "白盒 so 经 system 侧路径加载成功");
            return true;
        } catch (Throwable t) {
            EventLog.INSTANCE.error(TAG,
                    "兜底 so 加载失败：" + t.getClass().getName() + "|" + t.getMessage());
            return false;
        }
    }

    /**
     * 刷卡入口（在 {@link NfcSwipeHook} 的 Hook 线程调用，**立即返回**）。
     *
     * @return true = 已由本地链路接管；false = 本地没就绪，调用方回落 B 架构
     */
    public static boolean onSwipe(final Context ctx, final String aid, final long ts) {
        if (!sReady) {
            TsmNfcLog.log(ctx, "LOCAL_NOT_READY", "就地执行层未就绪，本次刷卡放弃（B 架构已删除）");
            return false;
        }
        EXEC.execute(() -> {
            try {
                doSwipe(ctx, aid, ts);
            } catch (Throwable t) {
                EventLog.INSTANCE.error("NFC_LOCAL_ERR", t.getClass().getName());
            }
        });
        return true;
    }

    private static void doSwipe(Context ctx, String aid, long ts) {
        // 每次刷卡都吃一份最新快照：ConfigStore 是进程内缓存，
        // listener 可能因为框架 Diff 推送丢包而落后（B 架构踩过这个坑）。
        SharedPreferences sp = sPrefs;
        if (sp != null) {
            try {
                ConfigStore.INSTANCE.syncFromSnapshot(sp.getAll());
            } catch (Throwable ignored) {
            }
        }
        String sel = "";
        try {
            sel = sp == null ? "" : sp.getString(BridgeContract.KEY_NFC_SELECTED_AID, "");
        } catch (Throwable ignored) {
        }
        if (sel == null) sel = "";

        // R-AID-7：没选卡不触发；AID 严禁写死
        if (sel.isEmpty()) {
            TsmNfcLog.log(ctx, "LOCAL_NO_CARD", "未选择触发卡");
            EventLog.INSTANCE.info(TAG, "未选择触发卡，忽略本次刷卡");
            return;
        }
        if (!sel.equalsIgnoreCase(aid)) {
            TsmNfcLog.log(ctx, "LOCAL_AID_MISMATCH", "aid=" + aid + "|sel=" + maskAid(sel));
            EventLog.INSTANCE.info(TAG, "刷的不是选中卡，忽略");
            return;
        }

        TsmNfcLog.log(ctx, "LOCAL_HIT",
                "aid=" + aid + "|ts=" + ts + "|mode=" + ConfigStore.INSTANCE.getNfcMode());
        EventLog.INSTANCE.info(TAG,
                "AID 匹配，就地执行|ts=" + ts
                        + "|nfcEnabled=" + ConfigStore.INSTANCE.getNfcEnabled()
                        + "|nfcMode=" + ConfigStore.INSTANCE.getNfcMode());

        // ── M2 基线快速路径（v3.0.3，2026-11-13 用户拍板）──
        //
        // 【为什么】M2 原来等云端 doorLock 2→1 边沿，真车实测那段链路延迟 ~5 s。
        // 用户打开刷卡页期间 NfcBaseline 已持续采好基线（2s 一帧、最长 30s），
        // 刷卡时基线几乎与真实门锁同步 ⇒ 刷卡后不需要云端确认：
        //
        //   基线=2（已锁）  → 本次刷卡=解锁 → 延迟 1 s 直接下发上电
        //   基线=1（已解锁）→ 本次刷卡=上锁 → 零动作、不开窗
        //   基线=null       → 未采到帧 → 回退现行 15 s 边沿判定（兜底，宁可不触发）
        //
        // 【残余风险，用户已明确接受】云端滞后窗口内门锁被他人改变会方向判反
        // → 误上电（锁态收到上电指令）。概率极低，换取刷卡后 ~1 s 上电。
        //
        // 无论哪个分支都先停基线轮询：快速/零动作不再需要它；兜底分支走
        // NfcM2Runner 自身轮询，双循环会加倍打云端。
        if ("M2".equalsIgnoreCase(ConfigStore.INSTANCE.getNfcMode())
                && ConfigStore.INSTANCE.getNfcEnabled()) {
            Integer base = NfcBaseline.current();
            NfcBaseline.stop("刷卡已消费基线（" + base + "）");
            if (base != null && base == 2) {
                EventLog.INSTANCE.info(TAG,
                        "快速路径：基线 doorLock=2（已锁）→ 本次刷卡为解锁，1s 后直接下发上电");
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    EventLog.INSTANCE.warn(TAG, "延迟被中断，跳过上电");
                    return;
                }
                com.bydhooker.autostart.model.Credential cred = CredentialVault.INSTANCE.current();
                if (cred == null) {
                    EventLog.INSTANCE.error(TAG, "凭据未就绪，快速路径放弃上电");
                    return;
                }
                // G-9：一次刷卡只下发一次，verify 只复核不重试
                if (CommandSender.INSTANCE.sendStart(cred)) {
                    CommandSender.INSTANCE.verify(cred);
                } else {
                    EventLog.INSTANCE.error(TAG, "启动指令未被受理（解锁由车端本地完成，不受影响）");
                }
                return;
            }
            if (base != null && base == 1) {
                // 用户拍板：基线已解锁 ⇒ 刷卡必为上锁 ⇒ 不开窗、不做任何动作。
                EventLog.INSTANCE.info(TAG,
                        "基线 doorLock=1（已解锁）→ 本次刷卡为上锁，不动作");
                return;
            }
            EventLog.INSTANCE.info(TAG, "基线不可用（未采到帧）→ 回退 15s 门锁边沿判定");
        }

        // 复用同一套门控与分流（nfcEnabled / M1 开窗 / M2 门锁边沿），
        // 判据一个字没改 —— 第二期只换执行者，不动业务判据。
        TriggerSource.INSTANCE.onNfcSwipe(ts);
    }

    /** AID 只留头 4 位（日志可能被导出，不做全明文）。 */
    private static String maskAid(String s) {
        if (s == null || s.length() <= 4) return s == null ? "" : s;
        return s.substring(0, 4) + "…(" + s.length() + ")";
    }
}

package com.bydhooker.autostart.exec;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 执行侧配置（架构 C，`docs/34` §5 N5）—— **零项目依赖**。
 *
 * 【为什么不读 remote prefs】探针实测（ArchCProbe v1/v2/v3）：system_server 里
 * `getRemotePreferences("bydhooker_main")` **能调用、不抛异常，但 `keys=0`**。
 * 探针读的是它自己模块的 group，所以这次结果不能证明模块自己读也会空；
 * 但「A 通道在 system_server 是否可用」仍属 D-34-1 待定项，而第一期不能卡在它上面。
 *
 * 所以第一期改走**文件**：模块 App（有 root）把配置写到 `/data/system/bydhk/config.txt`，
 * 三个被注入进程直接读。格式极简，`key=value` 一行一个，`#` 开头为注释。
 * 这条路已经实测可用（探针的 DE 读写全绿）。
 *
 * 【刷新策略】进程启动读一次；每次执行前 `reloadIfChanged()` 按 mtime 判断是否重读。
 * 不做监听 —— 丢推送不重放是这条通道的老问题，主动读比监听可靠。
 */
public final class ExecConfig {

    public static final String FILE_NAME = "config.txt";

    /** APP 方案总开关（`enabled`）。关 → 三击仍解锁，但不上电。 */
    public volatile boolean enabled = true;

    /** `M1` 开门上电 / `M2` 解锁即上电。 */
    public volatile String mode = "M1";

    /** M1 窗口秒数。 */
    public volatile int windowSeconds = 60;

    /** 车况轮询间隔（ms）。 */
    public volatile int pollIntervalMs = 1000;

    private volatile long loadedMtime = -1L;

    private final File dir;

    private ExecConfig(File dir) {
        this.dir = dir;
    }

    public static ExecConfig load(File dir) {
        ExecConfig c = new ExecConfig(dir);
        c.reload();
        return c;
    }

    /**
     * 文件有变化才重读（按 mtime）。
     *
     * @return true = 本次真的重读了
     */
    public boolean reloadIfChanged() {
        File f = new File(dir, FILE_NAME);
        long m = f.exists() ? f.lastModified() : -1L;
        if (m == loadedMtime) {
            return false;
        }
        return reload();
    }

    private boolean reload() {
        File f = new File(dir, FILE_NAME);
        loadedMtime = f.exists() ? f.lastModified() : -1L;
        if (!f.exists()) {
            ExecLog.warn("CFG_MISS", "config.txt 不存在，用默认值 enabled=true|mode=M1");
            return false;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                apply(k, v);
            }
            ExecLog.info("CFG_LOAD", "enabled=" + enabled + "|mode=" + mode
                    + "|window=" + windowSeconds + "|poll=" + pollIntervalMs);
            return true;
        } catch (Throwable t) {
            ExecLog.error("CFG_ERR", t.getClass().getSimpleName() + "|" + t.getMessage());
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void apply(String k, String v) {
        try {
            if ("enabled".equals(k)) {
                enabled = "true".equalsIgnoreCase(v) || "1".equals(v);
            } else if ("mode".equals(k)) {
                mode = v.isEmpty() ? "M1" : v;
            } else if ("windowSeconds".equals(k)) {
                int n = Integer.parseInt(v);
                // 夹到合理区间，防止配置写错导致窗口开一天
                windowSeconds = Math.max(10, Math.min(180, n));
            } else if ("pollIntervalMs".equals(k)) {
                int n = Integer.parseInt(v);
                pollIntervalMs = Math.max(500, Math.min(5000, n));
            }
        } catch (Throwable ignored) {
            // 单个键解析失败不影响其余键（配置文件是人工可编辑的，必须容错）
        }
    }

    public boolean isM2() {
        return "M2".equalsIgnoreCase(mode);
    }
}

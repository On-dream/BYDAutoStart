package com.bydhooker.autostart.exec;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 白盒 so 加载器（架构 C，`docs/34` §5 N4）—— **零项目依赖**。
 *
 * 【为什么需要它】`System.loadLibrary("wbsk_crypto_tool")` 只在「模块自己的 App 进程」可用
 * （APK 的 nativeLibraryDir 在 classloader 里）。官方 App / tsmclient / system_server
 * 三个进程都没有这个目录，只能把 so **解到文件系统某个绝对路径**再 `System.load`。
 *
 * 【真机实证（2026-11-13 14:57，ArchCProbe v3）】system_server 里能不能加载 /data 下的 so，
 * 卡的是 **SELinux 上下文**，不是权限位：
 * ```
 * ctx=u:object_r:system_data_file:s0 → canExec=false → dlopen EACCES
 * chcon u:object_r:system_file:s0   → canExec=true  → System.load 成功
 * ```
 * 而且 `chcon` 的重启后仍然保持（`restorecon` 没扫 `/data/system/bydhk/`）。
 * ⇒ 本类**只负责解压 + chmod**；`chcon` 必须由 root 在解压之后执行一次
 * （system_server 自己改不了上下文），见 {@link #NEEDS_CHCON_NOTE}。
 *
 * 【顺序不能反】先解压 → 再 chcon → 最后 load。反过来 chcon 会被覆盖掉。
 */
public final class InjectableSoLoader {

    public static final String SO_NAME = "libwbsk_crypto_tool.so";

    /** 记录给排障用：这一步 system_server 做不了，必须 root 补。 */
    public static final String NEEDS_CHCON_NOTE =
            "解压后需 root 执行 chcon u:object_r:system_file:s0 <so>";

    private static volatile boolean sLoaded = false;

    private InjectableSoLoader() {
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * 确保 so 已加载。
     *
     * @param pm      用于定位模块 APK（{@link #findModuleApk}）
     * @param destDir 解压目标目录（system 侧用 `/data/system/bydhk`）
     * @return true = 已加载（本次或此前）
     */
    public static boolean ensureLoaded(PackageManager pm, File destDir) {
        if (sLoaded) {
            return true;
        }
        synchronized (InjectableSoLoader.class) {
            if (sLoaded) {
                return true;
            }
            // ① 目标文件已存在就直接用 —— **绝不能覆盖**：
            //    覆盖会重建 inode，SELinux 上下文退回目录默认值，root 之前 chcon 的结果就没了。
            //
            //    放在 `so/` 子目录而不是工作目录根：Android 的 linker 规定
            //    **同一条文件路径只能被一个 classloader 加载一次**。
            //    工作目录根下可能还躺着别的模块解出的同一份 so（真机上就是架构 C 探针，
            //    它解到 `/data/system/bydhk/libwbsk_crypto_tool.so`），
            //    共用路径 ⇒ 谁的 classloader 先加载谁赢，另一边直接 `already opened` 失败。
            //    独立子目录从根上避免这种撞车。
            File soDir = new File(destDir, "so");
            if (!soDir.exists() && !soDir.mkdirs()) {
                ExecLog.error("SO_ERR", "无法创建 so 目录：" + soDir.getAbsolutePath());
                return false;
            }
            File so = new File(soDir, SO_NAME);
            if (!so.exists() || so.length() <= 0) {
                File apk = findModuleApk(pm);
                if (apk == null) {
                    ExecLog.error("SO_ERR", "找不到模块 APK，无法解压 so");
                    return false;
                }
                try {
                    String entry = extract(apk, so);
                    if (entry == null) {
                        ExecLog.error("SO_ERR", "APK 里没有 " + SO_NAME);
                        return false;
                    }
                    ExecLog.info("SO_EXTRACT", "entry=" + entry + "|len=" + so.length()
                            + "|path=" + so.getAbsolutePath());
                } catch (Throwable t) {
                    ExecLog.error("SO_ERR", "解压失败：" + brief(t));
                    return false;
                }
            } else {
                ExecLog.info("SO_REUSE", "len=" + so.length() + "|path=" + so.getAbsolutePath());
            }

            // ② 权限位（other 可读可执行）。注意：这**不解决 SELinux**，
            //    只解决「mmap(PROT_EXEC) 要求文件有 x 位」这一半。
            boolean canExec = so.canExecute();
            if (!canExec) {
                so.setReadable(true, false);
                so.setExecutable(true, false);
                canExec = so.canExecute();
            }
            if (!canExec) {
                // 到这里基本就是上下文不对了（真机实证：system_data_file 下 canExec 恒 false）
                ExecLog.error("SO_ERR", "文件不可执行（多半是 SELinux 上下文）：" + NEEDS_CHCON_NOTE);
                return false;
            }

            // ③ 加载
            //    ⚠️ 不能只看 `CryptoTool.loadFrom` 的返回值：它内部把异常吞了只回 false，
            //    现场就只剩一句「加载失败」，连是 SELinux 拒绝还是权限位问题都分不出来
            //    （2026-11-13 15:55 真机踩过：so 在、权限 0755、也 REUSE 了，就是不知道为啥失败）。
            //    所以这里自己先 load 一遍**只为拿到异常**，成功后再让 CryptoTool 认领
            //    （它要置 loaded 标志，`Whitebox.ensureLoaded` 开头那句会直接命中）。
            try {
                System.load(so.getAbsolutePath());
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                // ⚠️ "already opened" **不是失败**：同一条 so 路径已被本进程内另一个
                // classloader 加载了（真机 2026-11-13 16:47 就是被架构 C 探针抢先加载）。
                // 那时 so 已在进程内、**JNI 符号已注册**，而 linker 的符号表是进程全局的
                // ⇒ 任何 classloader 里的 CryptoTool 都能调到那些 native 方法。
                // 所以这里直接认领「已加载」，而不是整条链路失败。
                if (msg.contains("already opened")) {
                    com.wbsk.CryptoTool.markLoaded();
                    sLoaded = true;
                    ExecLog.info("SO_LOAD_OK",
                            "so 已由本进程内其它 classloader 加载，复用其 JNI 符号|path="
                                    + so.getAbsolutePath());
                    return true;
                }
                ExecLog.error("SO_ERR", "dlopen 失败|" + t.getClass().getName()
                        + "|" + t.getMessage()
                        + "|path=" + so.getAbsolutePath()
                        + "|ctx=" + selinuxCtx(so)
                        + "|canExec=" + so.canExecute());
                return false;
            }
            try {
                // 同一路径重复 load 是安全的（linker 直接返回）；只为把 loaded 标志置上
                com.wbsk.CryptoTool.loadFrom(so.getAbsolutePath());
            } catch (Throwable ignored) {
            }
            sLoaded = true;
            ExecLog.info("SO_LOAD_OK", "path=" + so.getAbsolutePath() + "|ctx=" + selinuxCtx(so));
            return true;
        }
    }

    /** 读文件的 SELinux 上下文（`security.selinux`）。 */
    private static String selinuxCtx(File f) {
        try {
            byte[] b = android.system.Os.getxattr(f.getAbsolutePath(), "security.selinux");
            return new String(b, Charset.forName("UTF-8")).trim();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * 定位模块 APK。
     *
     * 优先 `getModuleApplicationInfo()`（libxposed 提供，最准），失败再扫 `/data/app`。
     * Android 11+ 的路径多一层 `~~xxx==`，所以要递归。
     */
    private static File findModuleApk(PackageManager pm) {
        // ① 常规：按包名查
        try {
            ApplicationInfo ai = pm.getApplicationInfo("com.bydhooker.autostart", 0);
            if (ai != null && ai.sourceDir != null) {
                File f = new File(ai.sourceDir);
                if (f.exists()) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 兜底：扫 /data/app（有些进程的 PackageManager 看不到模块包）
        if (Build.VERSION.SDK_INT >= 21) {
            File[] top = new File("/data/app").listFiles();
            if (top != null) {
                for (File d : top) {
                    File hit = scan(d, 0);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static File scan(File dir, int depth) {
        if (dir == null || depth > 3) {
            return null;
        }
        if (dir.getName().contains("com.bydhooker.autostart")) {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().endsWith(".apk")) {
                        return f;
                    }
                }
                for (File f : fs) {
                    if (f.isDirectory()) {
                        File hit = scan(f, depth + 1);
                        if (hit != null) {
                            return hit;
                        }
                    }
                }
            }
            return null;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return null;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                File hit = scan(f, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 从 APK 解出 so。**优先 arm64-v8a** —— 64 位进程加载 32 位 so 必失败。 */
    private static String extract(File apk, File out) throws Exception {
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry hit = z.getEntry("lib/arm64-v8a/" + SO_NAME);
            if (hit == null) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().endsWith(SO_NAME)) {
                        hit = e;
                        break;
                    }
                }
            }
            if (hit == null) {
                return null;
            }
            InputStream in = z.getInputStream(hit);
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            return hit.getName();
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String brief(Throwable t) {
        return t.getClass().getSimpleName() + "|" + t.getMessage();
    }
}

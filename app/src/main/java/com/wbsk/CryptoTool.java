package com.wbsk;

/**
 * 白盒 so JNI 壳（与 E 路线 / P1 探针 `com.wbsk.CryptoTool` **同包同类同方法**——
 * so 内 JNI 符号 `Java_com_wbsk_CryptoTool_*` 与类名强绑定，不得改动包名/类名）。
 *
 * 加载方式（裁定 B2，P1-3 实测）：显式 {@link System#load} / {@link System#loadLibrary}
 * 由 Whitebox 控制三路径；本类只声明 native 方法与加载状态。
 */
public final class CryptoTool {

    private static volatile boolean loaded = false;

    private CryptoTool() {
    }

    /** 显式加载 so 绝对路径（路径③）。返回是否成功。 */
    public static boolean loadFrom(String soPath) {
        try {
            System.load(soPath);
            loaded = true;
            return true;
        } catch (Throwable t) {
            loaded = isLoaded();
            return loaded;
        }
    }

    /** loadLibrary 语义（路径①；runtime 从模块 APK 内 mmap 未压缩 so）。 */
    public static boolean loadViaSystemLibrary() {
        try {
            System.loadLibrary("wbsk_crypto_tool");
            loaded = true;
            return true;
        } catch (Throwable t) {
            loaded = isLoaded();
            return loaded;
        }
    }

    /** JNI 注册成功后为 true（native 方法可调用）。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 认领「so 已由本进程内**另一个** classloader 加载」的情况（2026-11-13 架构 C）。
     *
     * 【场景】system_server 里可能有两个模块都解出同一个 so 并加载
     * （例：架构 C 探针 ArchCProbe 与本模块用同一路径 `/data/system/bydhk/`）。
     * Android 的 linker 规定**同一条文件路径只能被一个 classloader 加载一次**，
     * 后来者会抛 `UnsatisfiedLinkError: already opened by ClassLoader 0x...`。
     *
     * 【关键】那个异常**不是失败** —— so 已经在本进程里加载好了，JNI 符号也已注册，
     * 而 linker 的符号表是**进程全局**的 ⇒ 用任何 classloader 里的 `CryptoTool`
     * 去调 `Java_com_wbsk_CryptoTool_*` 都能命中。
     * 所以后来者只需把 `loaded` 置真即可，不该整条链路失败。
     */
    public static void markLoaded() {
        loaded = true;
    }

    /** 外层白盒加密。**签名与 P1 探针实测版逐字一致**：key 第二参为
     *  {@link String}（hex 字符串，parse 在 so 内）——真机首装 0/13 的根因就是
     *  此处误写 byte[]，JNI 按参数类型绑定符号，必须与探针版逐字一致。 */
    public static native byte[] aesEncryptByteArr(byte[] data, String key, byte[] iv);

    /** 外层白盒解密（响应方向；M-Q3 已实证与 E 路线逐字节一致；key 同上 String）。 */
    public static native byte[] aesDecryptByteArr(byte[] data, String key, byte[] iv);
}

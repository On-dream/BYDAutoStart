package com.bydhooker.autostart.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 外部跳转（**2026-11-10 新增**，设置页「支持开发 / 追踪更新」两行）。
 *
 * ## 与 root / LSPosed 完全无关
 * 这里全是**普通隐式 Intent**：任何普通 App 都能发。不依赖 Xposed 激活、不依赖 root、
 * 也不依赖官方 App 在运行 —— 与本工程「快速授权」（要 `am start` 拉起官方 App
 * **未导出**的 Activity、要写宿主私有目录）是两个完全不同量级的事。
 *
 * 真实前提只有三条，都不是权限：
 *  ① 支付宝已安装（包名 `com.eg.android.AlipayGphone`）；
 *  ② `AndroidManifest` 的 `<queries>` 里声明过该包 —— **只有**「判断装没装」
 *     （`getPackageInfo`）需要；`startActivity(隐式 Intent)` 本身受 Android 11
 *     包可见性影响的是**查询**，不是启动；
 *  ③ Android 10+ 限制后台启动 Activity → 必须由用户点击触发。本类全部由点击调用，
 *     天然满足。
 * 部分国产 ROM（HyperOS 等）首次跨应用跳转会弹一次「是否允许跳转」，这是 ROM 行为，
 * 用户点「允许」即可。
 *
 * ## B 方案实测结论（2026-11-10 19:30，本机 元 Pro 设备实测）
 * 用户提供的收款码**内容本身**就是一条 https 链接：
 * `https://qr.alipay.com/fkx14151wjq3n0kuywt00ce`
 * （用 zxing-cpp 从原图解出，非猜测）。
 *
 * 但**「把链接交给支付宝」这条路在本机走不通**，已实证：
 *  · `cmd package resolve-activity` 解该 https 链接，命中的是 `mark.via/.Trampoline`
 *    （Via 浏览器），不是支付宝 → **支付宝没有把 `qr.alipay.com` 注册成 App Link**；
 *  · `am start -a VIEW -d <链接> -p com.eg.android.AlipayGphone` →
 *    `Error: Activity not started, unable to resolve`，ATMS 侧 `result code=-91`。
 * 故 `setPackage` 那一步必然抛 `ActivityNotFoundException`。
 *
 * ## 实测可用的直达路径（逐步试，全部有日志/截图佐证）
 *  ① 链接 + `setPackage(支付宝)` —— 保留但预期失败：将来支付宝若补上 App Link 自动生效，
 *     代价只是一次 `ActivityNotFoundException`；
 *  ② **`alipays://platformapi/startapp?appId=10000007`** —— 实测直达
 *     `com.alipay.mobile.scan.as.main.MainCaptureActivity`（**扫一扫相机界面**，
 *     右下角即「相册」入口）。这是本机最优路径：用户截图后点按钮 → 进扫一扫 →
 *     选「相册」→ 选中截图 → 直接到付款页；
 *     ⚠ 该 appId 是**实测得出**，不是凭记忆写的；支付宝换版本可能失效 → 因有 ③ 兜底，可接受；
 *  ③ 支付宝启动 Intent（首页）—— 最后兜底，用户自己在首页找「扫一扫」。
 * 中间**不**做「不带包限定的 `ACTION_VIEW(https)`」：那一步实测会落到 Via 浏览器
 * （见上），用户还得在网页里再点一次「打开支付宝」，是纯噪音。
 *
 * 另有一条**已排除**的路：`alipays://platformapi/startapp?appId=20000067&url=<收款码链接>`
 * 实测打开的是 `XRiverActivity` 里的**「商家服务」页**（收款方自己的收银台视角，
 * 显示"今日收款 0.00 元"），不是付款页 —— 因为登录态就是收款方本人，不能用来给自己付款。
 */
object AppLinks {

    /** 支付宝包名（官方 Android 包名，长期稳定）。 */
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 收款码内容（= 二维码里的原始链接，从用户提供的原图解码得到）。 */
    const val DONATE_QR_URL = "https://qr.alipay.com/fkx14151wjq3n0kuywt00ce"

    /**
     * 支付宝「扫一扫」直达 scheme（**2026-11-10 本机实测**：打开后窗口是
     * `com.alipay.mobile.scan.as.main.MainCaptureActivity`，界面右下角有「相册」）。
     */
    const val ALIPAY_SCAN_URL = "alipays://platformapi/startapp?appId=10000007"

    /** 酷安开发者主页（设置页「追踪更新」）。 */
    const val COOLAPK_URL = "https://www.coolapk.com/u/681094"

    /**
     * 酷安 App 包名（2026-11-10 本机实测：该 https 链接的处理者是
     * `com.coolapk.market/.view.AppLinkActivity`）。
     *
     * **策略（用户 2026-11-10 拍板）**：**装了酷安就直投，没装才走网页**。
     *  · 装了 → `setPackage` 直投，不弹系统「打开方式」选择器（本机还装了 Via，
     *    不限定就一定会弹，实测截图见 v0.9.52 记录）；
     *  · 没装 → **不做** `setPackage`（必然抛 `ActivityNotFoundException`），
     *    直接走不限定的 `ACTION_VIEW` → 浏览器打开网页，网页会引导下载酷安。
     * 判「装没装」用 `getPackageInfo`，依赖 `AndroidManifest` 的 `<queries>` 声明。
     */
    const val COOLAPK_PACKAGE = "com.coolapk.market"

    /** 通用「包是否已安装」判断（依赖 `<queries>` 声明，否则结果恒为 false）。 */
    fun isPackageInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * 判断支付宝是否已安装。
     *
     * 依赖 `AndroidManifest.xml` 的 `<queries><package .../></queries>` ——
     * targetSdk 30+ 起未声明的包 `getPackageInfo` 会抛 `NameNotFoundException`，
     * 与「真没装」无法区分。声明后两者才可区分。
     */
    fun isAlipayInstalled(ctx: Context): Boolean = isPackageInstalled(ctx, ALIPAY_PACKAGE)

    /**
     * 打开支付宝收款页。
     *
     * @return 空串 = 已成功发起跳转；非空 = 应弹 Toast 给用户的提示文案。
     *   **未安装时仍可点**（用户 2026-11-10 指定「未装支付宝时可点，弹 toast」）——
     *   这里不做按钮置灰，只把结论作为 Toast 文案返回。
     */
    fun openDonate(ctx: Context): String {
        val app = ctx.applicationContext

        // ① 链接交给支付宝，直达付款页（本机实测走不通，见类注释；保留给未来 App Link）。
        if (tryStart(app, viewIntent(DONATE_QR_URL, ALIPAY_PACKAGE))) return ""

        // ② 直达扫一扫（本机实测可用 → `MainCaptureActivity`）。不带 setPackage：
        //    `alipays://` 只有支付宝会处理，不会弹选择器；不带包限定也与我实测的
        //    命令行形态一致，少一个变量。
        if (tryStart(app, viewIntent(ALIPAY_SCAN_URL, null))) return ""

        // ③ 兜底：支付宝首页，用户自己找「扫一扫」。
        val home = try {
            app.packageManager.getLaunchIntentForPackage(ALIPAY_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Throwable) {
            null
        }
        if (home != null && tryStart(app, home)) return ""

        // 三条都不通 → 按「装没装」给不同提示（用户能据此判断该装 App 还是该查 ROM 设置）。
        return if (isAlipayInstalled(app)) "无法打开支付宝，请手动打开"
        else "未安装支付宝"
    }

    /**
     * 用系统方式打开一个 https 链接。
     *
     * @param preferPackage 非空且**该包确实已安装**时，先限定该包直投（避开系统
     *   「打开方式」选择器）；未安装则**跳过**直投（否则必然抛异常，且毫无意义），
     *   直接走不限定的 `ACTION_VIEW` → 浏览器开网页。
     *
     * 语义（用户 2026-11-10 拍板）：**装了客户端就用客户端，没装就走网页**
     * —— 不强制绑定某个 App，也不让装了的用户多点一次选择器。
     */
    fun openUrl(ctx: Context, url: String, preferPackage: String? = null): String {
        val app = ctx.applicationContext
        if (preferPackage != null && isPackageInstalled(app, preferPackage) &&
            tryStart(app, viewIntent(url, preferPackage))
        ) return ""
        // ① 未装 preferPackage；或 ② 装了但它没注册该链接（直投失败）→ 退回系统解析。
        //    此时若只有一个处理者（只装了浏览器）会直接打开、不弹选择器。
        if (tryStart(app, viewIntent(url, null))) return ""
        return "没有可用的浏览器"
    }

    /** 构造 `ACTION_VIEW`；`pkg` 非空时限定由该包处理。 */
    private fun viewIntent(url: String, pkg: String?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }

    /** `startActivity` 的安全封装：`ActivityNotFoundException` / `SecurityException` 一律视为失败。 */
    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }
}

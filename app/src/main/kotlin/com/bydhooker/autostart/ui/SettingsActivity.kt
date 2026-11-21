package com.bydhooker.autostart.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.bydhooker.autostart.data.XposedServiceGate
import com.bydhooker.autostart.ui.liquid.IosLiquidGlassNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/**
 * 设置页（模块 App 进程；架构 A：本页直接写 remote prefs，授权流程在本页执行）。
 *
 * 单 Activity + 底栏三 Tab `首页 / 日志 / 授权`（`18` §2，Q-2 顺序定稿）；
 * TopAppBar 标题与 Tab 名不一致为 Q-6 保留（`自动上电` / `事件日志` / `授权管理`）。
 * **不声明任何 intent-filter**（API 102 管理器打开机制【不知道·P1① 实测】，`18` §11 #5）。
 */
class SettingsActivity : ComponentActivity() {

    /**
     * 日志导出的 SAF 入口（2026-11-10）。
     *
     * `ActivityResultContracts.CreateDocument("text/plain")` = 系统文件管理器的
     * 「保存到哪里」界面（`ACTION_CREATE_DOCUMENT`），用户自己选目录与文件名：
     *  · 不需要任何存储权限（SAF 授权单次 URI）；
     *  · 不经过分享面板（用户要的是「选位置」而不是「发给谁」）；
     *  · 不用 `rememberLauncherForActivityResult` —— 本工程没有 activity-compose，
     *    设置页是 `ComposeView.setContent`，故 launcher 注册在这里，通过
     *    `SettingsState.exportLogs` 把触发权交给日志页。
     */
    private lateinit var exportLauncher: androidx.activity.result.ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerExportLauncher()
        XposedServiceGate.register()
        SettingsState.appContext = applicationContext
        // 设置页进程自己的装配（宿主装配只在官方 App 进程跑；架构 A 授权流程
        // 在本页发 HTTPS → 本进程需要 Whitebox so + 表就绪）。
        // 路径① loadLibrary（模块 APK 内未压缩 so mmap）真机已证可走通。
        try {
            com.bydhooker.autostart.data.HostLogReader.attach(this)
        } catch (_: Throwable) {
        }
        try {
            com.bydhooker.autostart.core.EventLog.init(filesDir)
            com.bydhooker.autostart.core.Whitebox.ensureLoaded(packageCodePath, filesDir.parent)
        } catch (_: Throwable) {
        }
        val controller = ThemeController(ColorSchemeMode.System)
        val view = ComposeView(this)
        view.setContent {
            // 【2026-11-21 显式注入 NavigationEventDispatcherOwner —— release 包闪退的修复】
            //
            // 背景（真机 logcat 实抓，不是推测）：
            //  · debug 包：升级 activity 到 1.13.0 后，GlassDialog 不再崩 ——
            //    因为 ComponentActivity 实现了 NavigationEventDispatcherOwner，
            //    AbstractComposeView 会从 ViewTree 自动注入 LocalNavigationEventDispatcherOwner。
            //  · **release 包（R8）：同一个弹窗仍然崩**
            //    `IllegalStateException: No NavigationEventDispatcher was provided via
            //     LocalNavigationEventDispatcherOwner` —— 说明 R8 优化把那条
            //    「ViewTree 自动查找 + 接口实现」的链路破坏/裁掉了，自动注入不可靠。
            // 修法：不再依赖自动注入，**在 Composition 根部显式 provide**
            // （官方 androidx.navigationevent:compose 提供的
            //  `rememberNavigationEventDispatcherOwner()` + `LocalNavigationEventDispatcherOwner.provides`）。
            // 这是纯代码调用，R8 无法把显式调用优化掉，debug/release 行为一致。
            val navEventOwner = rememberNavigationEventDispatcherOwner()
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navEventOwner,
            ) {
                MiuixTheme(controller = controller, textStyles = defaultTextStyles()) {
                    Root()
                }
            }
        }
        setContentView(view)

        // 日志刷新循环（R-01，2026-11-16）：**必须跟 Activity 生命周期走**。
        //
        // 原实现写在 Composable 的 `LaunchedEffect(Unit){ while(true){…delay(3000) } }` 里，
        // 而 LaunchedEffect 只在「离开组合」时取消 —— Home 切走、锁屏都不算离开组合
        // （`beyondViewportPageCount=2` 让三页常驻），于是 App 退到后台后仍每 3 s
        // 读一次 events.log（叠加读盘+解析，是本次 review 里唯一的成体系耗电源）。
        //
        // `repeatOnLifecycle(RESUMED)`：进前台才开始，退后台立即取消，回前台重启一轮。
        // 首页「最近事件」因此最多晚一个 3 s 周期刷新，用户不可感知。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    try {
                        com.bydhooker.autostart.data.LogRefresher.refresh()
                    } catch (_: Throwable) {
                    }
                    kotlinx.coroutines.delay(3000L)
                }
            }
        }

        // 【2026-11-21 已删除】原「了解本APP 二级页」的返回键拦截。
        //
        // 删除理由：二级页从「自绘覆盖层 + 布尔开关」改成了 **miuix-nav 真路由栈**
        // （`BhRoute.Guide` 压在 `BhRoute.Main` 之上），返回键与预测性返回手势
        // 由 `NavDisplay(onBack = ...)` 统一接管。
        // 保留这段 `OnBackPressedCallback` 会与 NavDisplay 抢返回事件（双消费 → 一次返回退两层），
        // 因此整段移除，不再有任何 Activity 级返回拦截。
    }

    /**
     * 每次回到前台重读一次 NFC 本地状态（Hook 三层 + 卡列表）。
     *
     * 为什么需要：`NfcLocalState` 的写入发生在 **Provider 被调用时**（刷卡 / 打开小米智能卡），
     * 那可能是用户正在看别的页面、或本 Activity 处于后台的时候。Compose 状态不会被
     * 非 Activity 期间的 SharedPreferences 写入自动刷新（没有注册监听器），
     * 所以 `onResume` 主动拉一次，代价是一次私有 SP 读（可忽略）。
     *
     * `onCreate` 里不用单独调 —— 它之后必然走 `onResume`。
     */
    override fun onResume() {
        super.onResume()
        SettingsState.reloadNfcLocal()
        // 三击开关/就绪标记（`docs/32`）：开关在 system_server 侧读，模块这边只做展示与写入
        SettingsState.reloadTripleState()
    }

    /**
     * 注册日志导出的 SAF 入口，并把触发权交给日志页（`SettingsState.exportLogs`）。
     *
     * 时序：点击「导出」→ 系统文件管理器选位置 → 回调里**后台**合并两端日志并写入。
     * 合并是阻塞的（宿主端要靠 root `cat`，最坏 12 s 预算），所以放在 IO 线程，
     * 期间 `SettingsState.exporting = true` 让按钮置灰，避免重复点击开两个 launcher
     * （`ActivityResultLauncher.launch` 重入会抛 `IllegalStateException`）。
     */
    private fun registerExportLauncher() {
        exportLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) {
                // 用户在文件管理器里按了返回 / 取消
                SettingsState.exporting = false
                toast("已取消导出")
                return@registerForActivityResult
            }
            toast("正在导出…")
            lifecycleScope.launch {
                try {
                    // v0.9.57：build() 返回 {text, hostStatus} —— 宿主端读不到**不算导出失败**，
                    // 而是降级为「只导模块端」，并按 hostStatus 弹不同的提示（见 finishExportToast）。
                    val res = withContext(Dispatchers.IO) {
                        try {
                            com.bydhooker.autostart.data.LogExporter.build(this@SettingsActivity)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    val ok = if (res == null) {
                        false
                    } else {
                        withContext(Dispatchers.IO) {
                            com.bydhooker.autostart.data.LogExporter.write(
                                this@SettingsActivity, uri, res.text,
                            )
                        }
                    }
                    if (!ok || res == null) {
                        toast("导出失败：无法写入所选位置", true)
                    } else {
                        finishExportToast(res.hostStatus)
                    }
                } finally {
                    SettingsState.exporting = false
                }
            }
        }
        SettingsState.exportLogs = {
            exportLauncher.launch(com.bydhooker.autostart.data.LogExporter.suggestedFileName())
        }
    }

    /**
     * 导出完成后的提示（v0.9.57 用户要求：**没授 root 要能看见、并能顺着提示去授权**）。
     *
     * 语义：只要文件写成功就算导出成功；宿主段缺失时**如实说明缺什么、去哪里补**，
     * 不把「只导了一半」说成「已导出」。文案与导出件里的原因/处理行同一口径。
     *
     * **长度**：实测 HyperOS 的 toast 只显示两行，超出会被截断成「…」，故每行都压到
     * 一行放得下（≤ 约 18 个汉字宽），细节留给导出件里的「原因 / 处理」段。
     */
    private fun finishExportToast(status: com.bydhooker.autostart.data.HostLogReader.Status) {
        when (status) {
            com.bydhooker.autostart.data.HostLogReader.Status.NO_ROOT ->
                toast("未授予 ROOT，只导出了模块端日志\nKernelSU 里允许后重试", long = true)
            com.bydhooker.autostart.data.HostLogReader.Status.TIMEOUT ->
                toast("读取宿主日志超时，只导出了模块端\n若是授权弹窗请允许后重试", long = true)
            com.bydhooker.autostart.data.HostLogReader.Status.NO_HOST_FILE ->
                toast("宿主日志还没生成，本次只导出模块端", long = true)
            com.bydhooker.autostart.data.HostLogReader.Status.FAILED ->
                toast("宿主日志读取失败，本次只导出模块端", long = true)
            else -> toast("已导出")
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        try {
            android.widget.Toast.makeText(
                this, msg,
                if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
            ).show()
        } catch (_: Throwable) {
        }
    }

    /**
     * 页面根（2026-11-21 改由 **miuix-nav** 托管，用户拍板「了解本APP 改成真二级页」）。
     *
     * 分工：
     *  · 本函数只负责「路由栈 + 玻璃采样源 + 注入二级页入口」，不含任何页面布局；
     *  · [MainEntry] = 路由根（Scaffold + 三 Tab + 玻璃顶栏/底栏）；
     *  · `BhRoute.Guide` = 二级页（原覆盖层，现在是真正的栈内页面，带转场动画）。
     *
     * **为什么 backdrop 放在这一层**：`rememberLayerBackdrop()` 必须比路由生命周期更长 ——
     * 二级页的玻璃卡片与主界面共用同一个录制源，若放进 MainEntry，
     * 进二级页时录制源会被销毁、玻璃退化为不透明色。
     */
    @Composable
    private fun Root() {
        val backdrop = rememberLayerBackdrop()
        // 路由栈根 = Main，永远不会被弹出（官方 NavDisplay 的 onBack 只弹栈顶）。
        val backStack = rememberNavBackStack<BhRoute>(BhRoute.Main)
        // 「了解本APP」入口注入：SupportCard 藏在 SettingsTab 深处（3 层），
        // 用项目既有的「回调注入」约定（同 SettingsState.exportLogs）避免逐层传 lambda。
        SettingsState.openGuide = {
            if (backStack.lastOrNull() != BhRoute.Guide) backStack.add(BhRoute.Guide)
        }
        CompositionLocalProvider(LocalBhBackdrop provides backdrop) {
            NavDisplay(
                backStack = backStack,
                // 返回键 / 预测性返回由 NavDisplay 统一接管（含手势）。
                // 因此 `onCreate` 里那段只看 showGuide 的 OnBackPressedCallback 已删除。
                onBack = { backStack.removeLastOrNull() },
            ) {
                entry<BhRoute.Main> { MainEntry(backdrop = backdrop) }
                entry<BhRoute.Guide> {
                    GuidePage(onBack = { backStack.removeLastOrNull() })
                }
            }
        }
    }

    /**
     * 主界面（路由根）：Scaffold + 玻璃顶栏 + 三 Tab Pager + 悬浮玻璃底栏 + 全部弹窗。
     *
     * 2026-11-21 从原 `Root()` 拆出：内容一字未改（除骨架从 Box+Column 换成 Scaffold、
     * 顶栏换 `GlassTopAppBar`、底栏换 `GlassNavigationBar`），只是把「谁拥有路由栈」
     * 上交给了 [Root]。
     */
    @Composable
    private fun MainEntry(backdrop: LayerBackdrop) {
        // Step 2K（2026-11-10 用户拍板「换官方悬浮栏」）：三 Tab 由「tab 状态 + when」
        // 改为官方 `HorizontalPager` —— 支持左右滑切页；点击切页走 miuix 官方
        // `springAnimateToPage`（弹簧动画），顶栏标题与底栏选中态都直接读
        // `pagerState.currentPage`（Compose State 自动重组，天然双向同步）。
        val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
        val pageScope = rememberCoroutineScope()
        // 【2026-11-21 底栏选中索引：自己维护，解决「跨页抽搐」又不破坏渲染】
        //
        // 两个都不能直接用：
        //  · `currentPage`：跨页点击（0→2）时 spring 动画会经过 1，序列 0→1→2，
        //    底栏内部 `LaunchedEffect(selectedIndex)` 每次都重起动画 → 指示器抽搐。
        //  · `targetPage`：**会让 `IosLiquidGlassNavigationBar` 渲染异常**
        //    （实测胶囊圆角与左右边距全部丢失、变贴边扁平白条，截图 final-targetpage）。
        // 故用「点击即锁定 + 手势滑动跟随」的自维护索引：
        //  · 点击底栏 → 立即写入目标索引（球一次到位，不被中间页打断）；
        //  · 手势滑动 / 动画结束 → 跟随 currentPage 校正。
        var barIndex by remember { mutableIntStateOf(pagerState.currentPage) }
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            // 只在「不在滚动中」时才采纳 currentPage，避免动画途中的中间页覆盖点击目标。
            if (!pagerState.isScrollInProgress) barIndex = pagerState.currentPage
        }
        // Step 1 骨架：0.9.4 原生折叠大标题滚动行为（skill usage-patterns.md:72）。
        val scrollBehavior = MiuixScrollBehavior()
        // 三个 Tab 标题（Step 2G：第三个 Tab 由「授权管理」更名为「设置」；
        //  Step 2L 2026-11-10 用户拍板：大标题「自动上电」→「首页」、「事件日志」→「日志」，
        //  与底栏标签一致）。
        // 【2026-11-21 修正：索引改用 `targetPage`，不用 `currentPage`】
        //
        // 用户反馈「从首页点设置页时，液态玻璃的选择球在移动过程中会抽搐」。
        // 根因：`currentPage` 是**当前可见页**，跨页切换（0→2）时 Pager 的 spring 动画
        // 会依次经过 1，于是 `currentPage` 变化序列是 0 → 1 → 2。
        // 而指示器（`IosLiquidGlassNavigationBar` 内部）用 `LaunchedEffect(selectedIndex)`
        // 驱动 `animateToValue(...)`，**每次变化都会重新起一轮动画** →
        // 球先奔向 1、半路又被改成 2 → 表现为抽搐。
        // `targetPage` 是「动画正在趋向的目标页」，在 spring 启动瞬间即确定为 2，
        // 过程中不变 ⇒ 指示器只起一次动画，平滑直达。
        // 顶栏标题同步改用它，否则跨页时标题会先闪一下中间页的名字。
        val appBarTitle = when (pagerState.targetPage) {
            0 -> "首页"
            1 -> "日志"
            else -> "设置"
        }
        // Step 2D 问题 1：系统状态栏 / 导航栏**图标明暗**运行时兜底（详见同文件
        // SystemBarIconAppearance 的注释）。主题侧（values/ + values-night/）已显式声明
        // windowLightStatusBar / windowLightNavigationBar，这里再在运行时写一次
        // APPEARANCE_LIGHT_* 位，保证 API 35+ 强制 edge-to-edge 下的最终态正确。
        // 明暗取值与页面底色、miuix 主题**同源**：isDarkMode() = isSystemInDarkTheme()。
        SystemBarIconAppearance()
        // 日志刷新循环已上移到 `onCreate`（`repeatOnLifecycle(RESUMED)`，R-01 修复）。
        // 原来是这里的 `LaunchedEffect(Unit){ while(true){…} }`：Composable 不等同于
        // Activity 生命周期，切到后台后循环不会停 —— 详见 onCreate 里的注释。
        // 2026-11-21（feature/miuix-0.9.5b-glass）：页面骨架改为 miuix 官方 `Scaffold`。
        //
        // 【为什么从「Box + Column 手搭」改成 Scaffold】（用户 2026-11-21 拍板）
        //  · `GlassDialog` / `GlassDropdownPreference` 官方硬性要求 Scaffold 提供 MiuixPopupHost。
        //    `docs/components/glass/glassdialog.md` 头部元数据实测：
        //      requiresScaffoldHost: true / "Must be used within `Scaffold` to provide
        //      `MiuixPopupHost`" / "Using outside `Scaffold` will cause popup content not to render"。
        //  · 此前 Overlay 系控件在本页「点开不画 / 点开就崩」的根因就是缺这个宿主
        //    （本项目实测记录：23:29~23:34，见 `NfcCardPicker.kt` 头注释），
        //    现在用官方结构解决，不再靠「自绘弹窗」绕行。
        //
        // 【录制与玻璃的分工】官方 `docs/guide/glass.md` §Background Sampling 实证口径：
        //  `layerBackdrop` 只录制**挂载它的那个 composable 的内容**，不含父级背景；
        //  录制区里必须有不透明背景、且必须把玻璃面本身排除，否则「玻璃采样到自己」→ 发灰拖影。
        //  因此：内容区 + 页面底色进录制区；顶栏、底栏、弹窗全部在录制区外。
        // 【2026-11-21 结构对齐官方：底栏放在 Scaffold **之外**】
        // 官方 AppContent 是 `Box { Scaffold(...); 底栏 }` —— 底栏与 Scaffold 同级。
        // 之前我把底栏放在 Scaffold 的 content 里，会被 content 槽的裁剪/约束影响：
        // 实测表现为**胶囊圆角与左右 24dp 边距全部丢失**（变成贴边扁平白条，截图 perf-*）。
        // 弹窗仍留在 content 内（MiuixPopupHost 由 Scaffold 提供，不能移出）。
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = pageBackgroundColor(),
            topBar = {
                // 【2026-11-21 按官方示例 uitest 的实现对齐（用户要求「直接抄官方」）】
                //
                // 对照源码：example/shared/.../utils/PageUtils.kt 的 AdaptiveTopAppBar →
                //   `isContentScrolled = isContentScrolled ?: (scrollBehavior.state.contentOffset < -1f)`
                //   `bandBrush = GlassTopAppBarDefaults.bandBrush(surfaceColor)`
                //   `progressiveBlur = appState.enableBlur`
                //
                // ⚠️ 我之前写的是 `contentOffset > 0.5f` —— **方向反了**。
                // muiix 的 TopAppBarState.contentOffset 在内容向上滚动后是**负值**，
                // `> 0.5f` 永远不成立 ⇒ band 一直按「未滚动」处理 ⇒ 顶栏只有底色没有模糊带。
                // 现按官方写法改回 `< -1f`。
                //
                // `bandBrush` 也必须显式给：官方传的是 `bandBrush(surfaceColor)`，
                // 这是那层从页面色渐隐到透明的带子；不传会落到别的默认值，观感不一致。
                val surfaceColor = pageBackgroundColor()
                GlassTopAppBar(
                    title = appBarTitle,
                    isContentScrolled = scrollBehavior.state.contentOffset < -1f,
                    modifier = Modifier.fillMaxWidth(),
                    expandable = true,
                    progressiveBlur = true,
                    backdrop = backdrop,
                    bandBrush = GlassTopAppBarDefaults.bandBrush(surfaceColor),
                    largeTitle = appBarTitle,
                    subtitle = "",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {},
                    actions = {},
                )
            },
        ) { padding ->
            // 外层 Box：`了解本APP` 二级页与弹窗都与录制区**同级**（不参与录制）。
            Box(Modifier.fillMaxSize()) {
                // 录制区：只录「页面内容 + 不透明页面底色」。
                // nestedScroll 挂在录制区最外层 —— 滚动 delta 上抛给顶栏折叠，
                // 同时 `layerBackdrop` 录到的是实时滚动内容（玻璃顶栏才看得到内容穿透）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .layerBackdrop(backdrop)
                        .background(pageBackgroundColor()),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // Step 2P：`verticalAlignment = Alignment.Top` —— Compose `HorizontalPager`
                        // 默认是 `CenterVertically`，内容不足一屏时整页被**垂直居中**。
                        // 显式改 Top 后内容恒贴顶（真机实证过：日志页无事件时小标题会跑到屏幕中间）。
                        verticalAlignment = Alignment.Top,
                        // 三页常驻：切回时保留各页 LazyColumn 的滚动位置，滑动也更顺。
                        beyondViewportPageCount = 2,
                    ) { page ->
                        // topInset（2026-11-21 新增）：内容**延伸到顶栏下方**，首项从顶栏底面开始。
                        // 这样滚动时内容会从顶栏后面穿过，玻璃顶栏才采得到真实内容；
                        // 若像旧版那样把内容排在顶栏下方（不重叠），玻璃采样到的只有纯页面底色。
                        // 用动态 padding 不会造成可见跳动：折叠时用户已滚离首项。
                        val topInset = padding.calculateTopPadding()
                        when (page) {
                            0 -> HomeTab(topInset = topInset)
                            1 -> LogTab(topInset = topInset)
                            else -> SettingsTab(topInset = topInset)
                        }
                    }
                }
                // 悬浮玻璃底栏（iOS-LIKE 胶囊，用户 2026-11-21 指定「悬浮底栏 + IOS-LIKE」）。
                //
                // 位置：与录制区**同级**（不在录制 Box 内）—— 底栏自身也是玻璃面，
                // 若放在录制区里就是「玻璃采样到自己」，官方 guide/glass.md 明确禁止。
                // 悬浮 ⇒ 不占布局行 ⇒ 各 Tab 底部留白要自己给（见 topInset 同级的 bottomInset）。
                // `defaultWindowInsetsPadding` 默认 true，底部手势条 insets 由组件自行消费，
                // 这里只补 12dp 让它真正「浮」起来。
                // 【2026-11-21 第四次修正：换成官方 iOS-like 的真正实现】
                //
                // 前情：uiitest 设置里 `FloatingNavigationBar Style` 有三档
                // Original / Glass / iOS-like。反编译 + 抓官方源码后确认：
                //   style=0 → `FloatingNavigationBar`（普通悬浮栏）
                //   style=1 → `GlassNavigationBar`（miuix-glass 玻璃栏）← 我前面一直在用这个
                //   style=2 → **example 自定义组件 `IosLiquidGlassNavigationBar`**
                //             （源码 `example/shared/.../component/liquid/LiquidGlassNavigationBar.kt`，
                //              改编自 Kyant0/AndroidLiquidGlass，Apache 2.0）
                //
                // 用户反馈「模糊和点击动效也不一样」，正是因为它不是 miuix-glass 的 GlassNavigationBar：
                //  · 模糊：`drawBackdrop` + `lens()`（24dp 真折射，带 chromaticAberration 色散）+ `vibrancy()`
                //          + 双层 backdrop 合成（面板层 + 指示器层）
                //  · 动效：`DampedDragAnimation` 阻尼拖拽（按压放大 78/56、跟手切换、橡皮筋回弹）
                //          + `InteractiveHighlight` 跟随按压位置的高光
                //          + 指示器跟随手指平移，松手 spring 吸附
                // 因此把整套 7 个文件移植进 `com.bydhooker.autostart.ui.liquid`（保留 Apache 2.0 头），
                // 这里直接调用，不再用 GlassNavigationBar。
                // 弹窗必须在 Scaffold **内部**：MiuixPopupHost 由 Scaffold 提供，
                // 放到 Scaffold 之外会「点开不画」（glassdialog.md 硬性要求）。
                // 2026-11-09 的修复保留：这些弹窗此前「只定义未挂载」，点按钮毫无反应。
                ClearCredentialDialog()
                ClearLogDialog()
                // Step 2G：授权弹窗（未授权 → 二维码流程；已授权 → 清除凭据 / 重新授权）。
                AuthDialog()
                // 2026-11-10：设置页「支持开发」收款码弹窗。
                DonateDialog()
                // 2026-11-21：NFC 触发卡已改为设置页内的**原生玻璃下拉**
                // （`GlassDropdownPreference`，见 NfcCardPicker.kt），
                // 原先「点开弹一个自绘单选弹窗」的 `NfcCardPickerDialog` 已删除。
            }
        }

        // 底栏：与 Scaffold 同级（官方结构），避免被 content 槽裁剪。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            IosLiquidGlassNavigationBar(
                    items = navItems,
                    // 自维护索引：点击即锁定目标，滑动时跟随，避免跨页抽搐（见 MainEntry 顶部注释）。
                    selectedIndex = barIndex,
                    onItemClick = { index ->
                        barIndex = index
                        pageScope.launch { pagerState.springAnimateToPage(index) }
                    },
            backdrop = backdrop,
            // 【2026-11-21 回退记录】曾尝试首帧 false、第二帧再 true 以避开 shader 编译，
            // 但实测：`IosLiquidGlassNavigationBar` 的**非玻璃分支**在本项目下布局不正常
            // （胶囊变扁平白条、指示器消失、底部多出一条蓝色横带，截图 perf-scrolled）。
            // release 冷启动也没有可测量收益（83~113ms vs 基线 89~129ms，在噪声内）。
            // 结论：**保持恒 true**，不要再用这个开关做"优化"。
            isBlurActive = isRuntimeShaderSupported(),
            // 组件内部自己处理左右 24dp 与底部 insets/悬浮边距；
            // 官方示例不传 modifier（modifier 默认 Modifier），这里对齐用外层 Box 承担。
        )
        }
        }
    }

    /**
     * 底栏三项（`18` §2 定稿顺序：首页 / 日志 / 设置）。
     *
     * 2026-11-21：从 `NavigationBarItem` 改为 `GlassNavigationItem`。
     * 字段（javap 实证 0.9.5-beta02）：`(icon: ImageVector, label: String?, contentDescription: String?, badge: @Composable (() -> Unit)?)`。
     * `contentDescription` 传与 label 相同 —— 官方 doc 要求 icon-only 项必须给，
     * 本工程有文字也用同一文案，读屏不会读出「首页 首页」以外的杂音。
     */
    private val navItems = listOf(
        NavigationItem(label = "首页", icon = BhIcons.home),
        NavigationItem(label = "日志", icon = BhIcons.log),
        // 官方 miuix-icons 扩展集齿轮（Regular 字重）。
        NavigationItem(label = "设置", icon = MiuixIcons.Settings),
    )

    /**
     * Step 2D 问题 1：系统状态栏 / 导航栏**图标明暗**（运行时兜底）。
     *
     * 根因链（均已实证，非推测）：
     *  ① miuix **不参与** system bar —— 0.9.4 `MiuixThemeKt` 字节码中
     *     statusBar / SystemBar / appearance / InsetsController **零命中**
     *     → 图标明暗原本完全由 Android 主题决定。
     *  ② `android:Theme.Material.Light` 本身**不带** `windowLightStatusBar`
     *     （该属性只存在于变体 `Theme.Material.Light.LightStatusBar`，见本机 SDK
     *     `platforms/android-37.0/data/res/values/themes_material.xml:815-817`），
     *     `Theme.Material` / `Theme.Material.Light.NoActionBar` 同样不带。
     *  ③ targetSdk 35 起强制 edge-to-edge，`statusBarColor` 被忽略 →
     *     状态栏区域由我们的浅色页面底（surface #F7F7F7）填充，
     *     而图标仍按「深色背景」假设保持白色 → 浅色模式下白图标看不见（用户原话：
     *     「浅色模式下系统状态栏不会反色成黑色了」）。
     *
     * 修法**两条都走**（互为兜底、写的都是同一个 APPEARANCE_LIGHT_* 位，不冲突）：
     *  · 主题声明：values/themes.xml（浅色 true）+ values-night/themes.xml（深色 false）
     *    —— 平台在装饰阶段即生效，覆盖 API 26-29 等无 WindowInsetsController 的路径；
     *  · 本函数（运行时）：直接写 `WindowInsetsController` 的 appearance 位
     *    —— 覆盖 API 35+ edge-to-edge 下由运行时控制器决定明暗的情况，
     *    并在系统深浅色切换后重新写入，与页面底色保持同步。
     *
     * API 核验（androidx.core 1.16.0 AAR 字节码实证）：
     *  · `WindowCompat.getInsetsController(Window, View)` 存在；
     *  · `WindowInsetsControllerCompat.setAppearanceLightStatusBars(boolean)` /
     *    `setAppearanceLightNavigationBars(boolean)` 存在；
     *  · 实现按 API 分派：API≥35 → `Impl35`、API≥30 → `Impl30`
     *    （直连平台 `WindowInsetsController.setSystemBarsAppearance(int,int)`）、
     *    API 26-29 → `Impl26`（走 `View.SYSTEM_UI_FLAG_LIGHT_*` 旧路径）。
     *
     * 本函数**只写图标明暗位**：不改 statusBarColor / 导航栏底色、
     * 不改页面底色与卡片配色（`23` §13.2 已裁定：页面底色不得改回 `background`）。
     */
    @Composable
    private fun SystemBarIconAppearance() {
        val dark = isDarkMode()
        LaunchedEffect(dark) {
            try {
                val decor = window.decorView
                val controller = WindowCompat.getInsetsController(window, decor)
                // 深色底 → 浅色图标（false）；浅色底 → 深色图标（true）。
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            } catch (_: Throwable) {
                // 个别 ROM 上系统栏控制器不可用时忽略：主题声明那一条路仍然生效。
            }
        }
    }

    /**
     * 【2026-11-21 已删除】原「miuix 官方贴底导航栏」`BottomBar`（Step 2L 实现）。
     *
     * 删除理由：用户 2026-11-21 明确要求「底栏采用悬浮底栏，style 为 IOS-LIKE」，
     * 底栏已改为 `GlassNavigationBar`（miuix-glass，悬浮胶囊 + 玻璃材质），
     * 该函数零调用点。按本项目约定（见 `BhWidgets.kt` 对 `KeyValueRow` 的处理）
     * **不保留死代码**，故整段移除，不在本文件留注释掉的残骸。
     */
}


// BYDHooker 正式模块 · 单 APK 构建脚本（T01 骨架）
//
// 版本全部来自 gradle/libs.versions.toml；AGP / Kotlin / Compose 插件版本为
// <version> 占位【不知道】—— 本轮不执行 assemble，待回拍板锁定后再跑。
// 仅声明依赖与打包配置，不含任何业务 / UI 源码（P1 六项闸门未全绿，01 §6）。
plugins {
    alias(libs.plugins.androidApplication)
    // KGP 2.4.21：android.builtInKotlin=false 时恢复（见 gradle.properties 注记），
    // 由 KGP 负责 Java↔Kotlin 混编 classpath（内置 Kotlin 混编断的替代路径）
    alias(libs.plugins.kotlinAndroid)
    // Compose Compiler 插件（Kotlin 2.x 起 compose 编译器随 Kotlin 插件分发，id 已核实：
    // org.jetbrains.kotlin.plugin.compose，版本与 Kotlin 对齐 2.4.21）
    alias(libs.plugins.kotlinCompose)
    // kotlinx.serialization 编译器插件：miuix-nav 的路由 key 必须 @Serializable
    // （官方 muiix-nav.md 硬性要求），否则 rememberNavBackStack 无法持久化路由栈。
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.bydhooker.autostart"
    // compileSdk 锁定 37（本地 android-37.0 平台存在；miuix→androidx.core 1.16.0 要求 ≥35，满足）；
    // minSdk 26 / targetSdk 35 按设备（Android 17 / API 37）与模块需求设定
    //
    // 【2026-11-21 升到 37.2】miuix 0.9.5-beta02 的 AAR metadata 实读要求：
    //   "requires libraries and applications that depend on it to compile against
    //    version 37.2 or later of the Android APIs"（13 个 miuix 构件全部如此）。
    // 项目原为 37.0，直接报 checkDebugAarMetadata 失败。AGP 9 支持 minor API level，
    // 用 compileSdkMinor 表达 37.2（不是把 compileSdk 写成 37.2 这种非法写法）。
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        // 2026-11-10 20:4x（用户拍板）：包名 com.bydhooker.module → com.bydhooker.autostart，
        // 应用名 BYDHooker → BYDAutoStart，版本 0.9.x → 1.0.0。
        // **注意**：包名变更后系统视为全新应用 —— 远程配置（Remote Preferences 按包名归属）
        // 回到默认值；但授权凭据存在**官方 App 私有目录** files/bydhooker/ 下（用户拍板不改目录），
        // 因此凭据仍然有效，无需重新扫码。
        applicationId = "com.bydhooker.autostart"
        minSdk = 26
        targetSdk = 35
        // 版本管理（2026-11-09 起）：每次发布递增 versionCode / versionName，
        // 同步更新仓库根目录 CHANGELOG.md 并打 git tag（vX.Y.Z）。
        // 1.0.0 起 versionCode 与版本名对齐：1.0.0 → 100（1.0.1 → 101、1.1.0 → 110）。
        // 2.0.0：NFC 解锁自动上电（方案 B）。versionCode 与版本名对齐规则：
        // 1.0.0 → 100（1.0.1 → 101、1.1.0 → 110）⇒ 2.0.0 → 200。
        // 之后统一按「每次发布 +1」递增（实测：3.1.6 → 266、3.1.7 → 267），
        // 故 3.2.0 → **268**。
        // 3.2.0（2026-11-21）：miuix 0.9.5-beta02 + 全局玻璃 UI（详见 CHANGELOG）。
        // 3.2.1（2026-11-21）：底栏指示器滑动跟手 + 「了解本APP」对齐官方 About 页。
        // 3.2.2（2026-11-21）：指示器同步改用官方 MainPagerState 机制（3.2.1 那版无效）。
        // 3.2.3（2026-11-21）：修底部弹窗被悬浮底栏遮挡（绘制层级）。
        versionCode = 271
        versionName = "3.2.3"
    }

    // API 102 入口声明文件放在 src/main/resources/META-INF/xposed/，
    // Gradle 自动打包成 APK 内 META-INF/xposed/{java_init.list, scope.list, module.prop}
    sourceSets {
        getByName("main") {
            resources.srcDirs("src/main/resources")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildFeatures {
        // T04：miuix/Compose UI（AGP 侧开关；编译器由 kotlinCompose 插件负责）
        compose = true
        // 2026-11-09（Step 2G）：授权状态卡左下角要显示当前模块版本号。
        // AGP 8.0 起 buildConfig 默认关闭 → 需显式开启才能用 BuildConfig.VERSION_NAME。
        buildConfig = true
    }

    buildTypes {
        release {
            // 体积优化（2026-11-09）：debug 包 10.93 MB 中 **94% 是 classes*.dex**，
            // 根因是 R8 未启用（Compose + miuix + androidx 全量进包、11 个 dex 未裁剪）。
            // 开启 R8 + 资源裁剪后预计降到 3~4 MB。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
            // 临时：release 用 debug 签名，便于直接装机验证（正式发布须换正式 keystore）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // 2026-11-21：17 → 21。miuix-nav 0.9.5-beta02 的 class 文件是 JVM target 21，
        // kotlinc 无法把 target-21 的 inline 字节码内联进 target-17 产物
        // （报错原文：Cannot inline bytecode built with JVM target 21 into bytecode
        //  that is being built with JVM target 17）。AGP 9.4 + D8 支持 21。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Kotlin JVM target 与 compileOptions 对齐（JVM target 校验：javac 与 kotlinc 必须一致）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    // libxposed api：compileOnly（框架运行时提供，不打进 APK）（15 §3.0.1）
    compileOnly(libs.libxposed.api)

    // libxposed service：裁定 H = 102.0.0（存在性复核 HTTP 200；06 §9.2）。
    // 设置页（架构 A）经 XposedServiceHelper 写 remote prefs → 宿主 Listener 实时收。
    // 102.0.0 上游缺陷：AAR 不含 AIDL 生成类（IXposedService$Stub 等）→ 由
    // app/src/main/java/io/github/libxposed/service/IXposedService.java 精简 Stub 补齐
    // （来源 P1 探针实测版本，事务号与 LSPosed 2.2.1 一致）。
    implementation(libs.libxposed.service)

    // Activity 宿主（设置页 ComponentActivity；1.7.0 本地缓存已有）
    implementation(libs.androidx.activity.ktx)

    // 二维码编码（设置页扫码授权出码；用户拍板 2026-11-08）
    implementation(libs.zxing.core)

    // miuix 0.9.5-beta02（2026-11-21 升级，用户要求「适配最新 beta」）
    implementation(libs.miuix.ui.android)
    implementation(libs.miuix.preference.android)

    // 2026-11-09（Step 2G）：官方扩展图标集 —— 底栏「设置」Tab 改用官方 Settings 齿轮。
    implementation(libs.miuix.icons.android)

    // 2026-11-21（feature/miuix-0.9.5b-glass）：OS4 玻璃材质组件集。
    // 「全局 glass」「悬浮底栏 IOS-LIKE」「原生 dialog」「原生下拉」全部走这个模块，
    // 不自绘玻璃（用户原话「不要自行搭，全部用 miuix 的」）。
    // 传递引入 miuix-blur（minSdk 33）+ miuix-material + miuix-ui。
    implementation(libs.miuix.glass.android)
    // 玻璃风格图标集（官方 example 的玻璃返回箭头 MiuixIcons.Glass.ChevronBackward）。
    implementation(libs.miuix.glass.icons.android)
    // 「了解本APP」二级页改用 miuix-nav 真页面栈（用户 2026-11-21 拍板）。
    implementation(libs.miuix.nav.android)
    // 路由栈序列化（@Serializable 注解编译期可见；版本随 miuix-nav 的 pom 实读值 1.13.0）。
    implementation(libs.kotlinx.serialization.json)
    // 显式注入 NavigationEventDispatcherOwner（R8 会破坏 miuix 的自动注入，见 SettingsActivity 注释）。
    implementation(libs.navigationevent.compose)

    // 图标 / nav / squircle 等可选组件：坐标与版本【不知道·按需，不编造】（15 §3.0.2）
    // miuix-blur 需 minSdk 33（设备 SDK 37 满足）【官方文档】
}

// 源文件统一 UTF-8：javac 默认按系统编码（Windows=GBK）读取，
// 含中文/全角标点的 .java 会报「非法字符」（2026-11-09 ReceiptHooker 命中）。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

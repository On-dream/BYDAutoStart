// BYDHooker 正式模块 · 根构建脚本
//
// 插件版本全部来自 gradle/libs.versions.toml。AGP=9.4.0 / Kotlin=2.4.21 已锁定（2026-11-08 复核）。
// T04：启用 Compose Compiler 插件（org.jetbrains.kotlin.plugin.compose，与 Kotlin 对齐 2.4.21）。
// 插件声明用 apply false，实际应用到 :app（见 app/build.gradle.kts）。
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
}

// 说明（非代码）：
//  - 正式模块必须 Gradle + Kotlin + Compose（miuix 是 Compose Multiplatform），
//    research/P1前置验证/p1probe/build.py 的 javac/d8 链只适用于 P1 纯 Java 探针（01 §8.2 #5）。
//  - 本轮只建骨架，不写 core/ ui/ crypto/ 业务源码 —— P1 六项闸门未全绿（01 §6）。

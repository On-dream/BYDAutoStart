// BYDHooker 正式模块 · Gradle 工程设置（T01 骨架，仅配置，不含业务/UI 源码）
// 版本一律来自 gradle/libs.versions.toml；未核实版本为 <version> 占位【不知道】
// 本骨架本轮不执行 assemble（占位版本无法解析），待回拍板锁定后再跑。
pluginManagement {
    repositories {
        // 仓库地址按实际可达配置【不知道·未验证可达性】，此处只用官方标准地址
        google()
        // 2026-11-09（Step 2G）：Maven Central 直连本机返回 **403**
        // （"This IP has been blocked for excessive or automated consumption of Maven Central"，
        //  curl 实测 central.sonatype.com 原文）→ 新增依赖无法拉取。
        // 故把阿里云镜像放在 mavenCentral() **之前**（Gradle 按声明顺序取第一个命中）。
        // 【已核实】镜像对本工程全部现有依赖 + 新增 miuix-icons 均为 HTTP 200。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "BYDHooker"
include(":app")

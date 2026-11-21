# BYDHooker 正式模块 · R8 / 混淆规则
# 来源：15-程序设计.md V1.1 §3.0.1（【官方文档】libxposed/api README 原文）
# 硬要求：以下三条**逐字照抄，不改一个字符**，保证 java_init.list 在混淆后被正确改写。

-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }

# ---- 体积优化补充规则（2026-11-09）----

# JNI 符号绑定：libwbsk_crypto_tool.so 导出
# Java_com_wbsk_CryptoTool_* —— 类名/方法名被 R8 重命名会导致
# UnsatisfiedLinkError，必须整体保留（Whitebox 直接静态调用该类的 native 方法）。
-keep class com.wbsk.CryptoTool { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 桥 / 凭据 / 加密表：运行期按固定类名与常量形态使用，保守保留
-keep class com.bydhooker.autostart.crypto.WbKeys { *; }
-keep class com.bydhooker.autostart.crypto.WbTables { *; }

# ---- 架构 C 执行层（2026-11-13 15:29 实测后新增）----
#
# system_server 的 classloader 里**没有**本 APK 的类（实测 DIRECT|ok=0/4，
# LSPosed 只注入入口链）。第一期改用 DexClassLoader 指向 base.apk 的 dex 来加载，
# 此时类能找到（实测 DEX_OK），但入口方法是**反射调用**的 ——
# R8 把方法名混淆掉就会 NoSuchMethodException（实测 `gs.info` 找不到）。
#
# 所以 exec 包整体保留类名与方法名：TripleKeyHook 用反射调它的入口方法。
-keep class com.bydhooker.autostart.exec.** { *; }
# 执行层入口的宿主侧反射点（保持签名与名字）
-keepclassmembers class com.bydhooker.autostart.exec.InjectableExec {
    public *;
}

# ---- 超级岛展示链（3.1.0，docs/37）----
# 岛事件由 DexClassLoader 加载的 InjectableExec 间接引用，且 SystemUI 侧
# 的 Hooker 由入口类按类名装配；保守保留类名/方法名，避免 R8 混淆后
# 反射找不到 attachContext / 事件协议字段。
-keep class com.bydhooker.autostart.island.** { *; }
-keep class com.bydhooker.autostart.hook.systemui.** { *; }


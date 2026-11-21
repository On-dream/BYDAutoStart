# BYDAutoStart

比亚迪「解锁 → 自动上电」的 LSPosed 模块。**不需要额外常驻后台 App、不需要保活进程**。

> 非官方项目，与比亚迪、小米均无关联。修改车辆控制行为有风险，请先读完[风险](#风险与已知限制)。

## 触发方式

| 触发源 | 实现位置 | 说明 |
|---|---|---|
| **三击电源键** | `system_server` | Hook HyperOS 的多击计数，把「三击」映射为「解锁 + 按模式上电」 |
| **官方 App 解锁** | 官方 App 进程 | Hook 车控指令收口，捕获解锁指令 |
| **NFC 刷卡** | 小米智能卡进程 | 监听车钥匙卡刷卡事件，按选定的卡匹配 |


## 两种上电模式

| 模式 | 触发条件 | 适用 |
|---|---|---|
| **开门上电**（默认，`M1`） | 解锁后窗口期内**拉开主驾门** | 
| **解锁上电**（`M2`） | 捕获解锁指令后**立即下发** | 更快，但解锁取个东西也会启动 |


## 环境要求

- 小米 / HyperOS 手机（实测：小米 17 Pro，HyperOS 4.0，Android 17 / API 37）
- **root + LSPosed**（libxposed API 102）
- 官方 App `com.byd.aeri.caranywhere` 已安装并登录
- 小米智能卡 `com.miui.tsmclient`（NFC 触发需要）
- ABI：arm64-v8a

## Need help

我手上只有小米手机，所以这个模块**只适配并在小米设备上验证过**，
其他品牌（一加、OPPO、vivo、原生 Android 等）没有测试条件，也不敢保证能跑。

需要说明的是，下面三块都贴着小米系统的实现走，换品牌基本要重做：

| 功能 | 依赖的系统钩子 |
|---|---|
| 三击电源键 | HyperOS 的多击计数（`getMiuiMaxMultiPressCount` / `triggerTripleClick`） |
| NFC 刷卡 | 小米智能卡 `com.miui.tsmclient` 的刷卡事件与 `CardInfoManager` |
| 状态提示 | 小米超级岛 |

**欢迎有能力的开发者把它适配到别的机型**。提 PR 就行（`main` 受保护，需要走 Pull Request）。
提交时请附上这些信息，能省很多来回：

- 机型、系统版本、Android API level、LSPosed 版本
- 目标 Hook 点的类名/方法名（反编译结果或实测日志）
- 失败时的 logcat / 模块日志片段

同样欢迎提供**其他车型**的云端字段口径（`powerGear`、`leftFrontDoor`、`doorLock` 的取值含义），
车型适配往往只是判据不同，逻辑不用重写。

## 构建

需要 JDK 21 或更高（`miuix-nav` 的 class 目标字节码是 21），Android SDK 含 **API 37.2** 平台。

```bash
git clone https://github.com/On-dream/BYDAutoStart.git
cd BYDAutoStart

# 指向本机 Android SDK（该文件不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

构建链：Gradle 9.7.0 + AGP 9.4.0 + Kotlin 2.4.21 + libxposed API 102 + miuix 0.9.5-beta02 + Compose。
依赖解析顺序为 `google()` → 阿里云镜像 → `mavenCentral()`（见 `settings.gradle.kts`）。

`assembleRelease` 默认沿用 debug 签名，方便装机自测；**自行分发请配置自己的 keystore**。

## 首次使用

1. 安装 APK，在 LSPosed 中启用模块。
2. 作用域勾选三项：比亚迪官方 App、小米智能卡，以及**系统（`system`）**——不是 `android`，填错会静默失效。
3. **重启手机**。`system_server` 里的 Hook 只在开机注入时生效，覆盖安装不会替换。
4. 打开官方 App，在模块内完成扫码授权（凭据只存在本机）。
5. 设置页选择触发方式、模式；NFC 触发需先选一张钥匙卡。

## 目录结构

```
app/src/main/kotlin/com/bydhooker/autostart/
├── core/     状态机、窗口、车况轮询、凭据库、指令下发
├── crypto/   内外层加密、白盒表、watch 请求构造
├── bridge/   跨进程契约与 Remote Preferences
├── data/     日志读取、root shell、导出
└── ui/       Miuix/Compose 界面（首页 / 设置 / 日志 / 授权）
app/src/main/java/com/bydhooker/autostart/
├── XposedModuleEntry.java   入口
├── hook/                    官方 App、system_server、tsmclient 三处 Hook
├── exec/                    在宿主进程就地执行所需的最小运行时
└── wake/                    ContentProvider 通道
app/src/main/assets/         白盒密钥表、自检样本、收款码
app/src/main/jniLibs/        白盒 JNI so（arm64-v8a）
```


## 许可证

[GPL-3.0](LICENSE)。`main` 分支受保护，禁止直接推送，所有改动走 Pull Request。

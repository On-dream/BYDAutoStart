# BYDAutoStart

比亚迪「解锁 → 自动上电」的 LSPosed 模块。取车时解锁车辆，模块在官方 App 已登录的前提下，
借官方 App 自己的云端通道下发一次上电指令，**不需要额外常驻后台 App、不需要保活进程**。

> 非官方项目，与比亚迪、小米均无关联。修改车辆控制行为有风险，请先读完[风险](#风险与已知限制)。

## 触发方式

| 触发源 | 实现位置 | 说明 |
|---|---|---|
| **三击电源键** | `system_server` | Hook HyperOS 的多击计数，把「三击」映射为「解锁 + 按模式上电」 |
| **官方 App 解锁** | 官方 App 进程 | Hook 车控指令收口，捕获解锁指令 |
| **NFC 刷卡** | 小米智能卡进程 | 监听车钥匙卡刷卡事件，按选定的卡匹配 |

三条触发都只做「检测」，真正的凭据、加密与下发都在模块进程内完成（通过 ContentProvider 跨进程调用）。

## 两种上电模式

| 模式 | 触发条件 | 适用 |
|---|---|---|
| **开门上电**（默认，`M1`） | 解锁后 60 s 窗口内**拉开主驾门** | 最稳，误触发概率低；实测「拉门 → 上电确认」约 2.7 s |
| **解锁上电**（`M2`） | 捕获解锁指令后**立即下发** | 更快，但解锁取个东西也会启动；冷车时上电较慢 |

> ⚠️ watch 通道**没有任何下电/熄火指令**。一旦误触发，只能上车物理断电。请确认接受这一点再使用 `M2`。

## 环境要求

- 小米 / HyperOS 手机（实测：小米 17 Pro，HyperOS 4.0，Android 17 / API 37）
- **root + LSPosed**（libxposed API 102）
- 官方 App `com.byd.aeri.caranywhere` 已安装并登录
- 小米智能卡 `com.miui.tsmclient`（NFC 触发需要）
- 车型：元 Pro 2023（其他车型未验证，云端字段口径可能不同）
- ABI：arm64-v8a

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

## 风险与已知限制

- 云端 `code=0` 只代表「受理」，真正的成功要靠车况字段复核（`powerGear == 3` 且数据龄 ≤ 30 s）。
- 上电后约 37 分钟 `powerGear` 会从 3 回落到 1，去重不能用一次性布尔量。
- NFC 判据基于产品级 AID：同款两辆车无法区分，刷错卡也可能触发。
- 蓝牙钥匙、云控变体、车机侧 Hook 等路线已验证不可行，未包含在本仓库。
- 锁屏未解锁时部分系统日志不可读，相关功能会降级。

## 仓库里没有的东西

- 设计文档、逆向记录、实车日志（含 VIN / token）均不随仓库发布。
- `app/src/main/assets/byd_keymaterial.json` 是本机授权产生的真实车辆凭据，已在 `.gitignore` 中永久排除。
- 源码注释里可能残留内部文档编号（如 `docs/30`），对应文档未公开，不影响编译。

## 第三方材料

`app/src/main/assets/byd_wb_keys.json`、`byd_predata_tables.json` 与
`app/src/main/jniLibs/arm64-v8a/libwbsk_crypto_tool.so` 提取自第三方车控应用，
版权归原权利人，仅用于协议互通。详见 [NOTICE](NOTICE)。

## 许可证

[GPL-3.0](LICENSE)。`main` 分支受保护，禁止直接推送，所有改动走 Pull Request。

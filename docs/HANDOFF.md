# Marionette HomeKit 交接与排障手册

更新日期：2026-09-16

这份手册给接手项目的人使用。先看结论和日志证据，再动配对或安装状态；不要用“重装一次”代替定位。

## 当前已验证结论

- Xiaomi 13 真机可被 iPhone 家庭 App 发现、配对并完成 Pair Verify。
- HAP 加密控制读写可用，按钮名称、布尔状态、亮度、音量、电池电量和充电状态均已完成同步验证。
- 14 个 GATT 服务可以稳定发布；状态轮询在无 BLE 连接时仍运行，重新连接后可继续同步。
- 移动数据使用 Root 的 `settings` + `svc data` 双路径切换，并在写入后回读确认真实开关状态。
- 屏幕亮度以 Android 系统亮度档位（1–255）作为 HomeKit 百分比真值；Root 设备通过 `settings put system` 写入，背光节点只作为系统设置路径不可用时的最终回退，不能将小米背光原始值线性映射为 HomeKit 百分比。
- Activity 内的亮度/音量滑块已采用最新值合并、串行写入和短暂稳定窗口，避免定时状态刷新与用户拖动互相覆盖。
- 每个按钮现在由 `homekit/controls/HomeKitControl.kt` 的模型和生命周期统一驱动；HAP 只是适配层。

关键文件：

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/dev/local/mihotspot/homekit/controls/HomeKitControl.kt` | 所有按钮的模型、生命周期、状态快照和登记表 |
| `app/src/main/java/dev/local/mihotspot/core/homekit/protocol/HomeKitAccessoryCatalog.kt` | HAP 服务、特征、IID、名称、格式和模型绑定 |
| `app/src/main/java/dev/local/mihotspot/core/homekit/HomeKitRuntime.kt` | BLE/GATT、配对、加密 PDU、HAP 读写和事件同步 |
| `app/src/main/java/dev/local/mihotspot/homekit/commands/AndroidCommandExecutor.kt` | Android/Root 实际执行与真实状态读取 |
| `app/src/main/java/dev/local/mihotspot/domain/HomeKitFeature.kt` | 控制模型在 UI 中的投影 |

## 构建、安装和启动

在项目根目录执行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  '/Users/lin/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0/bin/gradle' \
  --offline --no-daemon :app:assembleDebug

adb devices
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <设备序列号> shell am force-stop dev.local.mihotspot
adb -s <设备序列号> shell am start -n dev.local.mihotspot/.MainActivity
```

`install -r` 可以直接安装升级包，并保留应用数据和 HomeKit 配对身份。普通升级不要使用 `pm clear`，不要卸载重装；只有明确要重新配对时，才在应用内清除配对或重置配件 ID。

KernelSU 集成使用：

```sh
./build-kernelsu-module.sh
```

生成的 `marionette-kernelsu-module.zip` 在 KernelSU Manager 中安装。调协议时优先安装 debug APK，确认状态稳定后再做模块包。

## 日志基线

读取运行日志：

```sh
adb -s <设备序列号> logcat -d -s MiHotspotHap:I '*:S'
```

启动成功至少应看到：

```text
SERVICE_CREATED
STATE_POLLING_STARTED intervalSeconds=10
GATT_DATABASE_PUBLISHED services=14
BLE_ADVERTISING_REQUESTED paired=true ...
BLE_ADVERTISING_STARTED ... connectable=true
```

一次完整控制同步的关键顺序通常是：

```text
BLE_CONNECTION ... CONNECTED
PAIR_VERIFY_M4 ...
CONTROL_SESSION_ACTIVE
HAP_EVENT_SUBSCRIBED ... iid=...
CONTROL_STATE_READ ...
HAP_EVENT_INDICATION_QUEUED ...
```

不要只看“已连接”。`PAIR_VERIFY_M4` 只证明加密会话建立；`HAP_EVENT_SUBSCRIBED` 才证明 Home 正在订阅状态事件；`CONTROL_STATE_READ` 才证明 Home 真正读到了对应值。

## 按日志定位问题

| 现象 | 优先检查 | 常见原因 |
| --- | --- | --- |
| 一直“正在连接” | `BLE_ADVERTISING_STARTED`、`BLE_CONNECTION`、`PAIR_VERIFY_M4` | 广播未启动、旧 GATT 状态未清理、蓝牙权限/系统电源管理阻断 |
| 能配对但按钮显示“不支持” | `HAP_PDU_REQUEST ... status=...`、Characteristic Signature、`HAP_EVENT_SUBSCRIBED` | HAP 属性、presentation format、IID、CCCD 或 ConfiguredName 不符合预期 |
| 电池正常，所有按钮没有值 | 是否有控制特征的 `CONTROL_STATE_READ` | Home 没接受控制特征；重点查 `0x01B0`、Int32 `0x10`、布尔值 1 字节和 Service Signature |
| 移动数据写入失败 | `COMMAND_EXECUTE command=MOBILE_DATA` 的 message、Root 状态、写入后的实际值 | 普通应用无 `MODIFY_PHONE_STATE`；Root 未授权、`svc data` 被厂商策略拦截，或设备没有可用数据 modem |
| Home 显示“灯/开关 1” | `SERVICE_NAME_READ` 中的 `0x23` 与 `0xE3` | legacy Name 或 ConfiguredName 缺失、类型/属性错误，或错误接受了 Home 生成的 ConfiguredName |
| Android 改了状态，Home 不变 | `CONTROL_STATE_CHANGED`、`HAP_GSN_INCREMENTED`、`HAP_EVENT_INDICATION_QUEUED` | 轮询被停止、真实状态读取错误、未订阅、GSN 未递增或 indication 未排队 |
| 主动更新后短暂更新又失败 | 同时检查 `HAP_GSN_INCREMENTED` 和广播刷新 | 只改内存状态，没有让广播携带新的 GSN；当前实现会在无连接时刷新广播 |
| 重新安装后无法配对 | `paired=`、`controller_key` 是否还在 | 使用了卸载、清数据或重置配件身份，而不是 `install -r` |

电池日志不能作为协议整体正常的证明：电池是只读 UInt8，控制按钮是可读写、可事件通知的另一组特征。历史上“只有电池正常”的根因就是 Home 拒绝/跳过了控制特征，不能通过继续改 Android 开关执行器解决。

## 接手开发时的硬规则

- 所有按钮先加到 `HomeKitControlModels`，再接入 UI 和 HAP；不要在三个层次分别复制名称、命令和状态判断。
- Runtime 通过 `HomeKitControlLifecycle` 读取和写入控制值；只有 `AndroidCommandExecutor` 接触 Android 系统副作用。
- HAP 读写/事件缓存必须按全局 IID 或 GATT characteristic 对象隔离，不能只按 UUID。多个 Lightbulb/Switch 服务会复用特征 UUID。
- 当前功能控制只有两种图标服务：4 个 Light Bulb 和 6 个 Switch；Home 不支持通过名称自定义图标。若迁移到 Switch、Speaker 或 Stateless Programmable Switch，必须按新服务的必需特征完整实现，并递增 CN、删除旧配对后验证。
- 主 Activity 的 NFC 前台分发必须声明 NFC 权限，并在未 armed 时不调用 `disableForegroundDispatch`；MIUI 缺少该保护会在 Activity 退后台时杀掉承载 HomeKit Service 的整个进程。
- 服务/IID 一旦发布不要随意改。新增移动数据服务后 CN 已从 `0x16` 提升到 `0x17`；后续改变服务数据库仍要递增 CN，并删除旧配对重新验证。只改执行器不需要改 CN。
- HAP 的 Int32 是 4 字节小端，不能因为某个值看起来像 0/1 就按 Bool 解码；电池 UInt8 才是 1 字节。
- `ConfiguredName` 的 `0x00B0` 和控制特征的 `0x01B0` 不可混用；前者用于可编辑名称，后者用于按钮状态和事件。
- 状态事件是“零长度 Indicate 后 Home 回读”，不是把业务值直接塞进 indication；必须确认 CCCD、订阅、GSN、回读四件事。
- 屏幕电源是按键动作，不是普通目标状态；亮度和音量的 `On` 与滑块写入必须共享一个模型生命周期。

## 最小回归流程

1. 编译并用 `install -r` 更新，确认服务启动和 14 个服务发布。
2. 在 Home 中打开配件详情，确认每个功能名称不是自动生成名称。
3. 逐个读取屏幕电源、亮度、热点、移动数据、静音、手电筒、GPS、低电量模式、免打扰和音量；同时确认日志有对应 `CONTROL_STATE_READ`。
4. 从 Android 设置或系统动作改变状态，等待一个轮询周期，确认 `CONTROL_STATE_CHANGED → HAP_GSN_INCREMENTED → HAP_EVENT_INDICATION_QUEUED`。
5. 从 Home 写入布尔值和亮度/音量滑块，确认 `CONTROL_COMMAND_RECEIVED → COMMAND_EXECUTE`，并在 Android 侧核对真实状态；亮度应看到 `screen-brightness=... (panel+settings)` 或明确的回退路径。
6. 断开、重连、重启 Home 再做一次全量读取；不要在这个过程中清除配对，除非是在验证全新配对流程。

如需重新配对，优先使用应用内“清除配对状态”，保留配件 ID；只有要模拟新配件时才重置配件 ID。协议参考以 [Apple HomeKitADK BLE 实现](https://github.com/apple/HomeKitADK/blob/master/HAP/HAPBLEAccessoryServer%2BAdvertising.c) 为准，真机日志以本手册的运行时证据为准。

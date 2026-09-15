# Marionette：交接与运行手册

更新日期：2026-09-15

## 当前可交接状态

项目已经在 Xiaomi 13 上完成以下真机验证：

- HomeKit BLE 配件可广播、被家庭 App 发现并完成配对。
- Pair Setup / Pair Verify 后可进入加密控制通道。
- 最近一次已安装 APK 的配对状态以广播中的 `paired=` 为准（排查时“重置配件 ID”会清空 pairing，需重新配对）。最新 MVVM 拆分 APK 已构建；设备重新连接后需再执行 `install -r`。
- 9 个 GATT 服务已成功发布，最后日志无崩溃、无 `BLE_ADVERTISING_FAILED`。
- 已实现屏幕电源键按钮、屏幕亮度、热点、媒体静音、手电筒、电池状态与 Android 通知。屏幕电源与亮度已拆分为两个独立 tile。

工作目录：`/Users/lin/Desktop/xiaomi13`  
真机序列号：`510af65c`  
包名：`dev.local.mihotspot`  
入口：`dev.local.mihotspot.MainActivity`

## 不要做的事

- 不要为普通升级或排障点击/调用“重置配件 ID”或“清除配对状态”。这会让 iPhone 端已有家庭配对失效。
- 不要删除应用数据、执行 `pm clear`，也不要卸载后以会清数据的方式重装。
- 不要只以 UUID 查找 HomeKit 特征定义。三个 Lightbulb 和两个 Switch 会因此发生服务串线；`gattDefinition` 按 GATT characteristic 对象反查，失败时退回按 IID 描述符（全局唯一）反查。
- 不要在未递增 CN/GSN 的情况下改变服务清单或 IID。Home 会缓存元数据。

## 关键源码

| 路径 | 负责内容 |
| --- | --- |
| `app/src/main/java/dev/local/mihotspot/MainActivity.kt` | View：原生 UI、运行时权限、启动/重启前台服务、渲染 ViewModel 状态 |
| `app/src/main/java/dev/local/mihotspot/ui/MainViewModel.kt` | ViewModel：UI 状态读取、用户命令、亮度控制、配对码校验与按钮文案 |
| `app/src/main/java/dev/local/mihotspot/HomeKitService.kt` | Android 前台 Service 入口；仅绑定 Manifest 与 core runtime |
| `app/src/main/java/dev/local/mihotspot/BootReceiver.kt` | 开机、解锁启动和应用更新后恢复 HomeKit 前台服务 |
| `app/src/main/java/dev/local/mihotspot/domain/HomeKitFeature.kt` | Model：功能开关的稳定语义、标题与状态文案 |
| `app/src/main/java/dev/local/mihotspot/data/AccessorySettingsRepository.kt` | Model data：配件 ID、配对和用户配置持久化 |
| `app/src/main/java/dev/local/mihotspot/core/homekit/HomeKitRuntime.kt` | Core 基础设施：BLE/GATT、HAP PDU、SRP、Pair Verify、加密会话和服务数据库 |
| `app/src/main/java/dev/local/mihotspot/homekit/commands/HomeKitCommand.kt` | 命令枚举及 HAP/Android 边界接口 |
| `app/src/main/java/dev/local/mihotspot/homekit/commands/AndroidCommandExecutor.kt` | Root 与 Android API 的实际手机控制 |
| `app/src/main/AndroidManifest.xml` | BLE、Wi-Fi、相机、通知等权限 |
| `docs/ARCHITECTURE.md` | 完整架构、服务 IID、状态与限制 |
| `vendor/HomeKitADK` | Apple 开源 ADK 参考实现；勿将它当成 Android 可直接编译组件 |

## 构建、安装、启动

Gradle 9.1 可与本机 Android Studio JBR 一起使用。以下命令均在项目根目录执行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  '/Users/lin/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0/bin/gradle' \
  --offline --no-daemon :app:assembleDebug

adb -s 510af65c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 510af65c shell pm grant dev.local.mihotspot android.permission.BLUETOOTH_ADVERTISE
adb -s 510af65c shell pm grant dev.local.mihotspot android.permission.BLUETOOTH_CONNECT
adb -s 510af65c shell am force-stop dev.local.mihotspot
adb -s 510af65c shell am start -n dev.local.mihotspot/.MainActivity
```

`install -r` 会保留 `SharedPreferences`，因此保留配对。打开 `MainActivity` 后会请求权限并启动 `HomeKitService`；BLE/GATT 不再由 Activity 承载，关闭 UI 不应停止配件。手电筒还需要应用内“授权手电筒相机权限”按钮；不能把 `CAMERA` 作为服务启动前置条件，否则用户拒绝相机权限会误阻断 BLE 配件。

最新 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 健康检查

启动后读取日志：

```sh
adb -s 510af65c logcat -d -s MiHotspotHap:I '*:S'
```

正常基线必须包含：

```text
GATT_DATABASE_PUBLISHED services=9
BLE_ADVERTISING_REQUESTED paired=true ...
BLE_ADVERTISING_STARTED ... connectable=true
```

含义：

- `paired=true`：Android 端还保存着 iPhone controller key，不代表该瞬间有活跃 BLE 连接。
- `BLE_CONNECTION ... CONNECTED` / `DISCONNECTED`：家庭 App 的真实 GATT 连接变化，前台服务通知据此更新；Activity 只展示配对状态与后台服务提示。
- `PAIR_SETUP_M6`：新的配对已写入 controller key。
- `CONTROL_SESSION_ACTIVE`：Pair Verify 后加密通道已建立。
- `CONTROL_COMMAND_RECEIVED` 与 `COMMAND_EXECUTE`：HomeKit 指令已到达及 Android 执行结果。
- `CONTROL_STATE_READ`：Home 正在读取热点、静音、亮度、电量等状态。

如需只看异常：

```sh
adb -s 510af65c logcat -d | rg -i 'FATAL EXCEPTION|AndroidRuntime|MiHotspotHap:.*(ERROR|FAILED|Exception)'
```

## iPhone/Home 测试顺序

1. 打开 Android app，确认“配对状态：已配对”与“连接状态：由 HomeKit 后台服务维护”，并确认系统前台通知显示 HomeKit 广播中。
2. 打开家庭 App 中已有的 Marionette 配件。当前 CN 为 8、GSN 为 6；服务清单若有更新，通常等待一次连接/刷新即可得到新服务。
3. 先测试热点、静音和手电筒；手电筒走 sysfs `led:torch_*`（无需相机权限，最可靠），相机权限只是无 Root 兜底。
4. 测试屏幕时注意“屏幕电源”与“屏幕亮度”是两个独立 tile：电源的每次操作都是一次电源键按下，不要将 tile 的 bool 外观误认为会强制目标状态；亮度 tile 的滑块与开关都控制背光。
5. 发生问题后，不要先重置；先导出上述 logcat，重点保留从 `BLE_CONNECTION` 到 `COMMAND_EXECUTE` 的连续日志。

## 常见故障定位

| 现象 | 先检查 | 处理方向 |
| --- | --- | --- |
| 家庭 App 看不到新服务 | 广播中的 CN/GSN、`GATT_DATABASE_PUBLISHED` | 保留配对，关闭/重开家庭 App 后再等一次连接；新增服务时递增 CN/GSN |
| 配对失败 | `PAIR_SETUP_PARSE`、M2/M4/M6、是否误清 controller key | 不要重置 ID；只在用户确认后清除配对状态并重新配对 |
| 已配对但控制无效 | `CONTROL_SESSION_ACTIVE`、`CONTROL_COMMAND_RECEIVED`、`COMMAND_EXECUTE` | 区分 HAP 收包失败、应用内开关禁用、Root/API 权限失败 |
| 有 `HAP_PDU_REQUEST` 但无 `COMMAND_EXECUTE` | 写入值字节长度与声明 format（Bool=1 字节，UInt32=4 字节小端） | 写分发必须按长度解码（`decodeWriteValue`），不要假设 1 字节；读取返回长度也要与声明一致 |
| 命令 success=true 但物理无效 | 把 executor 原语拿到 `adb shell su -c` 手动复现；sysfs 读回“粘住”≠硬件动作 | 分层排查见 ARCHITECTURE.md“排查法”；灯/屏幕必须肉眼确认 |
| UI 按钮无效但 HomeKit 同功能有效 | 状态读回函数（`currentValue`/`isHotspotActive`）是否恒真/恒假 | UI 是“读状态→取反→执行”；读回误判会让每次点击同向（曾导致热点 UI 永远只发“关”） |
| 手电筒失败 | `flashlight=` 执行结果、`led:torch_*` 节点 | sysfs **必须先写 torch 电流再写 switch 使能**（PM8550 使能瞬间锁存电流）；无节点时回退 `CameraManager`（需 CAMERA） |
| 热点状态显示错误 | `dumpsys wifi \| grep curState=` | 只有存活 SoftApManager 报 `curState=StartedState`；停止实例是 `<QUIT>`，勿按 "enabled"/"active" 子串匹配 |
| 热点显示开但不能上网 | `hotspot=` 执行结果、系统 SoftAP 状态 | LocalOnlyHotspot 本来不提供网络；Root SoftAP 仍要按 OEM tethering 实测 |
| 静音无效 | `media-muted` 结果、`cmd media_session` 输出 | 已用 Root `cmd media_session volume --set 0`；确认 root 可用且命令返回 `volume is 0` |
| 服务/名称串线 | 命令 service IID、`gattDefinition` | 检查是否误按 UUID 查表；相同 service UUID 的实例必须按对象或 IID 映射 |
| 家庭 App 显示感叹号/无响应 | 前台通知是否仍存在、是否反复出现 `PROBE_STOPPED` 后 `SERVICE_CREATED` | 前台 Service 已承载 BLE；若 MIUI 强杀后仍残留死 GATT server，开关一次蓝牙后打开应用以重启服务 |

## 修改服务前的清单

1. 分配新的 service IID 和 characteristic IID，保证全局唯一。
2. 在 `HAP_SERVICES` 增加服务、Name/Configured Name、特征 IID 和 command mapping；先确认 IID 全局不重复。
3. 明确每个特征的 type、properties、format 和值宽度；不要把特征 UUID、format、Android GATT 属性混为一谈。
4. 按值类型更新 `initialCharacteristicValue()`、读取/写入分支；String/TLV8/Float 不得直接套用整数 `decodeWriteValue()`。
5. 更新 `commandFor()`、`HomeKitCommand`、`AndroidCommandExecutor`、UI 启用开关和通知 label；状态必须读真实底层状态。
6. 递增广播 GSN/CN；服务定义、IID、属性或格式变化后，旧 Home 配件必要时移除并重新添加。
7. 构建、`install -r`、启动并确认服务数增加且没有 `GATT_SERVICE_ADDED status != 0`。
8. 在 iPhone 上验证 Signature Read、Read、Write、状态回读、名称和图标；服务改名通过 Home 的名称编辑写入 Configured Name。

## 后续建议

- 已完成 BLE server/HAP session 到前台 `HomeKitService` 的迁移，并以 `MainActivity → MainViewModel → HomeKitCommandExecutor` 保持 UI 与运行时解耦。
- 下一步将 `HomeKitService` 内的 HAP transport、Pairing session 与 accessory database 继续拆成独立类，并为它们补单元测试。
- 为手电筒在应用启动/相机授权后立即注册 torch callback，改善外部改变手电筒后的冷启动状态同步。
- 明确多 controller 配对和 Pairings 删除策略后，再实现完整多家庭管理。
- 若追求真正的“按钮”服务语义，可实验 Stateless Programmable Switch；但当前“电源”用 Lightbulb `On` 是为了保证家庭 App 内有稳定、可直接点按的 tile。

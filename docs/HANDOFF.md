# Marionette：交接与运行手册

更新日期：2026-09-15

## 当前可交接状态

项目已经在 Xiaomi 13 上完成以下真机验证：

- HomeKit BLE 配件可广播、被家庭 App 发现并完成配对。
- Pair Setup / Pair Verify 后可进入加密控制通道。
- 当前 APK 已安装，当前 controller pairing 仍被保留。
- 8 个 GATT 服务已成功发布，最后日志无崩溃、无 `BLE_ADVERTISING_FAILED`。
- 已实现屏幕电源键按钮行为、亮度、热点、媒体静音、手电筒、电池状态与 Android 通知。

工作目录：`/Users/lin/Desktop/xiaomi13`  
真机序列号：`510af65c`  
包名：`dev.local.mihotspot`  
入口：`dev.local.mihotspot.MainActivity`

## 不要做的事

- 不要为普通升级或排障点击/调用“重置配件 ID”或“清除配对状态”。这会让 iPhone 端已有家庭配对失效。
- 不要删除应用数据、执行 `pm clear`，也不要卸载后以会清数据的方式重装。
- 不要只以 UUID 查找 HomeKit 特征定义。两个 Lightbulb 和两个 Switch 会因此发生服务串线；必须保留以 GATT characteristic 对象为 key 的映射。
- 不要在未递增 CN/GSN 的情况下改变服务清单或 IID。Home 会缓存元数据。

## 关键源码

| 路径 | 负责内容 |
| --- | --- |
| `app/src/main/java/dev/local/mihotspot/MainActivity.kt` | UI、BLE/GATT、HAP PDU、SRP 配对、Pair Verify、加密会话、服务数据库 |
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

`install -r` 会保留 `SharedPreferences`，因此保留配对。手电筒还需要应用内“授权手电筒相机权限”按钮；不能把 `CAMERA` 作为服务启动前置条件，否则用户拒绝相机权限会误阻断 BLE 配件。

最新 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 健康检查

启动后读取日志：

```sh
adb -s 510af65c logcat -d -s MiHotspotHap:I '*:S'
```

正常基线必须包含：

```text
GATT_DATABASE_PUBLISHED services=8
BLE_ADVERTISING_REQUESTED paired=true ...
BLE_ADVERTISING_STARTED ... connectable=true
```

含义：

- `paired=true`：Android 端还保存着 iPhone controller key，不代表该瞬间有活跃 BLE 连接。
- `BLE_CONNECTION ... CONNECTED` / `DISCONNECTED`：家庭 App 的真实 GATT 连接变化，UI 中的连接状态据此更新。
- `PAIR_SETUP_M6`：新的配对已写入 controller key。
- `CONTROL_SESSION_ACTIVE`：Pair Verify 后加密通道已建立。
- `CONTROL_COMMAND_RECEIVED` 与 `COMMAND_EXECUTE`：HomeKit 指令已到达及 Android 执行结果。
- `CONTROL_STATE_READ`：Home 正在读取热点、静音、亮度、电量等状态。

如需只看异常：

```sh
adb -s 510af65c logcat -d | rg -i 'FATAL EXCEPTION|AndroidRuntime|MiHotspotHap:.*(ERROR|FAILED|Exception)'
```

## iPhone/Home 测试顺序

1. 打开 Android app，确认“配对状态：已配对”与“连接状态：等待 HomeKit 连接”。
2. 打开家庭 App 中已有的 Marionette 配件。服务清单更新后，CN 已从旧版升级至 5；通常等待一次连接/刷新即可得到手电筒服务。
3. 先测试热点、静音和手电筒；手电筒无反应时，在 Android app 中确认相机权限和“手机手电筒”启用开关。
4. 测试屏幕时，每次操作均是一次电源键按下，不要将 tile 的 bool 外观误认为它会强制目标状态。
5. 发生问题后，不要先重置；先导出上述 logcat，重点保留从 `BLE_CONNECTION` 到 `COMMAND_EXECUTE` 的连续日志。

## 常见故障定位

| 现象 | 先检查 | 处理方向 |
| --- | --- | --- |
| 家庭 App 看不到新服务 | 广播中的 CN/GSN、`GATT_DATABASE_PUBLISHED` | 保留配对，关闭/重开家庭 App 后再等一次连接；新增服务时递增 CN/GSN |
| 配对失败 | `PAIR_SETUP_PARSE`、M2/M4/M6、是否误清 controller key | 不要重置 ID；只在用户确认后清除配对状态并重新配对 |
| 已配对但控制无效 | `CONTROL_SESSION_ACTIVE`、`CONTROL_COMMAND_RECEIVED`、`COMMAND_EXECUTE` | 区分 HAP 收包失败、应用内开关禁用、Root/API 权限失败 |
| 手电筒失败 | `CAMERA` 是否授权、`flashlight failed` | 在应用内授权相机；检查是否存在带闪光灯的后摄 |
| 热点显示开但不能上网 | `hotspot=` 执行结果、系统 SoftAP 状态 | LocalOnlyHotspot 本来不提供网络；Root SoftAP 仍要按 OEM tethering 实测 |
| 静音无效 | `media-muted` 结果、MIUI 音频策略 | 检查 Root `cmd media_session` 兜底是否可运行 |
| 服务/名称串线 | 命令 service IID、`gattCharacteristicDefinitions` | 检查是否错误按 UUID 查表；相同 service UUID 的实例必须按对象映射 |

## 修改服务前的清单

1. 分配新的 service IID 和 characteristic IID，保证全局唯一。
2. 在 `HAP_SERVICES` 增加服务、name 和 command mapping。
3. 更新 `initialCharacteristicValue()`、`commandFor()`、`HomeKitCommand`、`AndroidCommandExecutor`、UI 启用开关和通知 label。
4. 保持对应 characteristic signature 的 format、属性和单位正确。
5. 递增广播 GSN/CN。
6. 构建、`install -r`、启动并确认服务数增加且没有 `GATT_SERVICE_ADDED status != 0`。
7. 在 iPhone 上验证名称、图标、控制、状态读取与通知。

## 后续建议

- 首先把 `MainActivity` 拆分为 BLE server、HAP transaction/session、accessory database 与 UI 四层，再补单元测试。
- 为手电筒在应用启动/相机授权后立即注册 torch callback，改善外部改变手电筒后的冷启动状态同步。
- 明确多 controller 配对和 Pairings 删除策略后，再实现完整多家庭管理。
- 若追求真正的“按钮”服务语义，可实验 Stateless Programmable Switch；但当前 Lightbulb `On` 的设计是为了保证家庭 App 内有稳定、可直接点按的 tile。

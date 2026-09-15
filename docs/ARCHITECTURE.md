# Marionette：HomeKit-over-BLE 技术架构

更新日期：2026-09-15

## 目标与边界

Marionette 把一台 Android 手机实现为一个仅用于个人实验的 HomeKit BLE 配件。iPhone 的“家庭”App 通过 HAP-over-BLE 与手机配对、验证并发送控制指令；Android 再把这些指令映射为本机动作。

当前实现面向 Xiaomi 13（Android 13、已 Root）验证，不是 MFi 认证产品，也不适合直接用于商业分发或安全关键控制。

## 运行时结构

```text
iPhone 家庭 App
    │ HAP 广播发现 / BLE GATT / 加密 HAP PDU
    ▼
MainActivity.kt
    ├── BLE 广播：Apple manufacturer data、配件 ID、GSN/CN
    ├── GATT Server：按顺序发布 HomeKit 服务和特征
    ├── HAP 事务：签名、Pair Setup、Pair Verify、加密控制读写
    ├── 状态：配对控制器、公钥、连接设备、通知和 UI 状态
    └── Command boundary
             ▼
homekit/commands/AndroidCommandExecutor.kt
    ├── Root shell：电源键、SoftAP、系统亮度、媒体音量兜底
    └── Android API：Camera torch、AudioManager、WifiManager、PowerManager
```

`MainActivity` 目前同时承载 UI、BLE transport 和 HAP session；这是为快速真机验证而做的取舍。所有具副作用的手机操作集中在 `HomeKitCommandExecutor` 接口之后，后续可无破坏地将 HAP transport 拆到独立类或 Service。

## 配对与加密通道

实现位于 `app/src/main/java/dev/local/mihotspot/MainActivity.kt`：

1. 未配对/已配对广播均包含 Apple Company ID `0x004C` 和 HAP BLE payload。
2. iPhone 连接 GATT 后，先读取 Service/Characteristic Signature；实现会按 HAP PDU 返回服务属性、IID、类型、属性及 presentation format。
3. Pair Setup 使用 SRP-3072，完成 M1–M6；控制器 identifier 和 Ed25519 public key 存入 `SharedPreferences("homekit")`。
4. Pair Verify 以 Curve25519/X25519、HKDF-SHA-512 和 Ed25519 建立会话。
5. 后续控制 PDU 使用 ChaCha20-Poly1305 保护；读、写方向分别维护 nonce。

配对持久化键：

- `device_id`：6-byte 配件 ID，也是 BLE 广播中的 device ID。
- `accessory_seed`：配件 Ed25519 私钥种子。
- `controller_id`、`controller_key`：当前家庭控制器的 pairing identity/key。
- `setup_code_digits`：UI 中的 8 位 `4-4` 录入值；SRP 内部转换为 HAP 标准 `3-2-3` 格式。

不要在普通升级、排查 UI 或增加服务时清除上述数据。`重置配件 ID` 或 `清除配对状态` 只应由用户明确操作。

## GATT 服务模型

所有 UUID 使用 HomeKit base UUID `00000000-0000-1000-8000-0026BB765291`。配置号（CN）和全局状态号（GSN）当前为 **5**；增加或变更服务语义时应继续递增，使家庭 App 刷新缓存。

| 服务 | 服务 IID | 关键特征 IID | 作用 |
| --- | ---: | --- | --- |
| Accessory Information `0x3E` | `0x0001` | `0x0002`–`0x0007` | 厂商、型号、序列号、名称 |
| Protocol Information `0xA2` | `0x0010` | `0x0011`、`0x0012` | HAP BLE 协议配置 |
| Pairing `0x55` | `0x0020` | `0x0022`–`0x0025` | Pair Setup、Pair Verify、Pairings |
| 手机屏幕 Lightbulb `0x43` | `0x0030` | Name `0x0032`、On `0x0033`、Brightness `0x0034` | 可见 Home tile、屏幕按钮和亮度 |
| 手机手电筒 Lightbulb `0x43` | `0x0070` | Name `0x0072`、On `0x0073` | 手电筒 |
| 手机热点 Switch `0x49` | `0x0040` | Name `0x0042`、On `0x0043` | 热点 |
| 媒体静音 Switch `0x49` | `0x0050` | Name `0x0052`、On `0x0053` | 媒体静音 |
| Battery `0x96` | `0x0060` | Name `0x0062`、Level `0x0063`、Low `0x0064`、Charging `0x0065` | 手机电量 |

同一种标准服务和特征 UUID 可出现多次（两个 Lightbulb、两个 Switch）。Android 的 UUID 不足以区分实例，因此发布 GATT 时会使用 `IdentityHashMap<BluetoothGattCharacteristic, HapCharacteristic>` 以实际 characteristic 对象反查定义；请勿改回仅依 UUID 的 Map，否则名称和命令会串到其他服务。

## 命令层与权限

枚举与边界接口在 `homekit/commands/HomeKitCommand.kt`，Android 实现在 `AndroidCommandExecutor.kt`。

| 命令 | HomeKit 写入 | Android 动作 | 权限/降级 | 状态读回 |
| --- | --- | --- | --- | --- |
| `SCREEN` | 任意 bool 写入都视作一次按键 | Root：`input keyevent 26` | 无 Root 时：Device Admin 锁屏或 wake lock 亮屏 | `PowerManager.isInteractive` |
| `BRIGHTNESS` | `0..100` | 写 `SCREEN_BRIGHTNESS` | `WRITE_SETTINGS` 或 Root `settings put` | 系统亮度映射回 `0..100` |
| `HOTSPOT` | bool | Root SoftAP；否则 LocalOnlyHotspot | Root 可控制系统 SoftAP；免 Root 不是互联网共享 | SoftAP dump 或 reservation |
| `MUTE` | bool | `AudioManager.setStreamMute` | MIUI 拦截时 Root `cmd media_session` 兜底 | AudioManager / `dumpsys audio` |
| `FLASHLIGHT` | bool | `CameraManager.setTorchMode` | `CAMERA` runtime permission，不需 Root | 本进程 torch callback |

屏幕的 HomeKit 特征仍是 `On`，因为它在家庭 App 中最稳定、最可见；但服务端不把 bool 当最终状态，而是把每次写入解释为一次“电源键按下”。通知也显示“屏幕电源键：已按下”。

## UI 与状态

应用 UI 由 `buildUi()` 原生构建，包含：

- 设备名称、配对码、配件 ID 与配对状态操作。
- 配对状态：是否已保存 `controller_key`。
- 连接状态：是否有活动 BLE GATT 对端；未连接但广播中显示“等待 HomeKit 连接”。
- Root 与免 Root/系统授权分组的命令启用开关。关闭某项后，HAP 请求会被记录、通知为“未执行”，但不会产生系统副作用。
- 手电筒 `CAMERA` 授权按钮。

系统通知使用每个命令稳定的 notification ID，避免 HomeKit 轮询形成通知刷屏。

## 重要限制与下一步

- 仅保存一个 controller pairing；多控制器/完整 Pairings 管理还未成为目标。
- 手电筒外部状态只有在 torch callback 已注册后能实时追踪；首次从外部打开后的完整冷启动同步可进一步加强。
- 屏幕“按钮”使用 Lightbulb UI 兼容 Home 的可见 tile，不是 Stateless Programmable Switch 服务。
- LocalOnlyHotspot 不提供手机蜂窝网络共享；Root SoftAP 的 OEM 行为需真机验证。
- `MainActivity` 过大。下一步适合拆出 `HapBleServer`、`HapSessionManager`、`AccessoryDatabase` 与 `MainActivity` UI controller，并为 Pair Setup/Verify 与 PDU fragmentation 添加单元测试。

## 验证基线

2026-09-15 最后一次真机启动日志确认：8 个 GATT 服务全部 `status=0` 发布，`GATT_DATABASE_PUBLISHED services=8`，随后 `BLE_ADVERTISING_STARTED ... connectable=true`。当时保留的现有 pairing 状态为 `paired=true`。

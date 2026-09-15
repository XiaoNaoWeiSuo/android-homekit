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
MainActivity.kt (View)
    ├── 权限请求、原生 UI 渲染
    └── MainViewModel
             │
HomeKitService.kt (Runtime)
    ├── BLE 广播：Apple manufacturer data、配件 ID、GSN/CN
    ├── GATT Server：按顺序发布 HomeKit 服务和特征
    ├── HAP 事务：签名、Pair Setup、Pair Verify、加密控制读写
    └── 状态：配对控制器、公钥、连接设备、前台通知
             │
             └── Command boundary
             ▼
homekit/commands/AndroidCommandExecutor.kt
    ├── Root shell：电源键、SoftAP、系统亮度、媒体音量兜底
    └── Android API：Camera torch、AudioManager、WifiManager、PowerManager
```

BLE/HAP runtime 由前台 `HomeKitService` 独占，UI 关闭不会停止广播。`MainViewModel` 承担 UI 状态读取、用户命令和配对码校验；所有具副作用的手机操作集中在 `HomeKitCommandExecutor` 接口之后。

## 源码目录与 MVVM 边界

```text
app/src/main/java/dev/local/mihotspot/
├── MainActivity.kt                         View：原生 Android UI、权限请求、ViewModel 渲染
├── HomeKitService.kt                       Android Service 入口：仅绑定 Manifest 到 core runtime
├── BootReceiver.kt                         Runtime：开机/更新后恢复前台服务
├── data/
│   └── AccessorySettingsRepository.kt      Model data：配件 ID、配对与用户配置持久化
├── domain/
│   └── HomeKitFeature.kt                   Model：功能开关的稳定产品语义与展示元数据
├── ui/
│   └── MainViewModel.kt                    ViewModel：UI 状态、用户命令、配对码校验与文案
├── homekit/commands/
│   ├── HomeKitCommand.kt                   命令边界：HAP 可执行命令、结果与接口
│   └── AndroidCommandExecutor.kt           Android/Root 效果与真实状态读回
└── core/homekit/
    └── HomeKitRuntime.kt                   稳定基础设施：BLE、GATT、HAP、配对与加密
```

依赖方向固定为 `MainActivity → MainViewModel → domain/data + HomeKitCommandExecutor`。`HomeKitService` 只是 Android 生命周期入口，`core/homekit/HomeKitRuntime` 使用相同的命令接口但不依赖 View 或 ViewModel；不要再把 GATT、配对或加密逻辑放回 UI 层。

## 配对与加密通道

实现位于 `app/src/main/java/dev/local/mihotspot/HomeKitService.kt`：

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

所有 UUID 使用 HomeKit base UUID `00000000-0000-1000-8000-0026BB765291`。配置号（CN）当前为 **8**、全局状态号（GSN）为 **6**；增加或变更服务语义时应继续递增 CN，使家庭 App 刷新缓存。

| 服务 | 服务 IID | 关键特征 IID | 作用 |
| --- | ---: | --- | --- |
| Accessory Information `0x3E` | `0x0001` | `0x0002`–`0x0007` | 厂商、型号、序列号、名称 |
| Protocol Information `0xA2` | `0x0010` | `0x0011`、`0x0012` | HAP BLE 协议配置 |
| Pairing `0x55` | `0x0020` | `0x0022`–`0x0025` | Pair Setup、Pair Verify、Pairings |
| 屏幕电源 Lightbulb `0x43` | `0x0030` | Name `0x0032`、On `0x0033` | 电源键按钮：每次写入 = 一次电源键按下 |
| 屏幕亮度 Lightbulb `0x43` | `0x0080` | Name `0x0082`、On `0x0083`、Brightness `0x0084` | 屏幕背光（On 与 Brightness 都映射到背光） |
| 手机手电筒 Lightbulb `0x43` | `0x0070` | Name `0x0072`、On `0x0073` | 手电筒 |
| 手机热点 Switch `0x49` | `0x0040` | Name `0x0042`、On `0x0043` | 热点 |
| 媒体静音 Switch `0x49` | `0x0050` | Name `0x0052`、On `0x0053` | 媒体静音 |
| Battery `0x96` | `0x0060` | Name `0x0062`、Level `0x0063`、Low `0x0064`、Charging `0x0065` | 手机电量 |

同一种标准服务和特征 UUID 可出现多次（三个 Lightbulb、两个 Switch）。Android 的 UUID 不足以区分实例，因此 `gattDefinition` 先用 `IdentityHashMap<BluetoothGattCharacteristic, HapCharacteristic>` 按对象反查；MIUI 回调可能传回不同对象实例，此时退回用 IID 描述符（全局唯一）反查。请勿改回仅依 UUID 的 Map，否则名称和命令会串到其他服务。

### 新增服务/开关的推荐模板

先写一张定义表，再写代码。每一行都必须有唯一 service IID、唯一 characteristic IID、HomeKit type、值格式、读写属性和执行命令：

| 项目 | 普通开关示例 | 数值控制示例 | 名称示例 |
| --- | --- | --- | --- |
| Service type | `0x49` Switch | `0x43` Lightbulb 或对应标准服务 | 所属服务 |
| Service IID | 新分配，例如 `0x0090` | 新分配 | 不复用旧 IID |
| Characteristic type | `0x25` On | 标准数值 UUID，例如 `0x08` Brightness | `0x23` Name + `0xE3` Configured Name |
| Characteristic IID | 新分配，例如 `0x0093` | 每个特征单独分配 | 两个名称特征也不能共用 |
| Properties | `0x00B0`（当前项目 On 的读写/通知组合） | 按标准服务定义 | Name=`0x0010`，Configured Name=`0x0030` |
| Format | `0x01` Bool | 与实际值宽度一致 | `0x19` UTF-8 String |
| 状态来源 | executor 的真实状态 | executor 的真实数值 | SharedPreferences/服务配置 |

IID 是 HAP 实例身份，不是随意编号。新 IID 不能与任何已有 service/characteristic IID 冲突；同一服务内的 IID 也不能复用。定义完成后，再依次补 `HAP_SERVICES`、初始值、签名、读取、写入、命令路由和 executor。

不要只增加一个 `On` 特征就假定 Home 会正确显示。Home 根据完整服务类型、特征集合、读写属性、presentation format 和配置缓存决定图标、标题及“支持/不支持”状态。

## 命令层与权限

枚举与边界接口在 `homekit/commands/HomeKitCommand.kt`，Android 实现在 `AndroidCommandExecutor.kt`。

| 命令 | HomeKit 写入 | Android 动作 | 权限/降级 | 状态读回 |
| --- | --- | --- | --- | --- |
| `SCREEN` | 任意 bool 写入都视作一次按键 | Root：`input keyevent 26` | 无 Root 时：Device Admin 锁屏或 wake lock 亮屏 | `PowerManager.isInteractive` |
| `BRIGHTNESS` | `0..100`（On 则 100/0；写入按声明的 UInt32 解析 1/2/4 字节小端值，读取回 4 字节） | 优先写 `/sys/class/backlight/*/brightness` | 无 backlight 节点时 `WRITE_SETTINGS` 或 Root `settings put` | sysfs 读回映射到 `0..100` |
| `HOTSPOT` | bool | Root SoftAP；否则 LocalOnlyHotspot | Root 可控制系统 SoftAP；免 Root 不是互联网共享 | `dumpsys wifi` 中存活 SoftApManager 的 `curState=StartedState`（停止的实例是 `<QUIT>`） |
| `MUTE` | bool | Root `cmd media_session volume --stream 3 --set 0` | `AudioManager.setStreamMute` 仅作标记；MIUI 上以 media_session 为准 | `cmd media_session --get` / `dumpsys audio` |
| `FLASHLIGHT` | bool | sysfs `led:torch_*` / `led:switch_*`：**必须先写 torch 电流、再写 switch 使能**（PM8550 驱动在使能瞬间锁存电流，顺序反了不亮） | 无节点时 `CameraManager.setTorchMode`（需 CAMERA） | 本进程 torch 状态 |

屏幕电源与屏幕亮度已拆分为两个 Lightbulb：电源灯只有 `On`，避免 iOS 在“关灯”时联动写 `Brightness=0`（从而误触发熄屏）。电源灯不把 bool 当最终状态，而把每次写入解释为一次“电源键按下”；亮度灯则把 `On` 与 `Brightness` 都映射到手机背光。

## 扩展指南：特征值编码

新增/修改特征时必须同时满足以下三条，否则会出现“写入到达但不执行”或“读取显示错乱”。

### 1. 先区分四个“类型”

新增特征时不要只记一个 UUID 或一个数字。必须同时确定：

| 层 | 代码位置 | 必须一致的内容 |
| --- | --- | --- |
| HomeKit 类型 | `HapCharacteristic.type` | 特征 UUID，例如 `0x25=On`、`0x08=Brightness`、`0x23=Name` |
| 实例身份 | `iid` + 所属 service IID | 同 UUID 的多个服务必须靠 IID 区分 |
| 值格式 | `format` | Bool、UInt、Float、String、TLV8 等；决定 HomeKit 编解码 |
| 读写能力 | `properties` | `0x0010=readable`、`0x0020=writable`；读写特征用 `0x0030` |

`HapCharacteristic(type, iid, properties, format)` 的第 4 个参数是 HAP BLE signature 中的 presentation format，不是特征 UUID，也不是 Android `BluetoothGattCharacteristic` 的属性位。

当前项目使用的格式约定：

| format | 值类型 | HAP BLE 值字节 | 本项目示例 |
| ---: | --- | --- | --- |
| `0x01` | Bool | 1，`0x00/0x01` | On |
| `0x02` | UInt8 | 1，小端 | 0–255 的小数值 |
| `0x03` | UInt16 | 2，小端 | 需要 16 位整数时 |
| `0x04` | UInt32 | 4，小端 | Brightness、Battery Level 当前声明 |
| `0x07` | Float | 4，IEEE-754 | 温度等浮点值 |
| `0x19` | String | UTF-8 字节 | Name、Configured Name |
| `0x1B` | TLV8/Data | TLV 或原始字节 | Pair Setup、Pair Verify |

不要把 `characteristic.type == 0x08` 误认为 format；`0x08` 在本项目中是 Brightness 特征 UUID，Brightness 的 format 是 `0x04`。

已知遗留项：当前 Battery 的 `Level/Low/Charging` 定义仍使用 `format=0x04`，但读取路径返回单字节值。新增电池类特征时不要复制这个组合；应统一为“声明 UInt32 并返回 4 字节”，或把声明改为实际的一字节格式并同步验证 Home 解析结果。

### 2. 新增不同类型值时，必须分别实现读、写、状态

`processHapPdu()` 当前的 `decodeWriteValue()` 只适合 Bool/UInt 类数值。新增特征时应按特征类型分支，不能把所有写入都转成 `Int`：

| 值类型 | 写入解码 | 读取编码 | 禁止的做法 |
| --- | --- | --- | --- |
| Bool | 只接受 1 字节，规范化为 `0/1` | 1 字节 | 用字符串或 4 字节整数判断开关 |
| UInt8/16/32 | 按声明宽度以小端解码，校验 min/max | 固定返回 1/2/4 字节 | 按收到的长度猜类型 |
| Float | 只接受 4 字节，`Float.fromBits()`，校验有限值和范围 | 4 字节 IEEE-754 | 把 float 的 bit pattern 当普通整数执行 |
| String | UTF-8 解码，校验非空、长度和 UTF-8 合法性 | UTF-8 字节 | 走 `decodeWriteValue()` |
| TLV8/Data | 保留原始字节，按 TLV schema 解析 | 按协议组装 TLV/字节 | 当作字符串或数字 |

当前 `decodeWriteValue()` 对 1/2/4 字节整数提供兼容处理，4 字节超出 `0..100` 时尝试 Float；这只是现有 Brightness 的兼容逻辑。新增 Float、String、TLV8 特征必须增加显式 type/format 分支，避免错误值被“猜对”或静默执行。

### 3. 读写链路必须闭环

新增一个可控值，必须同时完成以下位置，否则会出现“写入到达但不执行”或“Home 一直正在更新”：

1. `HAP_SERVICES`：服务 IID、特征 IID、UUID、`properties`、`format`。
2. `characteristicSignature()`：类型、服务 IID、服务类型、属性和 presentation format。
3. `initialCharacteristicValue()` / `readableCharacteristicValue()`：首次 GATT 值和 HAP 读取值。
4. `commandFor()`：用 `(service.iid, characteristic.type)` 路由，不能只用 UUID。
5. `processHapPdu()`：按值类型解码，成功/失败返回正确 HAP 状态。
6. `AndroidCommandExecutor`：实际执行与真实状态读回。
7. 若要让 Home 实时刷新：增加事件通知支持，并在底层状态变化时发送通知；仅靠进程内缓存不能证明硬件状态。

bool 读必须返回 1 字节；UInt32 必须返回 4 字节小端（`leBytes32()`）。长度、格式或 TLV 外层包装不一致时，Home 可能只显示“正在更新”或“不支持”，而 Android 动作本身未必有问题。

### 各类型开关的正确建法

| 想要的交互 | 服务/特征组合 | 注意 |
| --- | --- | --- |
| 普通开关（热点、静音、手电筒） | Switch `0x49` + On `0x25`（Bool） | 无 Brightness 时 iOS 只做纯开关 |
| 开关 + 滑块（亮度） | Lightbulb `0x43` + On + Brightness `0x08`（UInt32） | iOS 开/关时可能联动写 Brightness=100/0，两个特征要映射到同一底层状态 |
| “按钮”语义（按一下触发动作） | 无真正的可点按按钮类型；用 Lightbulb/Switch 的 On，**每次写入都当一次触发**，忽略目标状态 | Stateless Programmable Switch 是输入设备，家庭 App 无法反向点按 |
| 只读状态（电量） | 只给 read 属性，不注册命令 | 如 Battery 服务 |

每种同名服务可存在多个实例：命令路由必须走 `commandFor()` 的（service IID, characteristic type）匹配，绝不要按 UUID 全局匹配。

### 服务元数据与 GATT 层的边界

`service()` 当前为 Android GATT 特征统一创建 READ/WRITE 权限；HomeKit 真正看到的能力来自加密 HAP 的 Characteristic Signature。因此新增特征时，必须以 HAP signature 的 `properties` 和 `format` 为准，不能因为 Android GATT 层可写就认为 HomeKit 允许写入。

特别注意：

- Name `0x23` 是只读展示值；用户改名必须写 Configured Name `0xE3`，不能把 Name 当作普通命令处理。
- Configured Name 写入必须按 UTF-8 处理、持久化，并让后续 Name 读取返回同一个值。
- 新增或删除特征、改变服务类型、改变特征属性/格式或 IID 后，必须递增 CN；服务语义或状态模型变化同时递增 GSN。
- 旧的 Home 配件可能继续使用缓存的服务定义。验证元数据时，应记录广播中的 CN/GSN，并在必要时移除旧配件后重新添加；不要通过重置配件 ID 绕过缓存。

### 提交前的最小验证矩阵

每个新开关或数值特征至少验证以下五条：

1. Signature Read：日志中的 type、service IID、characteristic IID、properties、format 与定义表一致。
2. Read：Home 发起读取后出现 `HAP_PDU_REQUEST opcode=0x03`，返回长度与 format 一致。
3. Write：Home 发起写入后出现 `CONTROL_COMMAND_RECEIVED` 和 `COMMAND_EXECUTE`；只有 `HAP_PDU_REQUEST` 没有这两条，优先检查 TLV 外层和值长度。
4. State：执行后再次读取，返回值必须来自真实底层状态，而不是只来自上次写入的缓存。
5. Metadata：Name/Configured Name 读取和写入都成功，服务名称不会退回“灯/开关”；CN/GSN 已更新。

HAP 返回状态的定位：`0x00` 是成功，`0x01` 是 Unsupported PDU，`0x04` 是无效 IID，`0x06` 是无效请求。日志中的 Home UI 文案“正在更新/不支持”不能直接等同于 Android executor 失败，必须结合对应的 `HAP_PDU_REQUEST`、响应状态和 `COMMAND_EXECUTE` 判断。

### 命名机制（Name 特征）

- 每个服务放 Name 特征 `0x23`（format `0x19`=String，properties `0x0010`=readable），值即 iOS 拼贴的默认名称。
- **iOS 只在"添加到家庭"那一刻读一次 Name**；之后名称是 iOS 家庭数据库里的用户数据，配件端改名不会同步。要应用新名称：家庭 App 里手动改（长按拼贴 → 配件设置 → 名称），或移除配件重新添加。
- 每个带 Name 的服务同时提供 Configured Name 特征 `0xE3`（String、可读写）；Home/iOS 改名会按服务 IID 持久化到 `SharedPreferences`，Name 读取返回该自定义名称。
- Accessory Information 的 Configured Name 映射到应用中的 `device_name`；其他服务使用 `service_name_<service IID>` 独立保存，避免同 UUID 服务串名。

## 扩展指南：Android 控制原语

新增控制项时的验证顺序：**先 adb shell su 手动验证原语 → 再接入 executor → 最后接 HomeKit**。以下原语均在 Xiaomi 13（MIUI、Magisk root）实测：

| 功能 | 原语 | 验证命令 | 坑 |
| --- | --- | --- | --- |
| 电源键 | `input keyevent 26` | `su -c 'input keyevent 26'` | 无 |
| 背光亮度 | `echo N > /sys/class/backlight/panel0-backlight/brightness`（N = pct×max/100，0 会熄屏，故最小钳到 1%） | 写后 `cat` 读回 | `Settings.System` 在 MIUI 上会被钳制且非线性，只做兜底 |
| 手电筒 | **先** `echo 500 > /sys/class/leds/led:torch_N/brightness`，**再** `echo 1 > /sys/class/leds/led:switch_N/brightness` | 写完看灯，不要只看读回值 | PM8550 驱动在 switch 使能瞬间锁存电流；顺序反了写入成功但灯不亮。关闭顺序相反 |
| 热点开/关 | `cmd wifi start-softap SSID wpa2 PASS` / `cmd wifi stop-softap` | 状态看 `dumpsys wifi \| grep curState=` 里存活的 `curState=StartedState` | 停止的 SoftApManager 也留在 dump 里（`curState=<QUIT>`）；绝不能按 "enabled"/"active" 子串匹配（`client_control_is_enabled=false` 恒在） |
| 媒体静音 | `cmd media_session volume --stream 3 --set 0`，静音前记录原音量用于恢复 | `cmd media_session volume --stream 3 --get` 读回 `volume is 0` | `AudioManager.setStreamMute` 对第三方 UID 无效，只能当标记 |

状态读回的正确性同样关键：UI 开关是“读状态 → 取反 → 执行”，读回恒真/恒假会让 UI 每次点击都变成同一个方向（本次热点 UI 失效的根因）。新增命令时必须让 `currentValue()` 反映真实硬件状态，而不是只返回进程内缓存。

## “日志 success=true 但物理无效”的排查法

1. **分发层**：确认日志出现 `CONTROL_COMMAND_RECEIVED` + `COMMAND_EXECUTE`。只有 `HAP_PDU_REQUEST` 没有这两条 = 写分发把值丢弃了（检查值长度/格式解码）。
2. **执行层**：把 executor 里的原语原样拿到 `adb shell su -c '...'` 手动跑一遍。adb 下也无效 = 原语本身错（本次手电筒的写入顺序）。
3. **硬件层**：sysfs 写入后 `cat` 读回会“粘住”，但**读回成功 ≠ 硬件动作**。灯、屏幕这类必须肉眼确认。
4. **状态层**：UI 控件无效但 HomeKit 有效（或反过来）时，先怀疑状态读回函数误判，而不是执行路径——两条路径共用同一个 executor。

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
- BLE 仍由 `MainActivity` 承载：`onDestroy` 只在 `isFinishing`（真正退出）时停掉 GATT/广播，瞬时销毁不再中断广播；`openGattServer` 移到了后台线程并带有限重试。若 MIUI 上滑清理强杀进程，仍会残留已死的 GATT server，需要开关一次蓝牙才能恢复。彻底根治要把它迁到前台 Service。
- `MainActivity` 过大。下一步适合拆出 `HapBleServer`、`HapSessionManager`、`AccessoryDatabase` 与 `MainActivity` UI controller，并为 Pair Setup/Verify 与 PDU fragmentation 添加单元测试。

## 验证基线

2026-09-15 最后一次真机启动日志确认：9 个 GATT 服务全部 `status=0` 发布，`GATT_DATABASE_PUBLISHED services=9`，随后 `BLE_ADVERTISING_STARTED ... connectable=true`，广播 `CN=0x08`。各命令在日志中均返回 `success=true`（热点/电源/静音/手电筒/亮度）。

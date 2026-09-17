# Marionette HomeKit-over-BLE 架构定稿

更新日期：2026-09-16

这份文档记录已经在 Xiaomi 13 真机验证通过的实现原则。它不是 HAP 教程；遇到问题时，先按这里的边界和不变量判断，再看 `HANDOFF.md` 的日志排障流程。

## 1. 系统边界

Marionette 把 Android 手机实现成一个面向个人实验的 HomeKit BLE 配件：

```text
iPhone 家庭 App
  └─ BLE 广播发现 / GATT / Pair Setup / Pair Verify / 加密 HAP PDU
       └─ HomeKitRuntime
            ├─ HAP 协议适配：服务、特征、IID、签名、读写、事件
            ├─ BLE 传输：GATT Server、Indicate、CCCD、广播刷新
            └─ HomeKitControlLifecycle
                 └─ AndroidCommandExecutor
                      └─ Android API / Root shell / sysfs
```

依赖方向必须保持单向：

```text
HomeKitControlModel（产品语义）
        ↑
HomeKitAccessoryCatalog（HAP 映射） ← HomeKitRuntime（BLE/HAP 会话）
        ↓                                  ↓
  HomeKitFeature（UI 投影）       AndroidCommandExecutor（真实状态）
```

`HomeKitRuntime` 是前台 Service 的稳定核心，Activity 关闭不应影响广播和状态轮询。UI 只通过 `MainViewModel` 和命令接口工作，不能承载 GATT、配对或加密状态机。

## 2. 按钮的可复用模型与生命周期

按钮不再由 UI、HAP 目录和运行时各自维护一份命令/名称/状态逻辑。统一模型位于：

`app/src/main/java/dev/local/mihotspot/homekit/controls/HomeKitControl.kt`

其中：

- `HomeKitControlModel`：按钮的命令、标题、状态文案、值类型和交互语义；完全不依赖 HAP UUID、IID、BLE 属性或 TLV。
- `HomeKitControlLifecycle`：一个按钮的完整运行时生命周期，统一负责真实状态读取、写入和规范化快照。
- `HomeKitControlModels`：所有按钮的唯一登记表。
- `HomeKitFeature` 只是模型在 UI 层的投影，不再重复定义名称和命令。

每个按钮都必须经过同一条链路：

```text
HomeKitControlModels 登记
  → HAP catalog 把模型映射为一个或多个特征
  → HAP read/write 进入对应 Lifecycle
  → Lifecycle 调 AndroidCommandExecutor
  → 读取 Android 真实状态
  → 轮询比较快照 / 发送 HAP event / 更新 UI
```

亮度和音量是最容易出错的例子：一个模型同时暴露 `On` 布尔特征和 `Brightness` Int32 特征。两个特征必须共享同一个生命周期；布尔写入代表 0 或 100，滑块写入代表 0–100，读取都回到 Android 的实际亮度/音量。轮询时则按 HAP 特征 IID 分别记录快照，确保两个特征都能收到变化通知。

Activity 内的两个数值滑块还必须做输入隔离：拖动期间、写入队列未清空期间和写入完成后的短暂稳定窗口内，禁止定时刷新或 HomeKit 广播事件回写滑块；连续拖动值合并为最新值并串行执行，避免旧写入完成后把鼠标位置拉回去。

屏幕电源是 `PRESS` 语义：Home 的布尔写入触发一次电源键动作，不能把它误当成“设置目标亮/灭”。其它开关是 `SET_STATE` 语义。新增按钮时先明确这两件事，再写 Android 执行器。

当前控制模型：

| 模型 | Android 命令 | 类型 | 交互 |
| --- | --- | --- | --- |
| 屏幕电源 | `SCREEN` | Boolean | Press |
| 屏幕亮度 | `BRIGHTNESS` | Percentage | Set state |
| 手机热点 | `HOTSPOT` | Boolean | Set state |
| 媒体静音 | `MUTE` | Boolean | Set state |
| 手电筒 | `FLASHLIGHT` | Boolean | Set state |
| GPS 定位 | `GPS` | Boolean | Set state |
| 低电量模式 | `LOW_POWER_MODE` | Boolean | Set state |
| 免打扰模式 | `DO_NOT_DISTURB` | Boolean | Set state |
| 移动数据 | `MOBILE_DATA` | Boolean | Set state |
| 媒体音量 | `VOLUME` | Percentage | Set state |

亮度执行以 Android 用户亮度档位为唯一百分比标尺：无 Root 时写入 `Settings.System.SCREEN_BRIGHTNESS`，Root 时优先通过 Root 执行同一条 `settings put system` 路径。小米显示服务会按设备校准曲线把 1–255 档位映射到 `/sys/class/backlight/*/brightness`，因此不能把背光节点原始值线性换算成 HomeKit 百分比；背光节点只用于无系统设置路径时的最终回退。读取也优先系统档位，保证 HomeKit 写入 60 后回读仍是 60。

热点由 `HotspotSettingsRepository` 保存一套配置，`AndroidCommandExecutor` 启动前先执行 `cmd wifi stop-softap`，再按该配置启动唯一 SoftAP，禁止回退到 `LocalOnlyHotspot`，以免同时出现两个个人热点。SSID、密码和频段可直接应用；Wi‑Fi 6、自动关闭时长等 SoftApConfiguration 的系统/厂商参数需通过二级页面的系统热点设置入口落地。

`AndroidCommandExecutor` 是唯一的副作用边界。它不能只返回“命令执行成功后猜测的值”；`currentValue` / `currentValueInt` 必须尽可能读取系统真实状态，否则 Home 的同步会在重启、系统设置或外部操作后漂移。

移动数据的读取优先使用 `TelephonyManager.isDataEnabled()`，它表示用户移动数据开关，而不是当前是否已经连上蜂窝网络；Root 环境下回退到 `settings get global mobile_data`。写入同时执行 `settings put global mobile_data 0|1` 和 `svc data enable|disable`，并回读确认。普通应用不能直接调用 `setDataEnabled()`，因为官方 API 要求 `MODIFY_PHONE_STATE` 或运营商权限；本项目依赖 Root 执行平台 shell 命令。

## 3. HAP 协议适配

协议目录位于 `core/homekit/protocol/HomeKitAccessoryCatalog.kt`。它只做两件事：定义固定的 HAP 数据库，以及把 `HomeKitControlModel` 适配成 HAP 特征。`HapControlBinding` 保存模型引用和线上值类型，不能重新保存另一套 command 字符串。

当前固定不变量：

| 项目 | 定值/规则 |
| --- | --- |
| Apple HAP BLE Service UUID | `00000000-0000-1000-8000-0026BB765291` |
| Service IID characteristic | `E604E95D-A759-4817-87D3-AA005083A0D1` |
| Characteristic IID descriptor | `DC46F0FE-81D2-4616-B5D9-6ABDD796939A` |
| HAP 可读可写可事件通知 | `0x01B0`：PR + PW + EV + 断开期间事件支持 |
| ConfiguredName | `0x00B0`：PR + PW + EV |
| HAP Int32 的 BLE presentation format | `0x10`，小端 4 字节 |
| HAP UInt8 的 BLE presentation format | `0x04`，1 字节 |
| 当前配置号 CN | `0x17` |

所有功能服务都必须同时提供：Service Signature (`0xA5`)、legacy Name (`0x23`) 和 ConfiguredName (`0xE3`)。三个值都要有正确的实例 ID、服务归属和 `String` presentation format。缺少其中任何一个，Home 可能显示自动生成的“开关 1/灯 1”，或者先显示“正在更新”再变成“不支持”。

当前发布 14 个服务：Accessory Information、Protocol Information、Pairing、屏幕电源、屏幕亮度、手电筒、热点、移动数据、静音、电池、GPS、低电量模式、免打扰和音量控制。功能服务的服务 IID、特征 IID 和名称一旦被 Home 缓存，后续版本应保持不变。

移动数据使用独立 Switch 服务：服务 IID `0x00D0`、Service Signature `0x00D1`、Name `0x00D2`、On `0x00D3`、ConfiguredName `0x00D4`。

Home 的图标由 HAP 服务类型决定，不能通过 `Name` 或 `ConfiguredName` 任意指定。当前 10 个控制模型实际只有两种控制图标服务：Light Bulb 4 个（屏幕电源、屏幕亮度、手电筒、媒体音量），Switch 6 个（热点、移动数据、媒体静音、GPS、低电量模式、免打扰）；另有 1 个 Battery Service，14 个服务中的其余 3 个是协议/配对基础服务。官方 HAP 还定义了 Speaker、Stateless Programmable Switch 等其它服务，但每种服务都有自己的必需特征和语义，不能只替换一个 UUID 来换图标。

语义上，屏幕电源后续可以迁移为 Switch，媒体音量可以评估迁移为 Speaker；屏幕亮度和手电筒使用 Light Bulb 是合理的，热点、移动数据、静音、GPS、低电量模式和免打扰使用 Switch 是最稳妥的映射。服务类型或特征清单变更会触发 Home 的缓存迁移，必须递增 CN、删除旧配对并重新验证，因此不要在稳定版本中直接改现有服务类型。

## 4. 配对、连接和状态同步

配对身份保存在 `SharedPreferences("homekit")`：配件 ID、配件 Ed25519 私钥种子、controller identifier/key 和配对码。普通 `install -r` 会保留这些数据；清除应用数据或卸载会破坏配对身份。

状态同步不是“写成功后改一个内存变量”，而是以下生命周期：

1. Service 创建后启动 10 秒周期的状态轮询，断开 BLE 也不停止。
2. 每个 HAP 控制特征通过对应 `HomeKitControlLifecycle` 读取 Android 真实值。
3. 按特征 IID 与上次快照比较；变化时递增 GSN，更新广播，并向已订阅的控制器排队事件。
4. HAP EV 特征使用 GATT Indicate 和标准 CCCD。指示内容为零长度，Home 收到后按 IID 回读加密 HAP 特征值。
5. 连接建立后只在本连接周期内最多递增一次 GSN；断开后保留上次状态快照，不要把变化检测重置成“未知”。
6. GSN 变化且当前无连接时，停止并重新启动广播，让 Home 能看到新的状态版本；CN 只有在服务数据库变化时才递增。

事件流和普通读必须都走同一个生命周期。普通读成功不能证明事件同步正确；要同时确认 Home 发来了订阅配置、Android 发送了 indication、随后 Home 又回读了值。

## 5. 新增按钮的步骤

1. 在 `HomeKitControlModels` 增加一个完整模型，明确真实读取方式、写入语义和值类型。
2. 在 `HomeKitAccessoryCatalog` 分配全局唯一且稳定的服务 IID/特征 IID，并引用该模型；不要直接传 `HomeKitCommand`。
3. 为布尔值使用标准 Bool；为百分比使用 HAP Int32、BLE `0x10` 和 `0..100` 范围。
4. 让 `HomeKitControlLifecycle` 覆盖读、写、快照三条路径；不要在 Runtime 里直接调用执行器读取控制状态。
5. 验证 Name、ConfiguredName、Characteristic Signature、CCCD 和事件属性。
6. 若改变服务清单或 IID，递增 CN，并在真机上删除旧配对后重新配对验证；只改 Android 执行细节不应改 IID。

## 6. 参考与限制

协议行为以 Apple 开源 [HomeKitADK 的 BLE 广播与事件实现](https://github.com/apple/HomeKitADK/blob/master/HAP/HAPBLEAccessoryServer%2BAdvertising.c) 为主要参考；ADK 是协议参考，不能直接当作 Android GATT 实现使用。

当前实现针对已 Root 的 Xiaomi 13 / Android 13 验证，不是 MFi 认证产品，也不适用于安全关键控制。Root shell、Android 版本差异、厂商电源管理和 Home 的元数据缓存都属于部署边界。

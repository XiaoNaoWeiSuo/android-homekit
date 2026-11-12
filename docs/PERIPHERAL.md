# 蓝牙键鼠外设（手机作为 HID Device）

更新日期：2026-11-11

本文记录 Marionette 的第一项「把手机变成外设」能力：**手机以标准 Bluetooth HID
键盘 / 触控板的身份接入主机**。主机端（macOS / Windows / Linux / iPadOS）不需要
安装任何软件或驱动，也不需要 root。

## 1. 为什么是 Classic HID

三条可选路径里，只有 Classic HID 同时满足「标准协议 + 零主机软件 + 不依赖内核」：

| 方案 | 主机是否需要软件 | Android 侧依赖 | 结论 |
| --- | --- | --- | --- |
| Classic HID Device（`BluetoothHidDevice`，API 28+） | 否，系统自带 HID 驱动 | 公开 API，无 root | **采用** |
| BLE HOGP（GATT 0x1812） | 否 | 自写 GATT 服务，可复用 HomeKit 的 GATT 经验 | 备选，后期可做低功耗版 |
| 自定义协议 / 主机守护进程 | 是 | — | 不符合要求 |

`BluetoothHidDevice` 注册的是标准 SDP 记录（HID Device，subclass `0xC0` =
键盘 `0x40` | 指点设备 `0x80`）与标准 HID Report Map，主机按普通蓝牙键鼠处理。

## 2. 协议：Report Map

报告描述符在 `peripheral/HidProtocol.kt`，采用 HID 1.11（§6.2.2）与 USB HID Usage
Tables 1.12 定义的通用复合描述符（与 Linux HID 示例、Arduino/Adafruit HID 库同源），
共 144 字节：

| Report ID | 用途 | 输入报告长度 | 字段 |
| --- | --- | --- | --- |
| 1 | 键盘 | 8 字节 | 修饰键 1B + 保留 1B + 6 个 usage |
| 2 | 相对鼠标 | 4 字节 | 按键 3bit + 5bit 填充 + dx/dy/wheel（各 1B 有符号） |
| 3 | 消费控制 | 2 字节 | 16 位 little-endian usage（预留多媒体键） |

键盘还有一份 Output 报告（LED：NumLock/CapsLock/Kana），主机写回时通过
`onSetReport` 收到，当前只记录在 `ledState`。

不变量（`HidProtocolTest` 断言）：

* Collection 嵌套配平（结束深度 0，最大深度 2）；
* 各 Report ID 的输入位宽换算后恰为 8 / 4 / 2 字节；
* 报告构造函数长度与描述符一致；
* 鼠标增量被夹到 −127..127，键盘最多 6 个同时按下的键。

## 3. 代码结构

```text
app/src/main/java/dev/local/mihotspot/peripheral/
├── HidProtocol.kt        # Report Map、报告编码、ASCII→usage 映射（纯 Kotlin）
├── BtHidPeripheral.kt    # BluetoothHidDevice 注册、连接状态、报告发送、重连
├── PeripheralService.kt  # 前台服务（connectedDevice）+ PeripheralHub 单例
├── TouchpadView.kt       # 手势→相对鼠标/滚轮/点击
├── KeyboardPanel.kt      # 屏上键帽，多指组合键 / 统一图标
└── PeripheralActivity.kt # 外设页面（状态、配对、触控板、键盘、文本直通）
```

与其他模块的关系：**不进入 HomeKit/HAP 栈**。HID 只依赖 Android 公开蓝牙 API，
与 `HomeKitRuntime` 的 GATT/HAP 生命周期互不影响；唯一共享的是 UI 主题与前台服务
约定。

## 4. 手势与按键映射

| 操作 | 行为 |
| --- | --- |
| 单指移动 | 相对鼠标移动（灵敏度 0.5×–3.0×，带亚像素累加） |
| 轻点 | 左键单击 |
| 双指上下拖动 | 滚轮（26 dp 一格） |
| 双指轻点 | 右键单击 |
| 长按 420 ms 后移动 | 按住左键拖拽，抬手释放 |
| 屏上键帽 | 标准 HID usage；按住即保持按下（主机自动重复），抬手才释放；支持多指和弦（如按住 ⌃ 再点 C），修饰键为按住语义 |
| 文本框 | 逐字符转 HID；退格按删除数量回放；仅 ASCII |

## 5. 键盘延迟：为什么会“鼠标顺、键盘慢”

经典蓝牙 HID 的链路只在有数据时被调度。鼠标移动是连续流量，链路始终活跃；键盘是突发流量，空闲后主机可能把 ACL 链路切到 sniff/低占空比，下一次按键要先等唤醒，表现为“键盘有延迟”。

两个对应处理：

* **低延迟保活**（触控板卡片里的开关，默认开）：连接期间每 100 ms 发送一次零位移鼠标报告。对主机语义上无操作，但让链路一直保持调度，避免进入 sniff。
* **按下即发送**：键帽在 `ACTION_DOWN` 就发出 HID 报告，而不是等 `ACTION_UP`，否则等于把手指按住的时间加进延迟；按住时长默认 40 ms 以保证主机能稳定识别一次敲击。

若关闭保活仍感觉慢，下一步应抓 HCI snoop 比对报告下发时刻与链路唤醒，而不是继续在应用层加逻辑。

## 6. 配对流程（macOS 示例）

1. App 首页 →「蓝牙键鼠外设」→ 打开「外设模式」（首次会申请蓝牙权限）。
2. 点「允许被发现 120 秒」，系统弹出可发现确认。
3. Mac：系统设置 → 蓝牙 → 在「附近的设备」里选择设备名（默认 `Magic Keyboard`，
   可在页面里改）。
4. 状态徽标变为「已连接」，触控板与键帽即开始工作。
5. 之后重新进入时，已配对主机会自动重连；也可点「重新连接」。

日志（`adb logcat -s MiHotspotHid:I`）关键顺序：

```text
HID_PROFILE_CONNECTED proxy=true
HID_REGISTER_APP name=Magic Keyboard result=false
HID_APP_STATUS registered=true
HID_CONNECTION state=2 device=<host> name=<host name>
```

> `registerApp` 返回 `false` 不代表失败：HyperOS/AOSP 会异步回调
> `onAppStatusChanged(true)`，实现以回调为准，并在 2.5 s 内没有回调时才报错。

## 7. 已知边界

* **相对指针，不是原生触控板**：macOS 的三指滑动、双指缩放等手势走 Apple 私有
  HID（Magic Trackpad vendor page），本实现不冒充那套格式；标准 HID 只能给
  光标、点击、滚轮。
* **文本框只转发 ASCII**：中文/表情需要主机输入法，HID 键盘本身无法直接产生。
* **一次一个活动主机**：这是 HID Device 的常规行为；切换主机需要在主机的蓝牙
  设置里断开再连。
* **屏幕需保持亮起**：触控板依赖 Activity 前台；服务负责连接存活，但输入需要 UI。
* **沉潜模式不是真正的 AOD**：AOD 由 SystemUI 绘制且 doze 下触摸通常不下发给应用，第三方 App 无法承载；沉潜模式用「纯黑 + 窗口亮度 0.01 + 常亮 + 全屏」近似。
* **厂商差异**：注册成功不等于主机会自动配对；若系统没有开放 HID Device profile，
  状态会显示「失败」并给出提示（真机小米 13 / Android 13 已注册成功）。

## 8. 外设界面（2026-11-11 UI 重构）

入口与开关：

* 首页顶部卡片只有两个开关：**HomeKit**（原服务开关）与 **HID**（蓝牙键鼠），旁边显示
  `HID 关闭 / 待机 / 已连接 / 失败` 徽标。
* 主机连接后首页出现「外设模式」卡片，提供 **沉浸** / **默认** 两个入口；旧的
  「蓝牙键鼠外设」独立页面已删除。
* 设备与手感设置移到统一设置页，三级结构：
  设置 → **蓝牙键鼠**（设备名 / 允许被发现 / 重连 / 重置蓝牙 / 灵敏度 / 鼠标 DPI /
  USB 回报率 / 低延迟保活 / 拖尾轨迹 / 按键音效 / 按键触感）→ **按键触感**（启用、强度、试一下）。
* 按键触感：每次按键 8 ms 单次震动，振幅按强度百分比（默认 30%），走 `VIBRATE`。
* 连接成功时发送高优先级 heads-up 通知（通道 `peripheral_connected`，点击直达沉浸模式）。

键鼠界面规则（`PeripheralPadActivity`）：

| 规则 | 行为 |
| --- | --- |
| 进入 | **强制横屏**，bar 紧贴右侧，键盘占满宽度，触控板区域宽度为 0（默认不启用） |
| 拖动 bar | 沿分屏轴缩放键盘/触控板（横屏水平拖、竖屏垂直拖）；bar 是 54 dp 短手柄，不再铺满整条、无三点装饰 |
| 上/下滑 bar | 切换全屏键盘 / 全屏触控板（横屏） |
| 双击 bar | 50/50 共存；**只有该状态解锁竖屏** |
| 方向 | 横屏「键盘左 / 触控板右」，竖屏「键盘上 / 触控板下」，顺序不变 |
| 退出 | ✕ 不再悬浮，作为键帽并入键盘第一行最右侧，无独立背景 |
| 返回手势 | 沉浸模式先对整窗设置 `setSystemGestureExclusionRects`（系统按每边 200 dp 截断）；实测 MIUI 的手势 stub 不遵守该限制，因此应用同时**吞掉返回事件**（`onBackPressed` + API 33 `OnBackInvokedCallback`），边缘手势/返回键不会退出，只有键盘上的 ✕ 能离开；底部 home 手势属于系统，不允许屏蔽 |
| 键位 | 16 单位 ANSI 网格（tab/caps 2u、shift 2.5/3.5u、enter 3u、space 7.5u），字母大写，`sans-serif-medium`；沉浸模式下静止无填充，仅按下时显示浅色填充 |
| 特色键 | tab / caps / enter / ⌫ / ⇧ / ⌃ / ⌥ / ⌘ / 方向键 / ✕ / esc 静止时带 1 px 浅色描边，按下补充浅色底与更亮描边 |
| 图标 | 手绘统一线稿：1.5 dp 描边、17 dp 方框、同一风格；左侧键左下对齐、右侧键右下对齐，与键帽宽度无关（MacBook 式） |
| 组合键 | 面板统一处理多指：每个指针独立按下/抬起，修饰键按住生效；长按不再被 40 ms 强制释放，主机自行重复 |
| 触控板 | 无文字、无卡片；中心画一个小十字；可在设置 → 蓝牙键鼠 → 「拖尾轨迹」开启淡出轨迹 |

## 9. 验证记录（2026-11-11）

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 编译 | `:app:assembleDebug` 成功 | Gradle BUILD SUCCESSFUL |
| 协议单测 | `HidProtocolTest` 6 项全部通过 | `testDebugUnitTest` |
| 真机注册 | `HID_REGISTER_APP` → `HID_APP_STATUS registered=true`，无注销循环 | logcat `MiHotspotHid` |
| 前台服务与连接 | `isForeground=true`；`HID_CONNECTION state=2`（焱林的MacBook） | `dumpsys activity services` / logcat |
| 键盘鼠标实际生效 | 用户实测输入与光标均正常 | 用户反馈 |
| 连接通知 | `id=4202`、通道 `peripheral_connected`、importance=4 | `dumpsys notification` |
| 首页双开关 / 徽标 / 模式卡片 | 均出现 | `uiautomator dump` |
| 设置三级页 | 设置 → 蓝牙键鼠 → 按键触感 均正常渲染 | `uiautomator dump` |
| 按键震动 | `opPkg=dev.local.mihotspot`，8 ms，振幅 0.298（≈30%） | `dumpsys vibrator_manager` |
| 默认态 | 横屏、键盘占满宽度、✕ 在键盘首行末尾 | 键位 bounds（a 宽 146px） |
| 双击 50/50 | 键盘宽度减半（a 宽 67px），`requestedOrientation=FULL_SENSOR` | bounds + `dumpsys activity` |
| 竖屏上下分屏 | **未完成实测**：HyperOS 忽略 adb 强制旋转，代码按 `height > width` 分支 | — |
| bar 拖动 / 上下滑切换 | 机制与双击同一路径，**未逐项实测** | — |

## 10. 后续（不在本期范围）

* 有线（USB）摄像头 / 麦克风：另开文档，先确认设备内核的 gadget 函数（`f_uac*`、
  `f_uvc`、`f_hid`）与 `f_fs` 能力。
* BLE HOGP 低功耗版：可复用现有 GATT 服务器代码，适合长期挂着不耗电。
* 沉潜模式可选：用 root 压低刷新率 / 把亮度做成设置项。

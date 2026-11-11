# USB 外设（摄像头 / 麦克风 / 扬声器 / 键鼠）

更新：2026-11-11

目标：手机通过 **USB 标准类协议** 变成主机的外设，主机零安装、零驱动：

| 能力 | USB 类 | 主机侧 | 手机侧 |
| --- | --- | --- | --- |
| 键盘 + 触控板 | HID (`f_hid`) | 系统 HID 驱动 | `/dev/hidg0` |
| 摄像头（前/后/屏幕） | UVC (`f_uvc`) | 系统 UVC 驱动 | `/dev/videoN`，V4L2 output |
| 麦克风 → 主机 | UAC2 (`f_uac2`) | 系统 USB Audio 驱动 | ALSA capture PCM |
| 主机 → 扬声器/听筒 | UAC2 (`f_uac2`) | 系统 USB Audio 驱动 | ALSA playback PCM |

## 1. 真机事实（小米 13 / Android 13 / kernel 5.15.74）

- UDC：`a600000.dwc3`，实际链路 **high-speed (USB 2.0)**。
- 内核已内建：`CONFIG_USB_F_HID=y`、`CONFIG_USB_F_UVC=y`、`CONFIG_USB_F_UAC2=y`、`CONFIG_USB_CONFIGFS_F_*=y`。
- 启动时厂商已建立 `functions/uvc.0`、`uac2.0`，但 `uvc.0` 的 configfs 图不完整，不能直接复用；必须新建实例（例如 `uvc.marionette`）。
- 厂商 USB gadget HAL（`vendor.usbgadget-hal-1-1`）会在 USB 事件时重写 `g1` 配置。直接改 configfs 前必须 `stop vendor.usbgadget-hal-1-1`，以及先 `echo "" > g1/UDC`。
- `g1/max_speed` 默认 `super-speed-plus`，即使线是 USB 2.0。UVC 在 SS 模式下要求 `control/class/ss` 与 `streaming/class/ss` 都存在；否则 `uvc_copy_descriptors()` 返回 `-ENODEV`，表现为 `udc: failed to start g1: -19`。
  - 解决：`echo high-speed > g1/max_speed`，并同时链 `fs/ss` 控制类与 `fs/hs/ss` 流式类。
- 内核的 UVC configfs **没有** `control/class/hs`（源码 `uvcg_control_class_create_children()` 只有 fs/ss），这是正常的。

## 2. configfs 绑定顺序（已验证可 bind）

```sh
G=/config/usb_gadget/g1
F=$G/functions/uvc.marionette
H=$G/functions/hid.marionette

stop vendor.usbgadget-hal-1-1
echo "" > $G/UDC
echo high-speed > $G/max_speed

# --- HID ---
mkdir $H
cat report_desc.bin > $H/report_desc
echo 8 > $H/report_length
echo 0 > $H/protocol; echo 0 > $H/subclass

# --- UVC control ---
mkdir -p $F/control/header/h
ln -s ../../header/h $F/control/class/fs/h
ln -s ../../header/h $F/control/class/ss/h

# --- UVC frame: 640x480 YUYV ---
mkdir -p $F/streaming/uncompressed/u/640x480
... wWidth/wHeight/dwFrameInterval/dwMaxVideoFrameBufferSize/dwMin|MaxBitRate ...
mkdir -p $F/streaming/header/h
ln -s ../../uncompressed/u $F/streaming/header/h/u
ln -s ../../header/h $F/streaming/class/fs/h
ln -s ../../header/h $F/streaming/class/hs/h
ln -s ../../header/h $F/streaming/class/ss/h
echo 3072 > $F/streaming_maxpacket   # 640x480@30 需要 3072
echo 1 > $F/streaming_interval

# --- 链接函数 ---
cd $G/configs/b.1
ln -s ../../functions/ffs.adb f1
ln -s ../../functions/hid.marionette f2
ln -s ../../functions/uvc.marionette f3
ln -s ../../functions/uac2.0 f4

# 守护进程必须先启动（等节点、排队 V4L2 buffer、订阅事件），再 bind
echo a600000.dwc3 > $G/UDC
```

UAC2 需要（宿主视角 playback=手机扬声器，capture=手机麦克风）：

```sh
echo 3 > uac2.0/p_chmask; echo 48000 > uac2.0/p_srate; echo 2 > uac2.0/p_ssize
echo 1 > uac2.0/c_chmask; echo 48000 > uac2.0/c_srate; echo 2 > uac2.0/c_ssize
```

## 3. 安全规则（重要，已踩过 kernel panic）

1. **绝不**在主机 SET_INTERFACE(alt=1) 之前调用 `VIDIOC_STREAMON`。内核 `uvc_v4l2_streamon()` 会调用 `uvc_function_setup_continue()`，没有 pending setup 时触发内核异常（曾导致整机 kpanic）。
   - 正确顺序：打开 `/dev/videoN` → `S_FMT` → `REQBUFS`/`QBUF` → 订阅 UVC 事件 → 等 `UVC_EVENT_STREAMON` → 再 `STREAMON`。
2. `/dev/hidg0` 的 `write()` 在主机没有读取请求时会**阻塞**；必须 `open(O_NONBLOCK)` 并处理 `EAGAIN`。关闭 fd 前不要 unbind。
3. 停止顺序：停止采集 → 守护进程关闭所有节点（`STREAMOFF`、close）→ `echo "" > UDC` → 还原 links → `start vendor.usbgadget-hal-1-1`。
4. 热拔插/热重绑期间不要让 V4L2 处于 streaming 状态。

## 4. 进程架构

App 进程（Kotlin）负责 Android 侧采集与 UI；root 守护进程负责设备节点 I/O：

```
Kotlin: UsbPeripheralService
  ├── UsbGadgetController   (root shell: configfs 绑定/还原)
  ├── UsbHidLink            ── TCP 47831 ──┐
  ├── UsbVideoSource        ── TCP 47832 ──┤  marionette_usbd (root, native)
  └── UsbAudioBridge        ── TCP 47833/4 ┘   /dev/hidg0, /dev/videoN, ALSA
```

守护进程只监听 `127.0.0.1`，每条连接首行必须是 `HELLO <token>\n`（token 由服务启动时随机生成，经命令行传入）。

### 4.1 HID（47831）

App → 守护进程：原始 HID 报告字节（含 Report ID）。守护进程 `write()` 到 `/dev/hidg0`，非阻塞，失败丢弃。

### 4.2 视频（47832）

App → 守护进程的二进制帧：

```
magic   "MRV1"        4 bytes
width   uint16 LE
height  uint16 LE
format  uint8          0 = NV21, 1 = RGBA8888
flags   uint8          保留 0
length  uint32 LE      payload 字节数
pts     uint64 LE      纳秒
payload ...
```

守护进程把 NV21/RGBA 转成 UVC 当前协商的 YUYV（必要时 MJPEG 后续再加），按主机帧间隔写入 V4L2 output。主机未取流时丢弃帧，不阻塞。

### 4.3 音频（47833 麦克风，47834 扬声器）

- 47833：App → 守护进程，S16LE mono 48 kHz（手机麦克风）。
- 47834：守护进程 → App，S16LE stereo 48 kHz（主机播放）。
- 守护进程用 `dlopen("libtinyalsa.so")`，参数可用命令行 `--pcm-card` 或自动探测 `UAC2Gadget`。

## 5. UVC 控制请求

`UVC_EVENT_SETUP` 必须响应，否则主机会挂起控制传输。参考 `tools/usb/uvc-gadget.c` 的
`uvc_events_process_*`，对标准/类请求返回零长度或合理默认值，然后
`ioctl(fd, UVCIOC_SEND_RESPONSE, &resp)`。`UVC_EVENT_DATA` 用于 SET_CUR 的数据阶段。

## 6. 验证基线

- 主机侧无需安装任何软件：
  - macOS：`hidutil list`、`system_profiler SPCameraDataType`、`system_profiler SPAudioDataType`。
  - Windows：设备管理器出现 HID / USB Video Device / USB Audio。
- 手机侧：`/dev/hidg0`、`/dev/videoN`（`name=dwc3-gadget`）、`/proc/asound/cards` 出现 `UAC2Gadget`。
- 日志：`adb logcat -s MiHotspotUsb:I`，原生守护进程 `logcat -s marionette_usbd`。

## 7. UVC 真机风险（2026-11-11，重要）

- 主机可以枚举出标准 `UVC Camera`（macOS `system_profiler` 可见），AVFoundation/ffmpeg 也能抓到 640x480 帧。
- 但在 UVC 取流/热重绑过程中，真机出现过 `POWERUP_REASON: kpanic`，手机会重启；这是内核级问题，应用层无法保证规避。
- 目前判断：vendor `f_uvc` 在主机仍持有 streaming 接口时执行 UDC unbind/rebind 会触发异常。
- 安全规则：
  1. UVC 仍视为实验性；不要在主机相机 App 打开时切换/停止 USB 外设。
  2. 切换前先关闭主机相机 App，最好拔掉数据线再重新枚举。
  3. HID/UAC2 不受此问题影响，可先作为稳定能力交付。
- 事件通知必须使用 `POLLPRI`（已修）；否则守护进程收不到 `STREAMON/SETUP`，主机只能读到打开时预排队的空 buffer。

## 8. 已知边界

- 手机 USB 口是 USB 2.0：640x480 YUYV@30 约 18 MB/s，可靠；1280x720 需要 MJPEG。
- UVC 摄像头在主机端是一个标准摄像头；前三路（前置/后置/屏幕）在手机上切换，主机会看到画面源切换。
- 隐藏系统导航条、音频路由（听筒）属于 Android 侧，另见主文档。
- Windows 对 UVC/UAC2 的标准驱动支持良好；HID 已验证。

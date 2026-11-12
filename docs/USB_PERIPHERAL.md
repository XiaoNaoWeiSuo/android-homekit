# USB 外设（摄像头 / 麦克风 / 扬声器 / 键鼠）

更新：2026-11-12（MJPEG 视频、FRONT/BACK 切换与全功能生命周期均已上机验证，详见 §12）

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

# --- UVC frame ladder: 竖屏 MJPEG@30（顺序即 bFrameIndex，第一档是主机默认） ---
mkdir -p $F/streaming/mjpeg/m/p1080
D=$F/streaming/mjpeg/m/p1080
echo 1080 > $D/wWidth; echo 1920 > $D/wHeight
echo 400000 > $D/dwMaxVideoFrameBufferSize   # 单帧 JPEG 上限，实际长度按帧报告
echo 48000000 > $D/dwMinBitRate; echo 96000000 > $D/dwMaxBitRate
echo 333333 > $D/dwDefaultFrameInterval; echo 333333 > $D/dwFrameInterval
mkdir -p $F/streaming/header/h
ln -s ../../mjpeg/m $F/streaming/header/h/m
ln -s ../../header/h $F/streaming/class/fs/h
ln -s ../../header/h $F/streaming/class/hs/h
ln -s ../../header/h $F/streaming/class/ss/h
echo 3072 > $F/streaming_maxpacket
echo 1 > $F/streaming_interval
echo 0 > $F/streaming_maxburst

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
   - 正确顺序：打开 `/dev/videoN` → `S_FMT` → `REQBUFS` + mmap → 订阅 UVC 事件 → 等 `UVC_EVENT_STREAMON` → `STREAMON` → **再** `QBUF` 全部 buffer（顺序原因见 §9.3）。
   - 同理，`VIDIOC_STREAMOFF` 也**只能**在 `UVC_EVENT_STREAMOFF` 回调里调用（vendor `uvc_v4l2_streamoff()` 同样调用 `uvc_function_setup_continue()`）。
2. `/dev/hidg0` 的 `write()` 在主机没有读取请求时会**阻塞**；必须 `open(O_NONBLOCK)` 并处理 `EAGAIN`。关闭 fd 前不要 unbind。
3. 停止顺序：停止采集 → 守护进程只 **close** 节点（**不要** `STREAMOFF`：关闭 fd 会走 `uvc_v4l2_release()` → `uvcg_video_disable()`，没有 setup 握手）→ `echo "" > UDC` → 还原 links → `start vendor.usbgadget-hal-1-1`。
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

`report_length` 必须覆盖**含 Report ID 的线上长度**（键盘 1+8=9 字节）。`f_hid` 用它决定
中断 IN 请求长度：写成 8 时每个键盘报告被截断成 8 字节，主机等不到第 9 个字节、
永远不重新发起请求，`/dev/hidg0` 从此只返回 `EAGAIN`（`Try again`），而主机侧
`hidutil` 仍能正常列出键盘设备——看起来“绑定成功、就是打不出字”。

### 4.2 视频（47832）

App → 守护进程的二进制帧：

```
magic   "MRV1"        4 bytes
width   uint16 LE
height  uint16 LE
format  uint8          0 = NV21, 1 = RGBA8888, 2 = 完整 JPEG
flags   uint8          保留 0
length  uint32 LE      payload 字节数
pts     uint64 LE      纳秒
payload ...
```

UVC 只暴露**一种**格式 `V4L2_PIX_FMT_MJPEG`，同一 format 下挂两个**竖屏**档位
（`start.sh` 的 frame ladder；主机在 VS_PROBE 里选档，序号即创建顺序）：

| bFrameIndex | 尺寸 | 单帧上限 `dwMaxVideoFrameBufferSize` |
| --- | --- | --- |
| 1 | 1080×1920 | 400000 |
| 2 | 720×1280 | 250000 |

帧率固定 30 fps（`dwDefaultFrameInterval=333333`），实际长度仍由每帧 `bytesused` 给出。
daemon 在 VS_COMMIT 时把主机选中的档位以 `MODE <w> <h>` 发回视频 socket，App 按这个画布编码
（见 §13）。竖屏档位是必须的：横屏画布会被 App 按 sensor 旋转再重采样，画面躺倒且切源后出条纹。

- `format = 2`（JPEG）：守护进程**原样直通**到 V4L2 output，不做任何转码，`bytesused` = 真实 JPEG 长度。
  这是 App 当前实际发送的格式：`UsbVideoSource` 压到主机协商的档位（默认竖屏 1080×1920），
  JPEG 质量按实测编码耗时在 55–88 之间自适应。
- `format = 0/1`（NV21/RGBA）：旧路径，需要守护进程自己编码，当前 UVC 协商为 MJPEG，
  这些裸帧**不会**被直接接受，仅作为历史兼容保留。
- 占位/空帧：MJPEG 模式下 `queue_blank()` 提交 `bytesused = 0` 的空 buffer（不是填黑），
  用来在主机取流前维持 buffer 循环；非 MJPEG 路径才会填黑。

主机未取流时丢弃帧，不阻塞。为什么必须上 MJPEG：USB 2.0 单等时端点约 24.6 MB/s，
裸 720p 需要 55 MB/s、原生 1080x2400 需要 155 MB/s，只有压缩格式能承载 720p。

### 4.3 音频（47833 麦克风，47834 扬声器）

- 47833：App → 守护进程，S16LE mono 48 kHz（手机麦克风）。
- 47834：守护进程 → App，S16LE stereo 48 kHz（主机播放）。
- 守护进程用 `dlopen("libtinyalsa.so")`，参数可用命令行 `--pcm-card` 或自动探测 `UAC2Gadget`。

**2026-11-11 实测（已验证，别再凭假设排查）**

- 必须用自建实例 `functions/uac2.marionette`，不要复用 vendor 的 `uac2.0`：后者出现得晚，
  且脚本里的属性写入会因目录不存在而静默失败（实测 `uac2.0` 一直是默认 `c_srate=64000`）。
- 自建实例（`p_chmask=3 / p_srate=48000 / c_chmask=1 / c_srate=48000`）绑定后：
  - `/proc/asound/pcm` 出现 `01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1`；
  - `/dev/snd/pcmC1D0c`、`pcmC1D0p` 存在，`tinycap -D 1 -d 0 -c 1 -r 48000` 能正常打开；
  - `tinypcminfo -D 1 -d 0`：PCM out 2ch/48k、PCM in 1ch/48k，与配置一致；
  - macOS `system_profiler SPAudioDataType` 出现 `Manufacturer: Xiaomi` 的 USB 播放/录音设备。
- **坑：`/proc/asound/card1/` 只有 `id`，没有 `pcm0c/pcm0p` 条目。** 守护进程若按
  `cardN/pcm*` 枚举会永远判为“没有 PCM 节点”（这正是之前反复打印
  `UAC2Gadget card 1 has no pcm*p/pcm*c nodes yet` 的原因）。发现路径必须改为
  `/proc/asound/pcm` 或直接探测 `/dev/snd/pcmC<card>D<dev>{c,p}`。

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

**2026-11-11 实测（完整组合 HID + UVC + UAC2 同时绑定）**

- HID：面板点一次 `q`，主机只收到一个字符，3 秒无重复。
- UVC：**YUYV 640×480 路径已验证可用**（2026-11-12）。灌入亮度 235 的 NV21 合成帧后，
  `/data/local/tmp/uvc_qbuf.log` 记录 `len=614400 used=614400 y0=235 y1=235 y2=235 u=128 v=128`；
  主机 `ffprobe` 收到 **94 帧、640×480、约 39 fps**，首帧平均亮度 255。
  - 该基线的格式（YUYV / 640×480 / 614400 字节帧）随后被 `519a4ee`、`56f8717`、`d5fb2d9`
    改成了 **MJPEG + 阶梯**，`eaf7e33` 起阶梯只保留竖屏档位。2026-11-12 用 App 真实相机复验：
    主机 `ffmpeg` 协商到 **1080×1920**，守护进程稳定 30 fps，每帧 145–160 KB，
    `uvc_qbuf.log` 的 `used` 是真实 JPEG 长度。
  - 之前判为“空白帧”是**测试方法问题**，不是 gadget 缺陷：重启后 AVFoundation 里
    UVC 相机的索引会变（曾由 `1` 变成 `0`），索引错了就开到内置相机上；
    另外用 `dd` 逐帧的喂帧脚本并未真正连上 47832。判据以 `uvc_qbuf.log` 的 `y0` 为准。
  - 另一个坑（YUYV 时代）：不指定 `-pixel_format yuyv422` 时 ffmpeg 会选 UYVY，与本 gadget 的
    YUYV 不匹配，表现为主机停在 alt 0、抓帧阻塞。改成 MJPEG 后**不需要**再指定像素格式，
    让 ffmpeg 按 UVC 描述符协商即可（见 §11）。
- UAC2：往 47833 灌入 440 Hz，主机录音测得 `mean -22.5 dB`；日志为
  `pcm_open ok card=1 dev=0 PCM_IN ch=1` / `PCM_OUT ch=2`，均 48 kHz。
- 扬声器方向：主机把默认输出切到该 USB 声卡并播放 440 Hz，手机侧从 47834 收流
  得到 2.79 s、`mean -25.5 dB / max -21.1 dB` 的 PCM，确认 主机播放 → PCM_IN →
  守护进程 这一段可用（App 的 AudioTrack 消费端本次未参与，因为测试时 `usb_audio` 为关）。
- UI：App 可认领存活会话（日志 `USB_SESSION_ADOPTED`），首页在未触碰 HID 开关时
  即显示「外设模式」。注意验证时的残留：应用偏好里的 `usb_token` 被写成测试值，
  后续启动会沿用。

## 7. UVC 真机风险（2026-11-11，重要）

### 7.0 2026-11-12 更正：这次重启不是 f_uvc 的 WARN

拔线后插到 Windows 触发的重启，`/data/vendor/diag/last_kmsg` 里的真实 trace 是：

```
Unable to handle kernel NULL pointer dereference at virtual address 00000000000009a0
Internal error: Oops: 96000005 [#1] PREEMPT SMP
CPU: 5 PID: 11111 Comm: marionette_usbd
pc : module_put+0x38/0x1d8
lr : __fput+0x1b8/0x440
Call trace:
 module_put+0x38/0x1d8
 __fput+0x1b8/0x440
 ____fput+0x14/0x24
 task_work_run+0x140/0x204
Kernel panic - not syncing: Oops: Fatal exception
```

要点：

- panic 在**守护进程 close() 一个 fd 的收尾路径**里（`__fput` → `module_put` 空指针），
  ！不是 `usb_composite_setup_continue()` 的 WARN，下面 §7 里那套 WARN 推导解释**不能**用来解释这次重启。
- 这台机 `panic_on_warn` 当前是 **0**，所以「WARN 被 panic_on_warn 提升为 panic」不成立。
- `usb_f_hid / usb_f_uvc / usb_f_uac2` 都不在 `/proc/modules` 里（内建），因此
  `module_put` 的那个 module 不是它们；具体是哪个 fd 需靠守护进程新增的
  `close fd=… path=…` 日志在下次复现时点名。
- 2026-11-12 已加的诊断：`usb_common.h` 的 `close_traced()` 在关闭节点前用
  `readlink /proc/self/fd/N` 打出真实路径；`marionette_usbd` 启动时会把
  继承的 fd 表形态（`fd inherited N -> path`）打进 logcat。
- 判 panic 仍然只能查 `POWERUP_REASON kpanic` 或 `last_kmsg`；`ro.boot.bootreason` 只显示 `reboot`。

- 主机可以枚举出标准 `UVC Camera`（macOS `system_profiler` 可见），AVFoundation/ffmpeg 也能抓到帧
  （历史基线是 YUYV 640×480；当前代码是 MJPEG 1280×720，见 §12）。
- 但在 UVC 取流/热重绑过程中，真机出现过 `POWERUP_REASON: kpanic`，手机会重启；这是内核级问题，应用层无法保证规避。
- 目前判断：vendor `f_uvc` 在主机仍持有 streaming 接口时执行 UDC unbind/rebind 会触发异常。
- 安全规则：
  1. UVC 仍视为实验性；不要在主机相机 App 打开时切换/停止 USB 外设。
  2. 切换前先关闭主机相机 App，最好拔掉数据线再重新枚举。
  3. HID/UAC2 不受此问题影响，可先作为稳定能力交付。
  4. 2026-11-11 复测补充：主机**没有**在取流（`streaming=0`、无 `STREAMON`）时，
     仅执行 `controller.stop()`（UDC unbind + 恢复厂商 HAL）也让真机 panic 重启。
     注意 `ro.boot.bootreason` 只会显示 `reboot`，**不能用它排除 panic**；
     要判定请查本轮 dmesg 的 `POWERUP_REASON kpanic`（`hardwareinfo` 驱动打印）。
     因此 **unbind 本身即风险点**，不要为了验证而反复启停；启动（bind）侧未观察到异常。
  5. 2026-11-11 21:05 再补：**绑定/激活一侧也会 panic**。一次
     `binduvc.sh`（解绑→重链 HID+UVC+UAC2→`echo UDC`）+ 新守护进程启动并订阅 UVC 后，
     主机开始枚举时真机 `POWERUP_REASON kpanic` 重启（`/proc/uptime` 归零）。
     所以不能把“启动安全、停止危险”当成定论；只要涉及 UVC 的 configfs 重绑，
     两侧都可能触发。重启后厂商默认 gadget 自动恢复，App 不会自动重跑 USB 外设。
  6. panic 触发条件（已从同族内核源码 `f_uvc.c` 确认，修正早期推测）：
     `uvc_function_setup()` 对**非 class** 请求直接 `return -EINVAL`，标准请求
     （含 `SET_INTERFACE`）**永远不会**送到用户态，所以“stall 标准请求导致 WARN”不成立。
     真正的条件是 `usb_composite_setup_continue()` 在 `delayed_status == 0` 时被调用，
     即用户态的 `VIDIOC_STREAMON/STREAMOFF` 找不到待完成的状态阶段。
     而 `set_alt(0)` 在 `uvc->state != UVC_STATE_STREAMING` 时**直接 return 0**：
     主机先 alt=1、紧接着 alt=0 时，内核不发 STREAMOFF、也不留 delayed status，
     若我们此时才为之前的 STREAMON 调 `VIDIOC_STREAMON`，就会踩中 WARN。
     这是厂商驱动的固有脆弱点，应用层只能缩小窗口（收到事件后立即 STREAMON），
     无法根除；在能确认主机不会中途重选 alt 之前，摄像头应默认关闭。
- 事件通知必须使用 `POLLPRI`（已修）；否则守护进程收不到 `STREAMON/SETUP`，主机只能读到打开时预排队的空 buffer。
- 2026-11-11 源码定位：除 POLLPRI 外还有两处致命缺陷（`UVCIOC_SEND_RESPONSE` ioctl 号写错、buffer 排队顺序反了），净效果与“收不到事件”完全一致。详见 §9。

## 8. 已知边界

- 手机 USB 口是 USB 2.0：裸 1080×1920@30（YUV）约 93 MB/s，远超单等时端点的 ~24.6 MB/s，
  因此视频走 **MJPEG 竖屏 1080×1920（单帧上限 400 KB）/ 720×1280（250 KB）**，
  主机在 VS_PROBE 里选定一档。
- UVC 摄像头在主机端是一个标准摄像头；前三路（前置/后置/屏幕）在手机上切换，主机会看到画面源切换。
- 隐藏系统导航条、音频路由（听筒）属于 Android 侧，另见主文档。
- Windows 对 UVC/UAC2 的标准驱动支持良好；HID 已验证。

## 9. 为什么永远 `streaming=0`（2026-11-11 源码定位）

依据：AOSP `android13-5.15` 的 `drivers/usb/gadget/function/{f_uvc,uvc_v4l2,uvc_video,uvc_queue}.c`、
`fs/select.c`、`drivers/media/common/videobuf2/*`。三处缺陷叠加，任意一处都能让主机走不到
`SET_INTERFACE(alt=1)`，也就永远收不到 `UVC_EVENT_STREAMON`：

1. **`UVCIOC_SEND_RESPONSE` 的 ioctl 号写错（致命）。** `uvc_stream.cpp` 里是
   `_IOW('U', 3, ...)`，内核 UAPI（4.19～6.6、含 android13-5.15）是
   `_IOW('U', 1, struct uvc_request_data)`。号不匹配时 `__video_do_ioctl()` 把它当
   “未知 ioctl”丢给 `uvc_v4l2_ioctl_default()` 的 default 分支，返回 `ENOIOCTLCMD`→
   用户态 `ENOTTY`。结果：**每一个 SETUP 都没有应答**，主机的
   `GET_CUR/SET_CUR (VS_PROBE/COMMIT)` 控制传输挂起、超时，不会再发
   `SET_INTERFACE(4, 1)`。日志特征：`UVC: SEND_RESPONSE: Inappropriate ioctl for device`。
2. **事件队列从未被读。** `v4l2_poll()` 在 `fops->poll` 存在时用驱动返回值覆盖默认的
   `EPOLLPRI`；UVC 的 `uvc_v4l2_poll()`= `uvcg_queue_poll()`→`vb2_poll()`，而
   `vb2_core_poll()` 对 **output** 队列在未请求 `EPOLLOUT` 时直接 `return 0`，只有
   `v4l2_event_pending(fh)` 才补 `EPOLLPRI`。`poll()` 又会按 `events` 掩掉未请求的位
   （`fs/select.c: mask &= filter`），所以旧的 `POLLIN` 等待永远等不到事件。
   24a82ab 加 `POLLPRI` 是对的；现在进一步改为 **每轮无条件 `VIDIOC_DQEVENT`**
   （poll 超时 50 ms），不再依赖驱动的 poll 位。
3. **buffer 排队顺序。** vendor `uvc_v4l2_qbuf()` 只在
   `uvc->state == UVC_STATE_STREAMING`（即 `STREAMON` 之后）才 `queue_work(pump)`；
   `uvcg_video_enable()` 只排 2 个零长度 isoc 请求，`uvc_video_complete()` 也只在
   `req_ready` 非空时才重新排 pump。旧代码在 **open 节点时**就 QBUF 了 4 个 buffer，
   早于 `STREAMON`，于是 pump 永远不跑：`streaming=1`、`DQBUF` 永远 `EAGAIN`
   （`out=0`，`drop(nobuf=…)` 增长）。现在：open 只 REQBUFS+mmap，
   **`STREAMON` 成功后再 QBUF 全部 buffer**（`handle_streamon()`）。

附带修正：`UVC_EVENT_LAST` 在 5.15 是 `PRIVATE_START+5`，`UVC_EVENT_CONTROL(+6)`
订阅必然 `EINVAL`（旧代码只是静默忽略），已从订阅表移除；`UVC_EVENT_DISCONNECT`
现在触发“关 fd 重开”，因为 `uvc_function_disable()` 之后 vb2 队列被标
`UVC_QUEUE_DISCONNECTED`，后续 QBUF 会一直 `-ENODEV`。

## 10. 安全停用：可以不 unbind UDC 吗？

a13-5.15 的 `uvc_v4l2.c` 里两个 ioctl 都带 setup 握手：

```
STREAMON : uvcg_video_enable()  + uvc_function_setup_continue(uvc, 0)
STREAMOFF: uvcg_video_disable() + uvc_function_setup_continue(uvc, 1)
```

`uvc_function_setup_continue()` 无条件调用 `usb_composite_setup_continue()`，后者在
`cdev->delayed_status == 0` 时执行 `WARN(cdev, "Unexpected call")`；本 ROM 通过
`panic_on_warn` 把 WARN 变成 kpanic。所以**只有** host 发来 `SET_INTERFACE` 时对应的
`UVC_EVENT_STREAMON/STREAMOFF` 才有“待完成的状态阶段”，也**只能**在那两个事件里调用
STREAMON/STREAMOFF。`VIDIOC_STREAMOFF` 不是“随时可用的暂停”。

关闭 fd 是安全的：`uvc_v4l2_release()` → `uvc_v4l2_disable()` →
`uvcg_video_disable()`（无 setup 握手）+ `usb_function_deactivate()`。
但它会让整个 composite 软断开（HID/UAC2 也一起从主机消失），重新 open 才恢复。

结论：

| 目标 | 做法 | 代价 / 风险 |
| --- | --- | --- |
| 暂停画面，主机仍认为相机在 | 守护进程停止投帧；保持 fd、`STREAMON`、configfs 不动（队列耗空后主机画面冻结） | 无内核风险，主机画面停住 |
| 让主机看到“相机被拔掉” | 只能 unbind 函数 / 关 fd（软断开整个 gadget） | 需先让主机退出取流，否则回到 §7 |
| 运行中切换手机侧画面源（前/后/屏） | **已实现**（`ACTION_SWITCH_CAMERA`）：只换 `UsbVideoSource`/`UsbVideoLink`，不碰 configfs/守护进程 | 不能走 `controller.start()` 重新 bind（会 kpanic） |

两条硬性约束：

- **同一时刻只允许一个进程打开 `/dev/videoN`。** `uvc_v4l2_open()` 每次都调用
  `usb_function_activate()`，第二次 open 命中
  `WARN_ON(cdev->deactivations == 0)` → panic_on_warn → kpanic。因此
  **不要**在 `marionette_usbd` 运行时再用 `uvc_probe`/`v4l2-ctl`/任何工具打开节点。
- UVC 函数是 `bind_deactivated`，**整个 gadget 的枚举依赖守护进程订阅
  `UVC_EVENT_SETUP`**（`uvc_v4l2_subscribe_event()` 里调 `uvc_function_connect()`）。
  守护进程一退出（关 fd → deactivate），HID/UAC2 也会一起消失。所以只要启用了摄像头，
  就不能让守护进程退出；不需要摄像头时应在 `start.sh` 阶段就不链 `uvc.*`（现有
  `__ENABLE_CAMERA__=0` 路径），而不是运行中“关掉”。

`start.sh` 无需为出流改动；仅提醒：注释里“排队 V4L2 buffer”已不再需要，
守护进程必须在 `echo UDC` 之前启动（现状就是这样，它负责等节点 + 订阅事件 + 激活 gadget）。

## 11. 修复后的上机验证

前置：**主机相机 App 已关闭**（FaceTime / Photo Booth / ffmpeg / 预览全部退出），
再重启守护进程与 bind；不要在主机取流时 `killall` 或 unbind。

1. 确认没有别的进程持有节点：`ls -l /proc/[0-9]*/fd 2>/dev/null | grep -E 'video[0-9]+'`，
   只剩 `marionette_usbd` 一个持有者。
2. 启动后看 `logcat -s marionette_usbd`（或 `/data/local/tmp/marionette_usbd.log`）：
   - `UVC: /dev/videoN ready 1080x1920 YUYV, 4 buffers` — S_FMT/REQBUFS/mmaps/订阅全部成功
     （`YUYV` 是 buffer 容量口径，实际格式是 MJPEG；尺寸是本次默认档位）；
   - 没有 `subscribe 0x… failed`（尤其 `SETUP` 的 `-EBUSY` 说明有残留句柄）。
3. 在主机侧打开相机（或 `ffmpeg -f avfoundation -framerate 30 -i <索引> -t 3 /tmp/uvc.mov`；
   不要指定 `-video_size`/`-pixel_format`，让主机从竖屏档位里自己协商），应依次看到：
   ```
   UVC: setup 21/01 v=0100 i=0001 l=34 -> 34    # SET_CUR(VS_PROBE)
   UVC: setup a1/81 ...            -> 34        # GET_CUR(VS_PROBE)
   UVC: setup 21/01 v=0200 ...     -> 34        # SET_CUR(VS_COMMIT)
   UVC: host selected alt 1, streaming
   UVC: in=… out=… drop(notstream=0,nobuf=…,bad=0) streaming=1
   ```
   内核 dmesg 此时应出现 `uvc_function_set_alt(4, 1)`（之前只有 `(3,0)`/`(4,0)`）。
4. 主机退出相机 → `UVC_EVENT_STREAMOFF` → `UVC: host selected alt 0, stream stopped`；
   此时才允许停服务（先关主机相机，再 `killall`/`echo "" > UDC`）。

失败分支：

- 只有 `setup …` 没有 `streaming`：看 `->` 的值。`-62`(`-EL2HLT`) 说明分类/应答错了；
  `34` 但仍无 `alt 1`，说明主机拒绝了 probe 参数（对比 `dwMaxVideoFrameSize`=400000（p1080 档）
  / 250000（p720 档）、`dwMaxPayloadTransferSize`=3072，与 `streaming_maxpacket=3072` 一致）。
- `streaming=1` 但 `out=0`：`DQBUF` 返回 `EAGAIN` 且 `nobuf` 增长 → QBUF/pump 顺序被打回
  （不要改回“open 时预排队”）。
- 任何 `SEND_RESPONSE: Inappropriate ioctl` → 跑的还是旧二进制。

## 12. 当前状态与恢复步骤（2026-11-12）

已在真机验证（完整组合 HID + UVC + UAC2 同时绑定）：

- 键盘/触控板：面板点一次 `q` 只出 1 个字符，3 秒无重复。
- 麦克风（手机→主机）：47833 灌 440 Hz，macOS 录音 `mean -22.5 dB`。
- 扬声器（主机→手机）：主机默认输出临时切到本设备播 2 s，手机侧从 47834
  收到 2.79 s PCM，`mean -25.5 dB`（播完已还原默认输出）。
- ADB 保持：开启→关闭 USB 外设后 `adb devices` 仍有设备（`127ee43`、`a8098dc`）。
- 画面源切换：FRONT→BACK→FRONT 全程守护进程 pid 不变、UDC 保持 `configured`（`36cb191`），
  现在单次切源首帧 ≤ 0.5 s（`b54109e`/`397b5a3`，见 §13）。

**摄像头（2026-11-12 已用 App 真实相机验证）**

- 早期合成帧基线（YUYV 640×480）：灌入 Y=235 后 `uvc_qbuf.log` 记 `y0=235`，主机收到 94 帧。
  此前“空白帧”的结论是测试方法错误（AVFoundation 索引变化 + 喂帧脚本未连上）。
- 当前链路：App 采集 → 按协商档位编码 JPEG → 47832 直通 → UVC MJPEG。主机 `ffmpeg` 协商到
  **1080×1920**（竖屏），守护进程稳定 30 fps、每帧 145–160 KB、`enc` 8–12 ms。
- FRONT→BACK 切换：首帧 PSNR 6.24 dB，确认是另一路相机；切换耗时与判据见 §13。
- 仍待验证：SCREEN 源（需 MediaProjection 授权，只能在 UI 里授予；无授权时降级为 OFF，
  日志 `USB_CAMERA_SCREEN_NO_CONSENT`）。

复现步骤（App 驱动，2026-11-12 实测有效的顺序）：

1. 主机线插着（厂商 gadget 已附着，`cat /sys/class/udc/a600000.dwc3/state` = `configured`）；
2. App 里开 USB 外设（或发 START intent），等日志出现 `USB_SESSION_READY state=configured`；
3. 主机列设备并确认索引（**每次重启后索引可能变**）：
   `ffmpeg -f avfoundation -list_devices true -i ""`；
4. 抓 3 秒：`ffmpeg -f avfoundation -framerate 30 -i <索引> -t 3 /tmp/t.mkv`；
5. 判据：`ffprobe` 的尺寸命中阶梯某一档、守护进程统计 `out>0`、
   `uvc_qbuf.log` 的 `used` 等于真实 JPEG 字节数。

风险：含 UVC 的解绑/重绑已多次触发 `POWERUP_REASON kpanic`（11-11 两次、11-12 一次）；
重启后厂商默认 gadget 自动恢复，App 不会自动重跑 USB 外设。
画面源切换已不再走重绑（见 §13）。

### 12.0 生命周期状态机（2026-11-12 重写并实测）

单一事实来源：`/sys/class/udc/a600000.dwc3/state`。`usb/online`(VBUS) 与
`typec data_role` 在这台机上**不可靠**（拔线后仍读 1 / 显示 host 时数据其实是通的），
只能做提示，不能做判据。

| 状态 | 触发 → 行为 |
| --- | --- |
| OFF | 开启时先看 UDC：主机在线 → 直接接管；不在线 → ARMING |
| ARMING | 「● 插线后自动开启」；主机一接上（厂商 gadget 变 `configured`）就自动接管 |
| PROVISIONING | 执行 `start.sh`（UVC 描述符未变则复用 function） |
| READY | 「● 运行中」；主机在线期间绝不解绑 |
| WAITING | 主机离开 → **把端口交还厂商 gadget**，等它再次 `configured` 后自动接管 |
| DISARMING | 插线时点关闭 → 「● 拔线后自动关闭」，拔线后才解绑并还原厂商 |

关键约束（都来自实测）：

1. **无主机时绑定的 gadget 永不 attach**（PHY 醒但无 CONNECTED），所以只能借厂商
   gadget 探测主机；这也是 WAITING 要把端口交还的原因。
2. **有主机时解绑是三次 kpanic 的来源**（`module_put/__fput`、`percpu_ref` UAF、
   slab 野指针），所以 stop 必须等拔线。
3. UVC 是 `bind_deactivated`：守护进程必须先打开 `/dev/videoN` 才会 activate/attach，
   不能因为「UDC 未附着」而不开节点（那会死锁）。
4. 接管后 3.5 s 稳定窗口 + 恢复后「连续 2 次探测、厂商 gadget 稳定 1.8 s」才重新接管，
   避免与厂商 HAL 的重配竞态；绑定失败只给一次 3 s 重试。
5. 拔线时先 `releaseAll()` 再改状态，避免主机侧留一个自动重复的按键。

已验证：全功能接管、脱离→交还→自动重新接管、关闭延后与厂商还原（adb 保持）、
音频空闲不再重连（30 s 计数 0）。

### 12.1 源码核对（2026-11-11，android13-5.15）

- `uvc_v4l2_qbuf()` 只在 `uvc->state == UVC_STATE_STREAMING` 时
  `queue_work(video->pump)`；而该状态是在 `uvc_v4l2_streamon()` 末尾
  （`uvcg_video_enable()` 与 `setup_continue()` 之后）才置位的。
  我们的顺序是「收到 STREAMON 事件 → `VIDIOC_STREAMON` → 之后才 QBUF」，
  **满足这个前提**，所以“泵没被唤醒”不成立，剩余问题不在排队顺序。
- `uvc_v4l2_release()` → `uvc_v4l2_disable()` → `usb_function_deactivate()`，
  这解释了为什么守护进程退出会解绑整个 gadget（HID/UAC2 一起消失）。
- `uvc_v4l2_subscribe_event()` 对 `UVC_EVENT_SETUP` 在已连接时返回 `-EBUSY`，
  所以同一时刻只能有一个进程订阅；重复 open 会失败而不是静默。

### 12.2 已知缺陷：关闭 USB 外设模式后 adb 消失（2026-11-11 修复）

症状：在 App 里关掉「USB 外设模式」后，主机再也看不到手机（无 adb、无 mtp，`ioreg` 为空），
手机本身正常（Wi-Fi 可达）。

原因：`stop.sh` 在移除我们自己 `f1..f4` 之后，又新建了 `f1 -> ffs.adb`、`f2 -> ffs.mtp`，
而厂商配置里本来就有 `function0 -> ffs.mtp`、`function1 -> ffs.adb`。**同一个 function
实例被链了两次**，configfs 绑定失败，`echo UDC` 未生效，于是整块 USB 一起消失。

修复：只删 `f1..f4`，厂商的 `functionN` 保持不动；仅当对应 ffs 链接缺失时才补建；
先 `start vendor.usbgadget-hal-1-1` 并留出 functionfs 重新挂载的时间，再绑定 UDC。

恢复手段：configfs 状态是运行时的，**重启手机**即可回到厂商默认组合（adb+mtp）。

## 13. 画面源切换：原地换源（2026-11-12 修复）

症状：USB 外设已开启时切换前置 / 后置 / 屏幕，有概率导致手机重启。

原因：`UsbPeripheralActivity.applyCamera()` 原本调用
`UsbPeripheralService.stop()` + `startUsb()`，即 `stop.sh` 解绑 UDC、`start.sh` 重新绑定。
含 UVC 的解绑/重绑正是本内核 panic 的高发路径（见 §7），因此切换会偶发 kpanic。

修复（`36cb191` 起，`2f58b88`/`b54109e`/`397b5a3` 收尾）：

- 服务新增 `ACTION_SWITCH_CAMERA`：**不触碰 configfs、守护进程和 gadget**，
  只在 App 侧换采集源；界面在「旧源与新源都是相机」时走原地换源，
  只有开启 / 关闭摄像头才重新 provisioning（那会改变 function 链接）；
- 切换在服务的单线程 executor 上执行，与绑定过程天然串行，不会并发写 configfs；
- `2f58b88`：切源期间**不断开** `UsbVideoLink`。断链会在主机重新同步的那一刻留一个空窗，
  有些主机从此停在黑帧；
- `b54109e`：整个会话复用**同一个** `UsbVideoSource`。每次新建实例会和上一个相机的异步释放撞车
  （`camera in use`），表现为主机停在最后一帧、只有重启 App 才恢复；
- `397b5a3`：真的撞上 `ERROR_CAMERA_IN_USE` / `ERROR_MAX_CAMERAS_IN_USE` 时，
  释放后 400 ms 重试一次（日志 `USB_CAMERA_OPEN_RETRY`），而不是一直失败。

### 13.1 实测时间线（2026-11-12）

`UsbVideoSource` 每次起流都会打自己的时间线（毫秒从该次请求算起）；主机侧用
`build-tools/usb/switch_probe.sh` / `switch_stress.sh` 同步记录亮度（`signalstats` 的
`YAVG`），两边对照：

```
USB_CAMERA_START source=BACK gen=4 target=1080x1920
USB_CAMERA_RELEASED after 69ms        # 关掉旧的 session / reader / device
USB_CAMERA_OPENED after 85ms          # openCamera 回调
USB_CAMERA_SESSION_READY after 153ms  # createCaptureSession 配置完成
USB_CAMERA_FRAME1 after 459ms luma=0  # 首帧；luma 0 = AE 还在起跳
USB_VIDEO_STATS 1080x1920 fps=30.0 q=89 avg=234KB enc=11.8ms luma=133
```

- 前后摄来回切 10 次：10/10 首帧 ≤ 0.5 s，0 次 `USB_CAMERA_FAILED`，结束后立刻回到 30 fps；
- 0.4 s 间隔连点 6 次：最后一次请求生效，没有 `camera in use` 失败；
- 主机侧亮度跟随切换的延迟 ≤ 0.5 s，不再停在旧帧。

### 13.2 “切过去是黑的” 先看 luma

同一时刻设备端和主机的亮度应当一致（后摄被桌面遮住时的实测）：

```
设备端 stats luma=20   主机 YAVG=20    # 一致 → 画面本来就是黑的
设备端 stats luma=164  主机 YAVG=164   # 一致 → 前置正常
```

判断顺序：两边一致 → 链路正常，暗的是场景（后摄 AE 从 luma 0 起跳，开头 1–2 s 更暗）；
主机亮度不随设备端变 → daemon/UVC 或主机解码器；设备端不再打 `FRAME1`/`STATS` →
采集侧没起来，看 `USB_CAMERA_FAILED` / `USB_CAMERA_OPEN_RETRY` 与
`logcat -s UsbVideoSource:I UsbPeripheralService:I`。

未覆盖：SCREEN 源需要 MediaProjection 授权对话框，只能通过 UI 手动验证一次；
未授权时降级为 OFF 并记 `USB_CAMERA_SCREEN_NO_CONSENT`。

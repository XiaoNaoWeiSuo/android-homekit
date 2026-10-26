# AirDrop 实验后端（更新至 2026-10-23）

## 2026-10-23 检查点三：常驻接收端到端成立，radio.sh 已改造

`radio.sh` 从"常驻 monitor vdev"改成"占空比窗口 + 主机侧汇聚接口"：

1. 预热一个窗口，逼出驱动懒创建的 `awdlsink`；
2. filin 用 `-i awdlsink` 绑定并一直读，窗口开合对它透明；
3. 监督循环按 2 秒窗口 / 4 秒周期开关 `awdlmon`，每轮顺序固定为
   建 vdev → `ip link up` → `capture_hold=1` → 抓 2 s → `hold=0` → 删 vdev。

新增两个能力闸门，把"驱动缺补丁 0047"和"驱动未以 `sta_monitor=1` 加载"从
含糊的"附近没有设备"里区分出来。

真机验证（24 s 与 54 s 两轮）：`state` 全程 `listening`，`sink_rx` 单调增长，
`awdlmon` 稳定开合，STA 每个采样点都在线，`Driver recovery` 计数不变；停止后
vdev 与 `awdl0` TAP 均被清理。filin 日志出现真实解码：
`chanseq decoded=[52, 0, 149, ...]`，并为 3 个对端 MAC 安装了邻居表项。

### 顺带查出的打包缺陷

第一次跑改造后的 `radio.sh` 时 filin 直接退出，只有一句
`Error: invalid CLI arguments: Invalid`。二分后确认与 `awdlsink` 无关，是
`-c 52` 被拒：设备上安装的 `libmarionette_awdl.so`（1 946 656 B）只接受
信道 6 / 44 / 149，是早于"接受 DFS 基站信道"修复的旧构建。仓库里的
`app/src/main/jniLibs/arm64-v8a/libmarionette_awdl.so`（1 953 032 B）已修复。

基站落在 DFS 信道时（本项目就是 52），常驻服务会永远起不来，而界面上只显示
一句无法定位的错误。这解释了此前反复出现的
`等待兼容驱动 · Wi-Fi 保持正常，暂不能互传`。**需要重新打包 APK。**

## 2026-10-23 检查点二：host-only sink 打通，常驻接收成立

补丁 0047 在驱动里加了一个纯主机侧 netdev `awdlsink`：`marionette_monitor_post_frame()`
把已识别的 AWDL 帧套 12 字节 radiotap 后 `netif_rx()` 进去，用户态（tcpdump / filin）
直接读这个接口。它的生命周期与 monitor vdev 解耦，只在驱动 shutdown 时销毁。

于是常驻接收不再需要 monitor vdev 常驻，改成占空比：

- 5 轮 × 2 秒窗口（建 vdev → `ip link up` → `capture_hold=1` → 抓 2 s → `hold=0` → 删 vdev）
- `sink_rx` 累计 **40**，`sink_drop` 仅 **2**（`awdlsink` 懒创建时丢掉的首帧）
- **STA 全程在线，`RDDM` 计数不变**（对比：vdev 常驻 9.9 秒后固件必崩）
- `tcpdump -i awdlsink` 抓到 **20 个包，全部 5260 MHz，全部 AWDL action 帧**，
  `a3=00:25:00:ff:94:73`，前缀 `7f 00 17 f2 08 10 03 00`（MIF）

证据：`build/airdrop/driver/sink-20261023/`；细节见 `ROADMAP.md` §26。

一个必须记住的坑：写 `capture_hold` 之前一定要先 `ip link set awdlmon up`，
否则 `marionette_capture_hold_set()` 因 `DEVICE_IFACE_OPENED` 未置位返回 `-ENOTCONN`，
且 dmesg 里连 `MARIONETTE_CAPTURE_HOLD` 都不会打印，表象与"补丁没编进去"一模一样。

## 2026-10-23 检查点一：AWDL 接收首次成立

**本机在保持 Wi-Fi 关联的同时，通过监视 vdev 收到了真实 Apple AWDL 广播帧。**
这是本项目第一次拿到可复核的空口接收证据，也推翻了此前"固件丢弃 AWDL"的推测。

两次独立测量（`candidate-0045`，`sta_monitor=1`，基站 5260 / −42 dBm，Mac 隔空投送前台）：

| 运行 | 时长 | pcap 总包 | pcap 内 AWDL 帧 | Δ`mon_post_awdl` |
|---|---|---|---|---|
| run1 | 20 s | 56 314 | 32（PSF 3 + MIF 29） | +32 |
| run2 | 25 s | 6 625 | 9 | +9 |

pcap 帧数与驱动计数逐次相等；帧为 `addr1` 广播、`addr3` = `00:25:00:ff:94:73`、
前缀 `7f 00 17 f2 08 10`。证据在 `build/airdrop/driver/awdl-rx-20261023/`。

两个推论：

- **不需要跳台到信道 44。** Mac 的 AWDL 同时使用信道 6（2437）和它与手机共享的
  AP 信道 52（5260）。同 AP 时监视端跟随基站信道即可收到。此前"信道 44 上 0 帧"
  的根因是对端不在 44 上，不是固件丢帧。
- **`awdl_rx_wmi` 恒为 0 不是故障。** 它在 STA/mgmt 路径；AWDL 广播走监视 vdev 的
  direct-buffer 路径，由 `mon_post_awdl` / `mon_desc_total` 统计。

当前第一障碍变成**共存稳定性**：监视 vdev 建立后约 20–40 秒被驱动拆除，同时基站掉线；
删掉 vdev 后基站 1 秒内恢复。补丁 0009 的实验性做法需要替换。只证明了接收，
发送、双向发现、IPv6 数据通道与文件互传仍未验证。

> 这一条已由「检查点二」解决：不再要求 vdev 常驻，改 2 秒占空比窗口，
> 帧经 `awdlsink` 交给用户态。

## 2026-10-22 重新审查检查点

本轮在现有 0046 驱动研究之上发现并修复了独立的用户态协议回归，不能继续将所有失败都归因于原厂驱动：

- `announce_template` 把 TLV 4 的 `tx_down_counter`（下一个 EAW 前的 TU 倒计时，正常为 1–64）写成了持续递增的 AW 序号。已有“序号随时间增长”测试也错读了这个字段，因而掩盖错误。现已区分倒计时与序号，并从当前同步状态生成，而非另起一套绝对时钟。
- TLV 4 内嵌信道序列及 TLV 18 仍在复用抓包模板的旧序列；TLV 24 的 sync parent、height、metrics、self counter 也有模板残留。现从同一运行时状态生成，避免广播宣称的调度与实际状态相互矛盾。已据 OWL `frame.h` / `tx.c` 核对布局。
- `NL80211_CMD_FRAME` 的 ACK 解析原先只看每次 recv 的第一条消息；cookie reply 与 ACK 合并时会漏掉 ACK 并空等接收超时。现遍历数据报中的对齐消息并匹配序号。
- radio 脚本现在先检查当前后端必需的能力，部分创建失败也会清理自己创建的接口；不再按端口号杀任何占用 8771 的进程，也不接管其他实验会话的接口。
- firmware 探针增加中断恢复与链路变化判定；“其他管理帧计数增长但没有 AWDL”改为证据不足，不能据此断言固件专门丢弃 AWDL。即使收到 Action 管理帧，也尚不能证明 AWDL 数据/IPv6 接收。

Android ARM64 真机运行 `filin-rs` 257 项测试全部通过，包括生成广播交给接收端后能在一个 EAW 内找到发送窗口、采用外部 master 时同步字段一致、合并 Netlink cookie/ACK 的回归测试。此结果仍不代表无线 AirDrop 成功。测试与构建证据：`build/airdrop/recovery-20261022/`。

检查时手机上另有 `awdl-slot-sweep.sh` 运行，并观测到驱动被独立更换；本轮因此暂停进一步无线修改，等待隔离并行实验，避免互相卸载模块或改变信道。APK 和驱动是否已经部署，以本轮后续实测记录为准。

## 当前结论

2026-10-16 19:13 最新进展（0028–0031后续均已拒绝）：0027修复mission共享RX环的前缀误差（400→实测272字节），Wi-Fi保持关联时，用户空间首次收到15个真实AWDL Action，包含本机Mac，信道6/149；DMA、重组后和AF_PACKET数量一致。此前“mission未收AWDL”的推测已被DMA及用户空间证据推翻。窗口期间网关43/48、最长3.073秒，之后3/3；删除monitor的STOP/DELETE竞态在0028中尝试修复，但导致删除超时后的 `dp_mon_vdev_timer` 崩溃并重启；候选已隔离，不能使用。0031进一步显示 direct-buffer ring 清理链仍会在 recovery 并发时写入失效地址，后续先做静态所有权审计。整帧TLV检查、RAW IPv6发送、真实文件互传、低延迟调度/功耗及system_server集成均未完成。手机在0031失败后自动重启并恢复原厂三模块，正常Wi-Fi、HomeKit与AirDrop常驻服务已确认；实验接口/锁/观察器已移除。证据 `build/airdrop/driver/mission-rx-research/direct-buffer-teardown-audit.json` 与 `build/airdrop/driver/monitor-timer-reset/`；请先看驱动README最新检查点。

已在 Marionette 中加入原生 AirDrop 实验入口及 Android ARM64 后端，但**这台小米 13 当前原厂驱动不能完成 iPhone 无线互传**。不能将协议自测通过当作 AirDrop 真机兼容成功。

2026-10-14 继续推进：已安装独立的 AirDrop 常驻控制服务，支持开机首次解锁/升级后恢复、KernelSU 看护和独立开关。当前状态是「等待兼容驱动」，不启动独占无线实验、不关闭 Wi-Fi、也不宣称可以发现或收取 iPhone 文件。已验证服务被停止后自动拉起、主动关闭后保持关闭，以及后续实际重启后的自动恢复。常驻服务框架不等于内核驱动或 Android system_server 内置服务。

后续真机结果：前三个实验 WLAN 模块能加载，但启用普通 Wi-Fi 时设备重启，均已隔离；原厂驱动热重载对照通过。补齐 `/dev/wlan` 与 runtime PM 仍未解决崩溃。随后通过实时 panic 堆栈及原厂模块反汇编确认 CNSS 输出结构体缺少 129 字节尾字段，导致栈越界。0005 补齐 CNSS / PLD 两端后，2026-10-16 11:28 的修复版首次通过短时真机加载、Wi-Fi 关联和绑定 wlan0 的网关往返（3/3），设备未重启，自动恢复原厂成功。正在继续验证默认关闭的 cfg80211 管理帧实验。**普通联网通过不能代表 AWDL 发射、长期稳定或原生 AirDrop 可用。** 详情和失败哈希见 [驱动文档](../native/airdrop/driver/README.md)。

内置驱动路线已固定 MiCode `fuxi-t-oss` 的 WLAN/vendor kernel 源码和匹配 Clang 14.0.7，现有二十八份研究补丁。CNSS 边界修复后的候选已通过短时普通联网，当前候选 506 项导入 CRC 与真实提供方对应；尚未验证长期稳定性、固件完整支持及空口互传。构建使用 Docker 持久卷。详见 [驱动研究与复现步骤](../native/airdrop/driver/README.md)。

下午的推进：保持 Wi-Fi 关联时，5220 / 2437 MHz 的 AWDL 管理帧请求均得到对应 cookie 的发送完成回调，期间网关 ping 24/24；广播帧回调成功不代表 iPhone 收到。原厂纯接收现场对照在信道 149 / 6 收到真实 AWDL，其中包括本机 Mac；STA 的短时驻留接收则在 WMI 阶段仍为 0。已补齐 LIM 前缀注册、阶段计数并验证扫描接收过滤选项，接下来验证保持 STA 关联的 DP monitor 接收。另修复了 PE 宿主会话数组的连续内存分配失败，并在核对 MLD 内部会话计数。每轮测试后恢复原厂；这些阶段进展不等于 AirDrop 可用。

2026-10-16 17:11 的阶段结果：monitor 可以在 STA 关联时创建，但持续监控会改写共享接收环、使普通联网失效。恢复正常 RXDMA 格式后网关 48/48，但 monitor 无完整帧。现已验证一次性捕获窗口：六个 40/80/160ms 时隙收到 316 个 raw 帧，网关 47/48；进一步用正常社交信道 ROC 的就绪事件触发九个 80ms 窗口，网关 48/48、raw 42 帧，但尚无真实 AWDL。正在核对 radiotap 实际频率；仍缺 AWDL 双向发现、IPv6 收发、后台调度和功耗验收。当前常驻服务仍等待驱动，不启用这些实验。

17:31 历史阶段结果（已由19:13收包证据更新）：radiotap 确认短窗口实际接收频率覆盖 6/44/149，但仍无 action/AWDL；完整过滤分类的 ROC 窗口收到858 raw，最长网关延迟2.1秒。直接 monitor 信道短切也获得924 raw，但仍无AWDL且网关42/48，尚未达到共存目标。紧邻原厂global monitor对照仍收到真实AWDL，包括本机Mac。下一步核对mission固件管理RX配置与转交差异；不把窗口返回成功等同固件支持或真实AirDrop可用。所有实验已回退，当前原厂Wi-Fi与常驻待机正常。

来源：[opendrop-rs](https://github.com/ayourtch-llm/opendrop-rs)，固定版本 `dccc798e244363eb92d35e3c52e9a913188dda91`，GPLv3。源码、许可证及移植说明在 `native/airdrop/`；APK 同时附带许可证。它需要监听模式和真实的 802.11 帧注入能力，仅有 root 或 monitor 标志不够。

## 已验证的范围

- Android ARM64 后端可执行；AWDL 创建 `awdl0`，文件后端以应用 UID 运行。
- 监听信道 44 收到真实外部 AWDL Action 帧；取消默认 −65 dBm 过滤后，状态显示 1 个活跃对端。无法据此确认具体是哪台苹果设备。
- 经 USB ADB 转发到本机 HTTPS，`Discover → Ask → 应用显示接收确认 → 用户接受 → Upload → 保存 → HTTP 200` 完整通过；测试文件内容核对一致。这不是无线互传测试。
- 在手机执行 Rust 单测：AWDL 226 项、文件协议 146 项全部通过；包括路径处理与截断 CPIO 拒绝。
- 接收必须先确认，未批准的 Upload 返回拒绝；传输以应用权限写入私有下载目录，扁平化文件名、避免覆盖、限制请求体和解压体大小。
- 无线调试曾因切换监听模式断开，之后改用 USB 和有退出恢复保护的测试；监听结束后 `con_mode=0`、Wi-Fi 开关恢复为 1。

## 驱动阻断的直接证据

设备：小米 13，Android 13，内核 5.15.74，`kiwi_v2`。读取实际设备模块：

`/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko`

SHA-256：`75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8`

ELF `.rodata` 中普通 `wlan_drv_ops.192` 起点 `0x14a2c8`，偏移 `0x20` 的重定位指向 `hdd_hard_start_xmit`；同一 ABI 的 `wlan_mon_drv_ops` 起点 `0x14a980`，对应偏移处为零且没有发包函数重定位。监听 ops 只见 open、stop、get_stats 回调。实测 AF_PACKET 写入虽返回字节数，网卡 TX 计数持续为零，未收到外部 mDNS 响应，双向发现未成立。

因此当前 APK 的 radio 守护脚本会识别这个**精确模块哈希**，在关闭 Wi-Fi 之前拒绝启动，并显示原因。其他模块仍属于未验证设备，不能视为支持。

进一步工作需要适配具备注入能力的驱动/固件或验证外置 USB 无线网卡。没有修改、刷写或替换手机内核/固件；不能直接拿其他 Qualcomm 芯片的补丁套用。

## 实现和构建

- `airdrop/AirDropActivity.kt`：开关、SAF 选择发送文件、接收确认、文件打开、诊断与许可。
- `AirDropService.kt`：用户启动、最长 10 分钟、前台通知、接收进程和 Wi-Fi 恢复。
- `AirDropResidentService.kt`：独立常驻控制平面，默认关闭；本次已在手机启用，等待兼容驱动而不操作无线接口。BOOT_COMPLETED / MY_PACKAGE_REPLACED 恢复不依赖 HomeKit 开关。
- `kernelsu-module/airdrop-watchdog.sh`：30 秒看护；只恢复已由用户启用的常驻服务，不开启独占模式。已更新手机现有模块脚本，保留原 start.sh 备份于 `/data/adb/marionette-backup/airdrop-resident-20261014/`。
- `assets/airdrop/radio.sh`：root 仅管理无线链路；独立守护程序检测超时、停止标志和应用进程退出。
- `native/airdrop/UPSTREAM.md`：Android ABI、网卡设置及协议修改说明。
- `build-airdrop-backend.sh`：Rust `aarch64-linux-android` + NDK 28；Gradle preBuild 自动执行，打包 ARM64 PIE 可执行文件为 native libraries。

执行 `bash build-airdrop-backend.sh`，再用现有 Gradle 构建 `assembleDebug` / `assembleRelease`。本机 Gradle 路径见 HANDOFF，要求已安装 Rust Android target 与 NDK。

最终 Debug/Release 构建及 Kotlin 单测通过；Release APK 已通过 `adb install -r` 覆盖安装。全量 `lintDebug` 仍有 3 项既有错误：styles 的 API 27 属性、MainActivity 旧版 registerReceiver 分支、Camera 的可选硬件声明；本次网络安全配置的新 lint 错误已修正。未宣称全量 lint 通过。

后续常驻服务这轮验证：Debug/Release 构建、Kotlin 单测通过；当次完整 `lintDebug` 已通过（当前工作区此前的 3 项问题已被修正）。带 AirDrop 看护脚本的 KernelSU ZIP 已重建并通过完整性检查；手机已更新 APK 和看护脚本。驱动补丁只进行隔离编译，未部署。

安装后实际启动 AirDrop，精确模块检查在切换网卡前返回上述驱动原因；确认 `con_mode=0`、Wi-Fi 开关为 1，HomeKit 前台服务仍在运行。

目前实验上限：单次发送 24 MiB、HTTP 接收体 32 MiB、解压后 64 MiB。只面向「所有人」模式，不实现联系人验证。固定信道 44 是当前驱动诊断配置，不代表完整跨信道兼容。

## 恢复和诊断

正常停止会写停止标志，让 root 守护程序杀掉自己的 AWDL 子进程、恢复 `con_mode=0` 和原 Wi-Fi 开关。不要随意杀守护程序或执行没有恢复保护的监听模式命令。

如异常，可经 USB ADB 先检查进程及模式，再针对本实验进程停止和恢复：

```sh
adb shell su -c 'cat /sys/module/kiwi_v2/parameters/con_mode'
adb shell su -c 'ip link set wlan0 down; echo 0 > /sys/module/kiwi_v2/parameters/con_mode; svc wifi enable'
```

应用诊断界面读取 radio/backend.log、transfer.log。接收文件在应用外部私有 `Download/AirDrop` 下，可从应用列表通过只读 ContentProvider 打开。

## 不断开 Wi-Fi 的诊断探针

`bash build-airdrop-probe.sh` 编译 `native/airdrop/tools/coexist-probe.c` 及解析器回归测试。默认仅短时注册 AWDL 管理帧接收；`--transmit` 只尝试一帧最小 MIF，不提供服务、不改变 con_mode 或 Wi-Fi 类型，不生成真实发现握手。所有 Netlink 操作有超时，socket 关闭会撤销接收注册。

本次 `wlan0 --transmit`：REGISTER_AWDL_RX=0；AWDL_MGMT_TX=-107/ENOTCONN，cookie=false、RX=0。未证明实际无线收发。`p2p0` 未启动时为 ENETDOWN；短时拉起后仍为 ENOTCONN，已恢复 DOWN。Wi-Fi 测试前后保持原路由器连接、con_mode=0。解析器截断、非法长度、对齐和 ACK flag 检查在 Android ARM64 上通过。

补充 `wlan0 --offchannel`：对 2437 / 5220 / 5745 MHz 各请求一次 1000 ms remain-on-channel，内核均接受并返回 cookie，测试后仍保持 STA 连接。RX 均为 0；同机 Mac 在此期间启用含 P2P 的 AirDrop DNS-SD 浏览，结束后已停止。**cookie 仅代表请求接受**，未捕获到实际进入目标信道的事件/空口证据，不能宣称发现、数据收发或可用共存。该结果只作为继续开发信道调度入口的线索。

## 检查点四：常驻接收 + 真正同步（2026-10-23）

驱动侧三个补丁把"能收到帧"变成了"能一直收、并且和对端对齐窗口"：

| 补丁 | 作用 |
|---|---|
| 0049 | `capture_hold=0` 真正重置监视滤镜（`dp_mon_filter_reset_mon_mode_1_0`） |
| 0050 | 丢弃固件锁存的 beacon-miss 指示（`marionette_awdl_bmiss_mute`），常驻 vdev 不再把基站弄掉 |
| 0051 | 把描述符 TSF 带进 sink 的 radiotap（24 字节，`present=0x09`） |
| 0052 | 模块卸载时销毁 sink，并回收残留的 `awdlsink`，修掉"每帧都进 sink_drop" |

实测（服务态，10 分钟）：

```
state=listening   sta=1 全程   asserts=0  recov=0   awdl 133 -> 1322
sink_rx 持续增长（428/429、2182 …）
synced=true  master_anchor=6  aw_alignment_pct=92%  peers=4  mdns_rx_other=4
```

`radio.sh` 现在是常驻形态：监视 vdev 只建一次，循环只切 `capture_hold`；
并且必须带 `--no-force-master`（filin 的 force-master 默认开启，会让我们永不
采纳对端主节点，`synced` 永远 false）。注意 `-f` 是 `--no-rssi-filter`，
不是 force-master。

排障提醒：

- 判断接收是否正常，**必须同时看 `marionette_mon_post_awdl` 和
  `marionette_awdl_sink_rx`**；两者相差很大时是 sink 网卡问题（残留/未创建）。
- 驱动断言持续增长时，先看是不是接收端在反复重启（`logcat | grep spawn[RECEIVE]`），
  孤儿接收端占住 5353 会让应用每 20 s 重启整条链路，每轮一条断言。

## 检查点五：附近设备列表接上真数据（2026-10-23）

"实时扫描附近 airdrop 设备"以前是空的：UI 有列表、后端有 `find --watch`，但两者
从来没接到一起（外部进程会把自己也当成附近设备）。现在设备发现做进了接收端：

```
luftlift -i awdl0 receive --progress --browse
```

同一个 mDNS 守护进程既宣告又搜索，事件混在同一条 stdout 流里：

```
{"event":"browsing","duration":0}                       ← 列表从头开始
{"event":"peer","name":"xxx._airdrop._tcp.local.","address":"fe80::…","port":8771}
{"event":"lost","name":"xxx._airdrop._tcp.local."}
```

`peer` 按全名去重（对端每 10 s 重播宣告，不会重复刷行），并按实例名前缀过滤掉
本机自己的宣告。`address` 是链路本地 IPv6，直接拼 `send --address` 就能发。

顺带更正一条旧结论：**卡住孤儿接收端的是 8771（AirDrop HTTPS），不是 5353**。
mdns-sd 设了 SO_REUSEADDR/SO_REUSEPORT，`find` 与接收端可以同时持有 5353
（实测 `find -d 3` 正常返回）。`reapStaleReceiver()` 的修复仍然必要且有效。

排障提醒（新增）：

- 设备列表为空时，先看后端日志有没有 `AirDrop peer resolved`。有解析但没 `peer`
  事件 = 那是我们自己的宣告被自过滤了（正常）；连解析都没有 = 对端没在宣告，
  或者 awdl0 上的 mDNS 收包不通（看 filin `/status` 的 `mdns_rx_other`）。
- 想在没有苹果设备时验证发现通路，用合成对端：
  `fake_peer awdl0 fakemac 120`（`cargo build --example fake_peer`，见
  `native/airdrop/crates/luftlift-rs/examples/fake_peer.rs`）。

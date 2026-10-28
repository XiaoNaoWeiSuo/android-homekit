# Marionette HomeKit 交接与排障手册

更新日期：2026-10-28

## 2026-10-28 App 自包含加载补丁驱动（已实现并真机验收）

**问题**：正常开机是原厂三模块，而 `radio.sh` 硬性要求补丁参数（`marionette_awdl_capture_hold`/`mgmt_tx`/sink/bmiss-mute/`sta_monitor`），KernelSU 模块 `marionette_homekit` 只起 HomeKit/sshd/mihomo、不装驱动，所以 App 点「开启」必然在 `radio.sh` 第 223 行 `fail … 9` 退出，界面显示「当前驱动缺少此实验后端需要的收发接口」。

**实现**（全部在 App 内，无需手工脚本）：
- 新增 assets：`app/src/main/assets/airdrop/marionette-kiwi.ko`（24,141,304B，SHA256 `bbfceb9363a24ac8fdc8887c7fadb67dde87d1f567793d4972e9e34141f9327e`，即 **candidate-0057**，已含 A-MSDU 截断修复）、`marionette-cfg80211.ko`、`driver-load.sh`。
- `driver-load.sh` 是 `native/airdrop/tools/awdl-stack-load.sh` 的上机精简版：幂等（参数已存在直接 exit 0）、卸载前 `svc wifi disable` 静默、任何一步失败都 `restore_stock` 回滚原厂并恢复联网。
- `AirDropService.ensureDriver()` 在 `startRadio` 之前执行：先查 `/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold`，在则快速路直接返回（不解压、不切 Wi‑Fi）；不在则从 assets 解压到 `files/airdrop/driver/` 并以 `su` 跑 `driver-load.sh`，失败把日志抛给界面。

**真机验收（原厂开机 → 直接从 App 开启）**：`install -r` 后 `am start-foreground-service …AirDropService` 走完整路径，`driver-load.log` 输出 `PATCHED_STACK_LOADED link=70:3a:73:a2:26:68`，`radio/state=listening`，界面变绿「实验中 / 停止会话」，状态「本地服务已启动」。停止后 `state=stopped`、`wlan0` 恢复 `10.12.1.48`（模块仍驻留）；再次开启走快速路（`driver-load.log` mtime 不变、无重载）直接 `listening`。**注意**：`marionette-kiwi.ko` 24MB 会进 Git；后续若要可复现构建，应改为从驱动源码产出后校验 sha 再打包。

## 2026-10-28 RX A-MSDU 截断根因（yuni 源码核对，已定位）

`yuni` 经直连 `ssh yuni`（不做 tailscale）可达。**构建身份已核实**：`audit-0056/candidate-0056.ko` 的 SHA256 = `bbb6e00602c6a0ce9c5f5f3222d536117356de957c0a6d63f900c12a7d4ade4e`，与上机 0056 模块 **完全一致** → 该源码树就是上机模块的真实构建。

**分支激活确认**：`wlan/qcacld-3.0/configs/default_defconfig:143` 有 `CONFIG_QCA_WIFI_MONITOR_MODE_NO_MSDU_START_TLV_SUPPORT := y`；`wlan/qcacld-3.0/Kbuild:3325` 据此加 `-DQCA_WIFI_MONITOR_MODE_NO_MSDU_START_TLV_SUPPORT`；构建目录的 `*.o.cmd` 记录里确有该 `-D`。所以 `dp_rx_mon_1.0.h` 的 `#ifdef QCA_WIFI_MONITOR_MODE_NO_MSDU_START_TLV_SUPPORT` 分支**被编译进上机模块**。

**根因**：该分支（`dp/wifi3.0/monitor/1.0/dp_rx_mon_1.0.h:738`）在 continuation 时计算
`frag_len = (RX_MONITOR_BUFFER_SIZE(2048) - rx_pkt_tlv_len - l2_hdr_offset) & (RXDMA_DATA_DMA_BLOCK_SIZE(128) - 1)`。
2048 是 128 的倍数、`DP_RX_MON_RAW_L2_HDR_PAD_BYTE=0`，所以结果 ≈ `(-(tlv)) & 127`，**恒 < 128 字节**，而不是本应约 `2048 - tlv ≈ 2000`。这个 `frag_len` 在 `dp_rx_mon_add_msdu_to_list`（行 552）里作为 RX fragment 长度直接传给 `qdf_nbuf_add_rx_frag(rx_desc_tlv, msdu_curr, tlv, frag_len + l2_hdr_offset, RX_MONITOR_BUFFER_SIZE, false)`。于是**跨多个 monitor buffer 的 A-MSDU/MSDU continuation 每片只交付 <128 字节 → 整帧截断**；单 buffer 帧走 `frag_len = msdu_len` 不受影响，正好解释“小文件传输能过、1310B 大帧间歇截断需 TCP 重传”与 `frame-814788`（136B≈120+头，首子帧声明 1466B）。

**对照**：非 NO_MSDU 分支用 `dp_mon_adjust_frag_len` / `dp_rx_mon_frag_adjust_frag_len`，计算 `RX_MONITOR_BUFFER_SIZE - rx_pkt_tlv_len - l2_hdr_pad`（精确 ~2000），证明 `& 127` 是错的：该去除掩码（取精确长度），或在确需向下对齐 DMA 块时改成 `& ~(128-1)`；当前 `& 127` 取的是“余数”而非“块数×块长”。

**修复（0057，已编译产出）**：已把第 738 行改为精确长度 `RX_MONITOR_BUFFER_SIZE - rx_pkt_tlv_len - *l2_hdr_offset_p`（与正常分支一致，去掉误用的 `& (RXDMA_DATA_DMA_BLOCK_SIZE - 1)`）。在 `wlan/` 树（基线 518147f + 0056 补丁）上增量 rebuild 成功（`driver/build-baseline.sh … modules`，EXIT=0），`kiwi_v2.ko` 522363512B（比 0056 +48B），`clang/bin/llvm-strip --strip-debug` 得 **`driver/candidate-0057.ko` 24,141,304B / SHA256 `bbfceb9363a24ac8fdc8887c7fadb67dde87d1f567793d4972e9e34141f9327e`**（与 0056 同尺寸、不同 sha，符合最小代码改动预期）。**未部署、未真机验证**：下一步把 candidate-0057.ko 推上机、加载后重放触发截断的 A-MSDU（app-native4 `frame-814788` 场景）确认重组完整。需按 audit-0056 流程将其固化为正式 0057 补丁包。

**手机发现苹果设备诊断（0056/0057 真机）**：接收端 `receive --browse` 能 resolve 部分 `_airdrop._tcp` peer（日志 `AirDrop peer resolved 9c49f096c68b @ fe80::685e:b7ff:fe58:2465:8770`），app 也收到 `PEER 9c49f096c68b… @ fe80::685e:b7ff:fe58:2465:8770`（logcat）。但**本机 Mac（awdl `fe80::f82f:d3ff:feae:c4e3` / MAC `fa:2f:d3:ae:c4:e3`）始终没被 resolve**。即发现管线（`--browse` → `peer` 事件 → app 列表）是通的，缺的是**收不到 Mac 的 `_airdrop._tcp` mDNS**。mDNS 帧小（单 buffer），不是 A-MSDU 截断问题（0057 修的是跨 buffer 大帧，对单 buffer 小帧无影响）。下一步：抓 awdlsink/awdlmon 看 Mac 的 mDNS 是否到达、落在哪个 channel/slot，与我们的 ROC 驻留窗口对比，判定是信道/时序还是 Mac 侧未在该信道宣告。candidate-0057.ko 已推上机并 sha 校验一致（`/data/local/tmp/marionette-candidate-0057.ko`）。

## 2026-10-28 环境复查与证据修正（未新增无线实验）

用户要求继续推进。本轮先只读复查环境并复核既有证据，未安装模块、未启动无线实验、未改 APK。

**环境**：手机 USB/ADB 正常（510af65c），root（KernelSU），当前为原厂 `kiwi_v2`，`/sys/module/kiwi_v2/parameters` 无 marionette 参数，无 awdl0/filin/awdlmon，bootreason=reboot，wlan0 10.12.1.48。Mac 本机 en0=10.12.1.63（上一轮记录为 10.12.1.45，本会话地址已变），awdl0=fe80::f82f:d3ff:feae:c4e3。手机侧 `ping -I wlan0 10.12.1.1` 3/3 正常（guard/preflight 跑在手机上，仍有效）；仅 Mac 本机对 10.12.1.1 的 ICMP 不通，与本机网段/路由有关，不影响手机端验收脚本。

**构建机不可达**：`yuni` 10.12.1.67 no route；Tailscale 已停（100.121.129.99 超时）；本机无内核源码。因此 A-MSDU 截断（驱动 RX）的根因排查本轮无法进行。

**离线验证**：`cargo test -p filin-rs --lib` 278 通过；`cargo test -p luftlift-rs` 190+6+1 通过；NDK28 下 `--bin filin --bin luftlift` Android release 编译通过。**但** `native/airdrop/crates/filin-rs/src/bin/awdl-emit.rs` 被 `.gitignore` 的 `bin/` 规则忽略且未提交，导致 `cargo build`/`cargo test --workspace` 默认 target 解析失败（工作树缺该文件）；需在源码树补提交，或用显式 `--bin`/`-p` 绕过。

**证据修正（重要）**：`app-native4-20261027/filin.log` 中目标 Mac `7e:4c:de:a9:3e:54` 的 chanseq 为 `[149,149,149,0,0,0,0,0,6,149,149,0,0,0,0,0]`；我们 ROC 序列为 `[149,149,149,0]` 循环。二者 149 重叠于 slot {0,1,2,9,10}，**已包含发现 EAW slot 0 与 10**，也含数据 slot 2/9/10。故“窗口相位漏掉一个发现时段”对**那台** Mac 不成立。同抓包还有 `[52,0,149,...]` 的其他 peer 序列，若要覆盖它们的 slot 0（52）必须占用 STA 信道发帧，属另一类改动，不能只改广告序列。该轮手机→Mac 失败是 TCP 建连成功后 `Connection reset by peer`，配合 Mac→手机方向 A-MSDU 截断，更像 RX 数据损坏/丢包导致握手中断，而非发送窗口问题。

**2026-10-28 续查（本轮真机）**：本轮在 0056 + 现有 APK 上跑了一次有界接收窗口（见下），选新会话中**本机 Mac 真实序列为 `[52,0,149,0,0,0,0,0,6,0,149,0,0,0,0,0]`**（peer `fa:2f:d3:ae:c4:e3`）。此时我们的 `[149,149,149,0]` 只在 slot {2,10} 与其同频，**确实漏掉 slot 0 发现 EAW**（Mac 在 52，我们在 149）。所以：该 Mac 的发现缺口是真实存在的，但修不能靠再扭 149 相位，而应在 Mac 的 52 时段把 radio 留在 STA 信道（52）收发——这是一类需动 `current_channel`/send 门控的改动，尚未实现。

**本轮真机结果（成功）**：在新会话上跑了一次有界接收窗口（0056 模块 sha `bbb6e0…` + 现有 debug APK，ROC149），用户在 Mac 原生隔空投送发来 `client-call-media-ui-fixes.md`，手机收到且**逐字节一致**：Mac 源 1310 字节 / `ec173ca483fc0a5045037103de5655a6603ff944f3a8c7f0f2c2958673901838`，手机 `…/Download/AirDrop/client-call-media-ui-fixes.md` 同 1310 字节同 SHA；另收到 263 字节 AppleDouble `._` 元数据（正常）。`transfer.log`：`Discover request` → `Ask response prepared sender=焱林的MacBook file_count=1` → `received file … bytes=1310` → `Upload complete count=2`。`dmesg` 无 RDDM/SSR，仅正常 `MARIONETTE_MONITOR_STOP_COMPLETE status=0 recovery=0`。窗口内见到多次 `NL80211_CMD_FRAME send failed err=Io(11)`（EAGAIN，无活动 ROC 时的多播/announce 丢弃）与 peer 频繁清理，未影响本次接收。结束后 `touch stop` 自动回退：`result=restored`、原厂 `g939d76a86982`、无 marionette 参数/awdl0/awdlmon/filin、网关 3/3。

**结论**：Apple 原生 Finder→手机在本会话再次可用（字节级验证）。仍需完成的是：手机→Mac 原生（需手机发起 + Mac 接收）、发现时段在 52 的覆盖改动，以及驱动 RX 根因。

**本轮实现（slot 0 覆盖 home 信道，离线已验证，未部署）**：`roc::sequence(roc, home)` 改为 slot0=`home`(52)/slot1,2=`roc`(149)/slot3=null；`remaining_slot_us` 泛化为“当前信道上连续 slot 的剩余时间”；`RocCapture` 变为 slot 感知三态（home/roc/null），home 时段通过 `MgmtTx::set_home_freq` 允许在 STA 信道直接发帧（不带 OFFCHANNEL_TX_OK），ROC 时段仍受 READY/cookie 门控；`constrain_roc_schedule(roc, home)` 透传 home。这样 discovery EAW slot 0 与 infra 锚定 peer（本机 Mac 的 52）同频，而 slot 2/10 仍走 149 覆盖数据。离线：`filin-rs --lib` **279 通过**（新增 slot0-home 发送判定测试），aarch64 Android release `filin`/`luftlift` 编译通过，clippy 干净。**未打包 APK**：本工作树无 gradlew，且装好的 gradle 9.1.0 已不在（现 8.14 触发 Kotlin 无法解析 JDK `25.0.2`，9.8.1 与 AGP 8.13.1 不兼容），属环境漂移；已装 APK 未变、设备仍原厂。该改动未经真机验证。

**真机确认（部署后）**：用 `gradle-9.1.0-all` + Android Studio JBR(JDK25) 出包（`buildAirDropBackend` 需先把被忽略的 `src/bin/awdl-emit.rs` 从源码树拷回）并 `adb install -r` 后启动 0056 接收窗口：introspect `/status` 显示 `current_channel=52`、**`rebroadcast_counts={"149":29,"52":33}`**——新旧对比此前只有 149，现在手机确实在 home 时段于信道 52 发帧；TX `requests_accepted=281 / unicast_no_ack=0`。说明 slot-0 home 信道发送路径在真机上生效（端到端接收仍需一次 Mac 发送确认）。

**端到端确认（新 build）**：在同一窗口内用户再发 `client-call-media-ui-fixes.md`，手机收到 `1-client-call-media-ui-fixes.md` **1310 字节 / SHA `ec173ca4…3901838`**，与 Mac 源逐字节一致；`transfer.log` `Ask response prepared` → `received file … bytes=1310` → `Upload complete count=2`。即带 slot-0 修复的 build **不回退** Finder→手机接收，且同时具备 52 信道重播能力（上条 `52:33`）。

**2026-10-28 里程碑提交 + 下一步探路**：上述改动已 commit 为 `14f5a65`（本地，未 push），并纳入被 `.gitignore` 的 `bin/` 规则漏掉的 `src/bin/awdl-emit.rs`（构建必需；之前 `buildAirDropBackend` 因缺它失败）。`yuni`：LAN `10.12.1.67` 当前不可达（无 ARP），仅 tailnet `100.121.129.99` 可达但 SSH 走 Tailscale SSH check 模式、需浏览器批准（`https://login.tailscale.com/a/…`），本轮无法自动登录。手机→Mac 原生：用手机现装 `libmarionette_airdrop.so … send --address '[fe80::f82f:…]:8770'` 实测得到 **`Connection refused (111)`**——SYN 已到 Mac、主机主动 RST，证明 AWDL 链路通、但 Mac 侧 8770 无监听（Finder 隔空投送窗口未开）；此前 app-native4 的 `Connection reset` 同属 Mac 侧。开窗后已 `touch stop` 回退：`result=restored`、原厂、网关 3/3。**待办**：Mac 打开 Finder 隔空投送后重发验证手机→Mac；给 `yuni` 一个可用登录方式再查 RX 截断。

**下一步（阻塞点）**：恢复 `yuni` 或提供内核源码以查 A-MSDU 截断根因（截断帧已在 `app-native4-20261027/frame-814788.bin` 归档）；或在用户驱动 Mac 原生 AirDrop 的前提下，用本机 0056 模块 + 最新 APK 做一次有界双向真机测试。四项 AirDrop 稳定验收仍 0/4。

## 2026-10-27 App 原生发送续查：A-MSDU 截断证据

`app-native4-20261027` 已结束并自动回退，不再是下面历史检查点的运行中状态。手机原厂 `g939d76a86982`、boot_count 78，wlan0 网关 3/3。App 手机→本机 Mac 8770 的 TCP 三次握手成功，TLS ClientHello 及双向握手数据可见，最终 Mac reset，发送失败，未证明证书拒绝或文件发送成功。

对照 `build/airdrop/app-native4-20261027/radio.pcap` 和 `tap-live.pcap`：18:59:24.814788 本机 Mac 发往手机的一帧 QoS A-MSDU，抓包记录长度和实际保存长度均 136 字节；首个子帧声明 1466 字节，但剩余数据不足。用真实 `data_rx::decode` 离线重放为 `Payload(TooShort)`，正确拒绝而非向 TAP 注入残缺 IP 包。随后 TCP 只收到乱序的 1669–3047 字节，缺失的 1–1379 字节约 14.1 秒后重传才到达；这与该截断相符，但未证明缺失范围完全属于该帧。既有监视抓包未观察到同一时间完整聚合帧，不足以区分空口、固件、驱动重组或捕获路径的截断。

远端 `yuni` 0056 源码 `qca-wifi-host-cmn/dp/wifi3.0/monitor/1.0/dp_rx_mon_1.0.h` 的 continuation 分支包含 `& (RXDMA_DATA_DMA_BLOCK_SIZE - 1)` 长度运算；远端当前对象的构建命令确有 `QCA_WIFI_MONITOR_MODE_NO_MSDU_START_TLV_SUPPORT`，但还须核对它与上机模块的构建身份，也尚未观察此帧是否使用 continuation。不能据此认定该运算导致截断，更不能直接修改或部署驱动。下一步应核对实际描述符、DMA 长度和重组结果，再以有界测试验证；继续保留有效 peer 准入及自动回退。新增离线回归确保截断 A-MSDU 不交付部分包，不代表无线修复。

## 2026-10-27 ROC突破（进行中）：原生Finder→手机文件成功

本轮用户要求持续做到完整，不要在部分进展时停止。请继续当前工作，不重新询问总体授权。最新用户反馈在Mac隔空投送已看到手机；后续原生发送已由日志/文件验证成功。

- 0056当前合并补丁实际上允许STA+monitor的6/44/149短ROC（≤200ms）。`build/airdrop/roc-20261027/` 被动sweep9次有8个matching READY/cookie并捕获614帧，50AWDL前缀，含本机Mac；正常STOP/原厂网关3/3。旧笔记使用不合条件参数而断言ROC不可用，已被本轮证据取代。
- 新增 `filin-rs/src/roc.rs`：ACK+cookie+接口/频点匹配READY才开TX，EXPIRED/拒绝/超时关闭；有界早到缓存，driver可复用cookie256，严格保持单个预约，开始前drain旧事件。MgmtTx真实发包携带OFFCHANNEL_TX_OK，使用当前有效ROC频率。`FILIN_ROC_CHANNEL`显式启用；RocCapture只在ready时开capture_hold，结束关闭。先200ms预约/200ms回AP，后改为与AW同步的[149,149,0,0]重复，null时段不宣告AWDL接收。实际radio不再与runtime争用filter。6/44亦可选但暂只验证149。
- 149初版UDP手机31/39、Mac19/38；同步时段版24/39和18/39，普通WiFi20/20。TCP双端各发起一次连接，每连接双向8192字节全验证通过，约1.5–2.6秒；`tcp-roc-20261027/*tcp*.txt`。新增 `tools/awdl-tcp-probe.c`，Mac/Android严格编译和真实loopback通过。
- `luftlift` macOS TCP需SO_RECV_ANYIF。新增netutil连接helper（Mac linklocal使用socket2并在connect前设该选项），server listener同样设置。主机/Androidrelease均编译通过，workspace+Android APK/JVM/lint通过。已 `adb install -r` 最新debugAPK，保留HomeKit/配对数据，HomeKit服务重新发布；尚未持久化安装实验模块。
- 两端luftlift真实AWDL/TLS `/Discover /Ask /Upload` 双向8192字节落盘hash都为 `0068407bc613bc7a2ea97998a903dafed291309b10d4145bbeaf9370ab47c287`。`native-roc-20261027/transfer-retry-result.txt`及incoming证明。此前tls-roc第一轮Mac→phone Ask403（测试脚本非原子decision写入），改用与App一致的.answer+mv后通过；手机→Mac第一轮即成功。不能把双luftlift称作Apple原生双向。
- **Apple原生Finder→手机已成功**：`native2-roc-20261027/native-result.txt`：sender焱林的MacBook，AirDrop-Xiaomi13-Test.txt正文95字节，逐字节匹配SHA `d9da9b785468fb2620d5403bec442d23b297381d50909f79400e37dc89244562`。Apple额外发送163字节 `._AirDrop-Xiaomi13-Test.txt` metadata（不是正文损坏）；保留两文件。此前用户还试发imgres.html，测试审批器限定测试文件所以未自动接收。连续90/120秒两轮网关分别90/90、120/120，无RDDM，正常自动回原厂/boot_count78；UDP仍有较高丢包，不能宣布稳定发布。
- 有测试receiver孤儿：timeout父进程被杀留下luftlift22784占8771，已确认路径后清理。后续直接记录接收器PID，TERM等待/KILL兜底；正式App已使用MARIONETTE_CONTROL_STDIN管道绑定生命周期，不同于这个测试包装错误。
- `radio.sh`现默认ROC149，`MARIONETTE_AWDL_ROC_CHANNEL=0`回旧固定STA实验；APP已含此脚本，正在准备在真正App中验证。驱动仍需实验loader，不是开机即用成品。无AP、iPhone原生、长时功耗/完整SSR等未完成。

**历史动作**：`build/airdrop/app-native-20261027/supervise.py` 及后续几轮已结束。Mac本机原生_AirDrop实例 `0932a6efcd28`，host `26f84f8e-cd0d-4bdf-9fef-c00ba95a4c8e.local.`，当时 AWDL `fe80::7c4c:deff:fea9:3e54`，端口8770；地址和发现状态在续测前需重新核对。证据 `build/airdrop/native-send-20261027/resolved.json`。

App测试结束前必须通过am ACTION_STOP等monitor消失再回原厂（guard已处理）。本轮尚未将全部检查点做最终汇总；继续工作。构建机SSH `yuni`=10.12.1.67可达，Tailscale100.121.129.99暂不可达。远端源码仍518147fd带0056修改，未新增驱动补丁。本机sudo不可免密，不要假称能抓Macraw无线包；手机额外awdlsink/awdlmon/TAP三抓包已有。

## 2026-10-27 续测：首次双向 UDP 往返，修复首次选主同步顺序

真机重新连接后进行4轮20秒UDP基础测试，独立radio最长35秒，保留host实时kmsg、logcat、原始和TAP两份pcap、双端ready证据、status/peers快照与自动guard。没有启动手机AirDrop Discover/Ask/Upload，也未升级APK。总表 `build/airdrop/ip-probe/continued-20261027.json`；各目录 `summary.json` 可核对计数。使用0056模块，前三轮为queue-fix filin `714cd2df…`，第四轮为新sync-fix `72efa90833c445658fa01775d494e02509bf25066f50c84222af601b39c9f7cc`。

| 轮次/目录 | 条件 | 手机请求收到回复 | Mac请求收到回复 | 本机Mac无线证据 |
| --- | --- | --- | --- | --- |
| queue-20261027 | 双端ready，原2秒/4秒窗口 | 0/40 | 0/40 | 无Mac Action/data |
| finder-20261027 | 打开Mac隔空投送窗口，同窗口参数 | 0/40 | 2/39 | 2 MIF/2 data，序列有52；两端各收到2个请求 |
| short-window-20261027 | 200ms/400ms窗口 | **5/40** | **3/39** | 16 MIF/8 data，Mac增加52时段；Mac收到16个手机请求、手机收到3个Mac请求 |
| sync-20261027 | 同200ms/400ms，新同步代码 | 0/40 | 0/40 | 仅1 PSF/0 data，Mac序列又无52；不能用此轮评价同步修复的无线收益 |

第三轮首次证实双方发起的真实UDP往返。手机已收到的回复均值31ms/最大97ms，Mac均值260ms/最大275ms；这些只统计成功包，绝不代表全链路时延/成功率已达标。短窗口与Mac自身availability变化同时发生，不能把改善全部归因于窗口长度；生产radio默认仍保持原值。最后一轮零往返必须保留，不能只报告最佳轮。AirDrop稳定四项验收仍0/4。

**队列和探针已获真机证据**：四轮两端均先ready后started，未再出现探针启动前的端口不可达。第二轮持续入队/发送42个单播；第四轮无共同信道时入队19、过期18，广播仍继续（末次snapshot rebroadcast=49），没有第一帧阻塞整个TAP。第一/三轮缺Mac peer时UDP可能停在内核邻居解析，send()成功不等于已入filin/已无线发送。

**同步代码修复**：apply_action原先在本帧完成peer有效性和选主之前尝试同步，首个选中parent的MIF时钟被忽略。现先更新有效性/选主，再仅从新选中parent接受本帧时钟；倒计时超过当前EAW长度的同步字段被忽略，避免减法越界。`last_sync_error_tu`每次apply清空，仅实际接受的同步样本计入alignment；`synced`现在要求当前cluster确实收到过时钟，不再把仅选出master当作同步。根master改变/清理回self时旧同步资格失效；同cluster重选parent不假装重新测得时钟。它仍不是实时漂移或可达性保证。

新增2项状态回归，修正1项原本错误的status测试；实际finder pcap离线重放首次同步从5275811us提前到0us（相对抓包首帧），样本4→7；这是解析/状态重放，不是空口性能提升证明。完整Rust workspace报告470项通过（含既有条件/辅助项），Android release和all-targets clippy通过，日志在 `short-window-20261027/*sync*`。新binary归档于 `sync-20261027/filin-sync-fix`，已独立上机但无成功UDP结果，不部署到常驻APK。

**测试前置修正**：第二轮loader刚关联但IPv4未完成，原脚本仅检查ping退出码，把2/3继续放行；保留该事实，不能称该轮前置3/3。第三、四轮先等IPv4，再严格grep三收零丢，否则中止。四轮结束均自动回原厂且网关3/3；四次STOP均status=0/recovery=0，正常STOP历史累计10次。四个短窗口未出现RDDM/Oops，但不能覆盖此前约48秒固件断言或长期共存风险。

末次仍可连接，独立复查原厂g939d76a86982、boot_count78、scan-always=1、IPv4 10.12.1.48、网关3/3，无awdlmon/awdlsink/awdl0或filin进程/stack锁。证据 `final-continued-20261027.txt`。Mac隔空投送窗口本轮已打开。

下一步仍停在基础链路：稳定Mac有效MIF捕获和共同信道，核对availability与实际RX窗口/单播ACK时间；检查固定STA信道方案能否支持Mac离开52的状态，不放开未知peer/不忽略信道gate。固件line#1413根因和长时共存仍待解决，暂不开始TLS/文件传输验收。

## 2026-10-27 基础链路：确认 TX 全局阻塞，保留固件 RDDM 现场

ADB 恢复后按既定分层顺序运行了一轮独立 UDP 探针，未启动 AirDrop Discover/Ask/Upload、未安装 APK。手机开机计数78，原厂网关前置3/3；部署的是已记录的0056模块和上一轮60秒peer租约filin（SHA `15a8dbb3…`）。Mac 当前 AWDL 地址变为 `7e:4c:de:a9:3e:54`，探针使用重新读取的 IPv6。证据 `build/airdrop/ip-probe/lease-20261027/`，原始pcap及 `link-audit.json`、kernel/system日志、TAP日志、两端探针结果均保留。

**实际结果与根因边界**：原始抓包114 Action、2 data；本机Mac仅1个MIF，晚于两包UDP约20ms。已有pending_rx在真机释放两包到TAP（约20.2ms），但手机探针尚未绑定39921，内核回ICMP端口不可达；不是UDP应用接收成功。Mac MIF序列为 `[149,149,149,0,0,0,0,0,6,149,149,0,0,0,0,0]`，无手机固定信道52。peer租约保住了Mac，但第一帧单播（端口不可达回复）一直Hold；原实现因此关闭整个TAP的POLLIN，后续UDP/广播也读不到。runtime只有1次buffered、0次data injected、TX_STATUS无单播提交。双方探针0请求/0回复；手机97提交/23发送错误，Mac105提交/13发送错误，含后述故障及启动阶段，不能作稳态丢包率。

**固件故障**：启动探针约48秒后出现RDDM，SFR `cmnos_thread_patch.c:1170:0x8Asserted in:0x1366428:0x1c34a98, line#1413`，随后SSR回收monitor/awdlsink，未整机重启。该SFR在旧 `build/airdrop/driver/mld-owner/capture-follow/kernel-001.log` 也出现过；与0037–0040 AF_PACKET注入的line#2834不同，但相同字符串不证明相同根因。此轮没有为故障归因，也没有继续重复无线配置。自动guard正常回原厂、网关3/3、scan-always=1、boot_count78，无实验接口。正常STOP的历史6次通过不能覆盖这次运行期固件故障；0056不能视作稳定候选。

**本轮实现**：新增 `pending_tx.rs` 并接入实际runtime：持续读取TAP，单播按对端保序等待；全局32包/64KiB、每对端8包、2个hop周期（约2.10秒）过期，满队列拒绝新包，重试不续期。一次drain超过2ms不再启动后续Netlink发送，避免把单次阻塞放大到整个队列；单次Netlink原有超时仍存在。保持原有信道/peer gate，不通过强行向不共同的信道发送来伪造链路可用。4项回归覆盖实测Mac序列造成的阻塞、其他对端可发送、每对端顺序、过期/容量/删除。该修复尚未上机验证，不解决缺共同信道及固件断言。

探针增加 `AWDL_IP_PROBE_START_FILE`：先bind输出ready，等两端均ready后再创建各自全新gate；10秒等待上限、开启后开始计时。增加2项真实loopback/未开启gate超时回归，后续guard预算须包含这10秒；详见 `tools/BASE_LINK_TESTING.md`。避免启动先后顺序浪费仅有的接收窗口。

验证：Rust workspace、Android filin release构建及Android all-targets clippy通过；探针Mac/Android严格告警编译通过；新增4项TX回归和2项探针测试通过，最终产物及hash见本轮 `filin-queue-fix` / `build-report.json`。未部署修复后的二进制。末次只读复查ADB列表为空，不推断原因；原厂/boot_count78/网关3/3是此前guard结束时的证据，断开后无法再核对。下一步先定位line#1413触发条件和共享RX/STA共存路径，再解决与Mac的共同信道/同步窗口；保留有效Action准入。稳定UDP、Apple无线TLS/双向文件、无AP及长期后台仍未验收，四项AirDrop稳定验收仍0/4。

## 2026-10-26 基础分层验证：正常 STOP 连续通过，UDP 往返仍未通

用户澄清：不是禁止真机测试，要先夯实基础通信再做协议层。后续允许带明确通过标准的驱动/帧/IP测试。本轮未启动AirDrop接收服务、TLS或文件传输；使用0056固定模块和独立radio/UDP探针。结果目录 `build/airdrop/driver/lifecycle-20261026/`、`build/airdrop/ip-probe/`，机器可读总表 `ip-probe/progress.json`。

**已验证的正常关闭路径**：一次monitor创建关闭成功，随后同一次加载下连续三次创建→启用→关闭→删除成功（第三次含200ms被动捕获），再加两轮基础UDP测试退出，共6次真实STOP成功完成。STOP完成约7.6–14.8ms，普通接口关闭约80–90ms。内核先记录STOP_BEFORE_DELETE，后STOP_COMPLETE status=0 recovery=0，再DELETE响应；六次无旧DELETE-before-STOP告警、无Oops/整机重启。不是SSR故障/长期稳定全部通过：仍见原有DP peer状态诊断，队列分配失败/关闭交错所有权未完成。

**加载器根因与修复**：初次与一次重复测试在联网前置阶段失败，均未创建monitor。仅等待wificond新PID不够：明确日志 `WifiSelfRecovery: Already restarted wifi 2 times in last 1 hour. Disabling wifi`，daemon death的延迟处理会关掉刚启用的接口。现在临时关闭scan-always（保存并恢复原值），等NumActiveModeManagers=0；wificond新PID后再次保持关闭，等待12次连续quiet检查覆盖本机约2秒延迟，再启用。普通reconcile不再重复restart wificond。修订后连续三次加载/退出（repeat和两轮UDP）完成，最终原厂联网恢复。不能据单轮修复推断所有HAL/framework故障已解决。10项loader测试+4项route/hotspot测试通过。

**基础UDP结果**：新增 `native/airdrop/tools/awdl-ip-probe.c`，双端固定64字节、明确对端地址/端口、源标记和序号；Mac设置SO_RECV_ANYIF和IPV6_BOUND_IF，手机绑定awdl0。Mac/Android均编译通过，本机loopback双端各6/6。第一轮手机提交130请求，Mac收到11；Mac提交134请求，手机收到1；双方echo reply都是0（末尾Mac比手机多运行数秒产生4次No route，不当作稳态丢包）。证据是双向零星请求可达，**没有成功UDP往返，更没有稳定IP链路**。Mac与手机实际基础Wi-Fi信道均52。

**已复现的数据早于Action丢包**：第一轮原始pcap中Mac UDP先于Action约23/31ms，runtime因未知peer丢弃3帧；之后10秒peer移除又使TX丢弃。新增 `pending_rx.rs`，仅对data_rx已校验且含本机单播的delivery暂存，16包/64KiB/250ms上限；不创建peer，仅Action确认有效peer后释放，过期/无peer不放行。接入真实runtime统一TAP交付路径，4项回归通过。新增 `examples/pcap-peer-replay.rs` 用真实RX/peer/queue代码离线重放：原始3帧在23452/31544/31428us后均能释放。第二次60秒真机对照未捕获Mac Action，仅两帧Mac data，queue按设计超时，双方0接收/0往返；不能称无线改善。该修复只覆盖已证实的先后顺序，不解决Action间歇可见、peer超时和无线时序。

验证：Rust workspace报告463项（含条件跳过/辅助项），Android filin release构建和clippy通过，14项shell测试通过。新filin只放到独立测试目录，本轮未升级APK。最后两轮guard正常回原厂，最终scmversion g939d76a86982、boot_count78、scan-always=1、网关3/3。首次单次生命周期回退ping过早遇DHCP未完成为2/3，已保留事实，后续guard等IPv4并严格核对3/3。

末次只读补查时ADB列表为空（`build/airdrop/ip-probe/final-state.txt`），不推断拔线或重启原因；原厂、boot_count78与网关3/3是此前guard结束时已记录的状态，末次连接消失后无法再核对。

离线新增peer租约修复：固定信道模式的peer超时从10秒改为60秒（普通跳频保持2秒），因为真实Mac两次完整MIF间隔约48秒；增加fixed_channel_keeps_sparse_apple_peer_for_sixty_seconds回归。新filin Android release已构建，SHA256 `15a8dbb318599fd43a4abcc0cdd88faca1c52acbc79f222f002d606bc30f8148`，但开始下一轮前ADB再次消失，未部署/未验证无线效果。 全量Rust workspace现262+6+189+6+1项测试通过，filin Android clippy通过；此前旧fixed-channel测试已同步改为61秒删除阈值。

下一步停留在基础通信：查Mac Action间歇可见/捕获与发送时间窗、peer生命周期和广播同步一致性；沿用独立UDP探针及原始/TAP双抓包，不启动Discover/Ask/Upload。driver正常close已有证据，但完整SSR/消息队列错误清理仍需补。四项AirDrop稳定验收仍0/4。

## 2026-10-26 用户要求先修底层：0055/0056 离线驱动候选

用户明确要求停止着急上机测试，先把底层链路和驱动补丁做完整。本轮没有 ADB/无线测试、没有安装模块或APK。遵循这一方向，先修停止/删除/定时器以及加载回退边界；尚未声称通信稳定或完整驱动完成。

新增0055：在SME逻辑删除之前异步STOP，保留原有SME/WMA引用；接入monitor STOP响应和pending START响应，包含失败/超时状态；DOWN回调只标记READY，在外层SM调用返回后恢复删除，避免锁内执行SME。用IDLE/WAIT/READY/RESUMED原子状态限制继续删除一次；WMA detach不再重发STOP；monitor PE session留到停止完成；HDD等待预算包含START/STOP。分支限定STA-monitor实验和SERIALIZE_VDEV_RESP构建。STOP超时增加内部响应status字段，不能冒充固件成功。逻辑删除前先分配self-peer删除参数，避免分配失败留下已逻辑删除对象。

新增0056：DP timer起停/回调重挂共用锁；先清RUNNING再锁外sync_cancel，禁止停止过程中重启；并发停止均等待回调，deinit关闭入口后释放；未初始化/重复deinit不碰已销毁锁。实际C函数由check-monitor-stop.py/check-monitor-timer.py提取编译，ASan/UBSan故障注入通过。不是内核完整调度/并发模拟。

候选全模块编译通过（保留CFI/LTO、SERIALIZE_VDEV_RESP），SHA256 `bbb6e00602c6a0ce9c5f5f3222d536117356de957c0a6d63f900c12a7d4ade4e`，512/512导入CRC匹配。源码基线518147fd，内核7fad87dc，clang r450784e；缺vmlinux/BTF、浅克隆版本脚本和根Module.symvers警告仍在。远端 `~/workspace/xiaomi13-airdrop/audit-0056/`，本地 `build/airdrop/driver/candidate-0056/`。新增 `native/airdrop/driver/stack-0056/` 合并补丁+44文件hash+apply.py，从干净固定输入应用后逐文件匹配；合并包替代历史编号补丁重放，不能与编号系列叠加。包含5个原先未跟踪的平台头文件；仅WLAN，不包含独立cfg80211模块补丁。未部署。

加载器修正：明确指定WLAN候选（不再默认旧0046）；load/stock共用锁；观察Wi-Fi关闭与wificond新PID/running后再启用；卸载失败即停止，不在旧模块上叠加insmod；候选加载/联网失败在同一锁内恢复原厂。7项新shell故障测试+4项既有路由/热点测试通过。还没有真机验证框架同步效果。后续上机前必须重新计算guardian时限，旧150秒预算不覆盖完整失败+回退最坏路径，不能直接复用旧脚本。

剩余重点：scheduler消息分配/入队失败与shutdown flush交错的引用所有权、SSR与pre-delete continuation同时发生的完整清理尚待审查。host shim只验证列举的分支，不证明这些系统级竞态；候选保留UNVALIDATED RUNTIME标记。继续源代码与离线故障验证，不恢复试跑。通信层还缺peer/action时间与数据准入/调度、捕获窗口与STA共存、无AP链路；生命周期修复不是这些问题已解决的证明。

## 2026-10-26 真机恢复测试：mDNS hop-limit 与错误配对服务宣告

用户重新接入 USB 真机并授权继续；Mac 隔空投送正常且“所有人”。手机原厂、boot_count=78、LIMO-5G ceshi/5260MHz/10.12.1.48，Mac 10.12.1.45，前置网关3/3。构建机 yuni 的局域网 SSH 恢复，0054 已取回校验 SHA256 `80680eae…`。保留数据 install-r 更新 APK。证据 `build/airdrop/device-20261026/runtime.json` 及各轮目录。

三次有界尝试（guardian 150 秒）：第一、二轮实际启动0054；第三轮 STACK_LOADED_NO_LINK，未启动接收服务。第一轮实际单播TX_STATUS报告6 ACK/3未ACK，未按目标MAC归属，不能写成本机Mac接收确认。仍有STOP/DELETE警告，无整机重启。第一轮最初电脑日志子进程因父shell结束退出，随后补live reader和dmesg快照；第二、三轮由持续存活的控制进程持有日志reader。不要宣称三轮都有完整起始实时日志。

已修复并验证：
- mdns-sd 0.11.5没有设置发送TTL/hop-limit，第一轮实际IPv6 mDNS响应为1。固定上游副本放入 `native/airdrop/third_party/mdns-sd`，仅改接口socket的IPv4/IPv6组播与单播hop-limit为255，错误上抛。独立socket选项回归通过；第二轮实际AirDrop PTR/SRV/TXT/AAAA响应抓包为255。不是稳定发现已解决的证明。
- 第二轮原始177帧中171 action、6 data；六帧都通过实际RX解析器。两帧组播mDNS送入TAP；四帧来自本机Mac `4e:2f:a9:4e:a0:4f` 的QoS SYN发往手机 **53870**，当时Mac不在peer表，未进TAP。这不是8771的握手证据。MIF模板TLV2恰保留了 `_appsvcprepair:53870` / `_applicationservicepairing:53869` 和AirDrop TXT；重命名并未实现对应服务。新增 `build_link_announcement`，实际runtime/awdl-emit去掉这些TLV2，保留原始模板研究函数。生产入口及保留其他TLV回归通过；新APK已装，但第三轮前置联网失败，**此修正无线效果未验收**。
- 新增 `filin-rs/examples/rx-audit.rs` 可用真实接收解析器重放radiotap二进制。pcap分析脚本原来把addr1广播误标成a2，已改为真实source偏移10。

测试工具纠正：Mac dns-sd必须加 `-includeAWDL`；只有IncludeP2P的空列表无效。原curl没有Darwin SO_RECV_ANYIF，单独超时不能证明Apple原生也不通。第三轮准备了按OpenDrop设置0x1104的Python探针，但因前置失败未执行。Mac sharingd日志有8771 Discover/SYN重试，但主机名被隐私化，不能仅凭此唯一归属手机。`-includeAWDL`实际查到两个其他服务，未完成手机新实例稳定发现。当前Mac的新AirDrop还用application-service路径，没看到传统TCP监听不等于未开启。

回退：第一轮原厂自动恢复3/3；第二轮原厂已加载但框架Wi-Fi关闭，人工有界svc关开+重启wificond后3/3；第三轮自动原厂回退最终3/3。最终原厂scmversion `g939d76a86982`、boot_count仍78，普通Wi-Fi可用、AirDrop测试停止。勿把第二轮自动回退写成完全通过。未删除HomeKit/配对数据。

验证：Rust workspace报告459项（含条件跳过及辅助项），mdns接口socket选项测试、Android目标filin clippy、Debug/JVM/lint通过。仍是0/4稳定验收，尚无Apple无线TLS/文件成功。下一步优先修复加载/回退框架同步、STOP真实回调/删除屏障，然后对去除错误TLV2的新APK做带IncludeAWDL和SO_RECV_ANYIF的单轮验证；若研究peer数据准入，必须区分data可达、有效action同步/信道信息与TCP服务是否实际存在，不要直接放行所有未知peer。

## 2026-10-24 恢复远端构建，0054 完整编译通过

用户提供构建机地址后，Tailscale 节点表确认实际是 `lin@100.121.129.99`（用户发的 `.9` 超时且无对应节点）。已成功访问 `/home/lin/workspace/xiaomi13-airdrop`，WLAN HEAD 仍为 518147fd，0053 已归档产物哈希与记录一致。未修改 SSH 全局配置。构建机没有额外 AGENTS.md，源码树保留既有改动。

0054 已在真实 0053 树应用并完成完整模块编译，保留 CFI/LTO；512/512 导入 CRC 与 provider 表匹配，真实构建源码的 ASan/UBSan 边界回归通过。候选 SHA256 `80680eaec756687e3a47464fd92b78e2749f68b84335d32821f074a440fc4855`。远端 `audit-0054/` 保留前置源码、全树差异、构建日志与候选，本地证据 `build/airdrop/driver/candidate-0054/`。沿用已知构建限制：浅克隆版本脚本报错、根 Module.symvers 缺失但传入实际 provider 表、缺 vmlinux 而未生成模块 BTF；不声称可加载性/完整 ABI 已验证。没有部署或无线测试。

新的关键退出证据：实际 `wma_utils.c:4712` 的 monitor stop 函数发 STOP 后立即递交 `MLME_DOWN_REQ`，down 函数立即递交 `DOWN_COMPLETE`，二者在发送失败时也继续；monitor ops 没有 ext_stop_rsp/stop_continue/notify_down_complete。故 INIT 不表示固件 STOP 已响应，0028 加在 WMA stop handler 的等待收尾也没接上 monitor 回调链。需在逻辑删除前补齐真实响应派发及完成/失败通知，先审计 DP timer/SSR 清理，不能直接恢复 0028 或仅加状态等待。0054 不修复该顺序问题。路线图顶部已纠正旧“删除已安全”结论；完整 AirDrop 目标仍未验收。

产物位置注意：当前 DERP 链路的大文件传输多次未完成/超时，本地不完整模块和归档已清理。完整候选仍在远端 `audit-0054/candidate-0054.ko`；本地 `build.json` / `ARTIFACT.txt` 明确记录位置，CRC 校验直接在远端完整模块执行。构建日志、实际源码、回归结果及选定反汇编已取回。后续取回必须重新核对上述 SHA256。

## 2026-10-24 Monitor 退出审计和 0054 边界补丁（离线）

固定源码确认：HDD monitor close 的 hdd_monitor_mode_vdev_status 仅等待 UP，不等待 STOP；随后逻辑删除，WMA 异步发 DOWN/STOP 后立即 detach DP/释放引用，触发 DELETE-before-STOP。STOP target-if 先取消响应计时器再取状态检查引用，提前 DELETE 会妨碍迟到响应。需要在逻辑删除前完成异步停止屏障及超时/SSR 处理，不能重用已拒绝 0028 延迟 WMA 引用方案。详细契约 docs/AIRDROP_RESEARCH.md，机器可读审计 build/airdrop/monitor-stop-audit/audit.json。

新增 0054-validate-vdev-response-boundaries.patch：修复 delete 的 id==max_bssid 越界；stop 回调先检查空值/ID/对象身份，再分发。新增 check-vdev-response-boundaries.py 使用原始回调函数的 host shim，ASan 复现修改前越界，修改后 ASan/UBSan 用例通过，补丁 apply check 通过。未修改 STOP/DELETE 顺序、未完整编译驱动、未部署；远端构建主机上次检查不可达且用户暂不无线测试。本轮不将 host shim 结果描述成驱动稳定性验收。目标仍未完成。

## 2026-10-24 上游 TLS/Ask 兼容性修正（离线）

核对 OpenDrop config.py/client.py/server.py 并保存原文哈希（build/airdrop/community-research/tls-ask-source-snapshot.json）。本地发送端原先禁用客户端证书；现生成一份自签名进程身份，响应对端请求，整次传输共享配置。TLS1.2/TLS1.3 要求证书、匿名拒绝及无请求不发送三项握手回归通过。签名算法列表改取实际 ring provider；未读取系统钥匙串，也不提供联系人身份认证。

Ask SenderID 从 plist Data 改为上游的十六进制字符串，修正旧测试的无证据类型假设；文件请求去掉空 Items，补 FileIsDirectory=false / ConvertMediaFormats=false。已有真实 loopback TLS文件测试继续通过。workspace报告458项，Android Debug/JVM单测/lint通过；日志 build/airdrop/apple-wire-*。未部署/未无线测试；不把 TLS 差异当作此前 TCP 未通的根因。下一步仍需解决真实无线握手、无AP、驱动退出与共存，并以 Apple 文件互传验收。

## 2026-10-24 TLS 文件协议与批准消费修复（离线）

真实 loopback TLS 检查发现：同连接 approved 标志绕过共享批准消费，可让另一连接复用已用批准；新 Ask 拒绝不会自动撤销旧授权；handler 未校验 POST，仅按路径可处理 GET/PUT Upload。现 Upload 一律消费共享限时批准，新 Ask 首先撤销旧批准，非 POST 在 100 Continue/正文前 405，TLS 入口与 HTTP parser 共用严格请求行解析。

新增 server_tls_tests.rs：生产 TLS/审批文件/落盘链路，覆盖同连接分块上传等待100、跨连接每阶段重新连接、接受一次仅上传一次、拒绝后旧批准失效、批准过期、非 POST 拒绝、重复文件名保留旧文件。字节内容一致。测试只绑定127.0.0.1随机端口，无 AWDL/mDNS/设备无线操作。原有批准测试共用 test-only 环境锁，避免测试目录串扰。

4 项 TLS 测试 + 请求行回归通过；workspace 报告455项（含条件跳过/辅助项）、Debug APK/JVM单测/lintDebug通过。日志 build/airdrop/tls-consent-tests.txt、tls-consent-build.log。未部署。目标仍缺 Apple 真机 TLS/双向文件、无AP、驱动退出/ACK稳定性、后台与共存验收；回环成功不代表无线可用。

## 2026-10-24 发现列表与 mDNS 重播生命周期（离线）

修复 browsing/peer 顺序竞态：以前浏览线程可先发缓存 peer，主线程随后发 browsing 清空 UI；去重又使 peer 不再出现。现在同一线程先发 browsing 再消费事件。断开的浏览通道不再忙循环，退出发送已知 peer 的 lost，接收 supervisor 观察线程结束重建会话。地址集合排序变化不再产生伪更新。3 项无网络事件回归覆盖这些路径。

新增 filin-rs/src/mdns_cache.rs：本机查询不覆盖正向响应缓存，TTL=0 goodbye/不完整响应清缓存，仅完整成功响应进入重播；持续时间受最短记录 TTL 和 45 秒上限约束。原始报文发送不变，不重写报文内 TTL，仍是单报文缓存。5 项回归含完整编码解析与缓存状态迁移。未宣称已解决现场所有 mDNS/Finder 抖动。

验证：workspace 报告 450 项通过（含条件跳过网络测试/辅助项），Android target filin clippy、Debug 构建/JVM 单测通过，git diff --check 通过。证据 build/airdrop/discovery-lifecycle-*。遵照用户要求未部署/未无线测试；真实双向文件、无 AP 驱动、STOP/DELETE 和长期共存仍未完成。

## 2026-10-24 RX 准入与组播误锁定修复（离线）

发现 data RX 对所有帧调用 note_unicast_rx，组播 mDNS 也锁定传输信道；监视接口收到的其他目的地址单播/A-MSDU 子帧未在 TAP 入口过滤。新增 filin-rs/src/data_rx.rs 并接入真实 RX：AWDL BSSID/帧类型/三地址布局/完整 LLC OUI 校验，过滤本机反射及非本机单播，聚合逐子帧过滤，仅本机单播更新 transfer pin。未实现的分片/加密/扩展头明确拒绝。Action RX 在修改 TSFT 前也校验 MAC envelope。

新增 6 项回归，包括混合 A-MSDU、QoS/Retry/FCS 正例与截断/错误 OUI/其他 BSS 负例。filin 251 项、workspace 报告 442 项（含条件跳过/辅助项），host/Android clippy、Debug 构建和 JVM 测试通过。证据 build/airdrop/rx-admission-*。未部署、未无线测试。不能将此当作 Finder 服务抖动的已证实根因；mdns_rx_self 含 TAP 出站统计，不能据该计数证明无线反射。

## 2026-10-24 TX_STATUS 接入（仅离线验证）

新增 filin-rs/src/tx_status.rs 并接入 Linux/Android 实际 MgmtTx：动态 mlme 订阅、完整 Netlink 数据报解析、当前单播 cookie + ifindex 归属、乱序/重复处理、5 秒状态超时、128 项缓存上限。主循环非阻塞处理迟到事件；请求 ACK 使用同一 3 秒截止时间，避免事件洪流不断延长等待。/status 新增 tx_status，区分内核接受/明确单播 ACK/明确未 ACK/状态或 cookie 缺失，订阅失败明确显示，不把广播计为对端确认。详情 docs/AIRDROP_RESEARCH.md。

新增 8 项 parser/tracker 测试及 1 项 JSON 诊断测试；删除旧 frame_ack 镜像解析器和测试，生产路径直接使用新模块。filin host tests 245、Android target tests check 与 Android target clippy 通过，workspace 报告 436 项（含网络条件跳过和辅助项），Debug APK 构建与 JVM 测试通过。最后验证另修复 Android c_char 字节转换的 clippy 警告；tracker 分配在 Box 内避免膨胀发送后端枚举。

未部署、未无线测试、未验证真实 TX_STATUS 到达；不等同文件互传。原 P2P 无 AP 限制、monitor STOP/DELETE、真实 TLS/双向文件/长期共存目标仍未完成。构建和测试日志 build/airdrop/p2p-audit/tx-status-*。

## 2026-10-24 P2P 源码审计与单播 ACK 请求修复

继续遵守用户暂不无线测试的指示，未操作手机无线/驱动。依据固定 MiCode 源码确认：P2P_DEVICE 非 public action 被 cfg80211 拒绝；0043 data 例外仅放行 STA；HDD ROC 在 STA+monitor concurrency 下主动返回 EINVAL。无 AP P2P 路径不能通过改接口名或删除关联检查完成，接收数据通路仍缺证据。详细表在 docs/AIRDROP_RESEARCH.md，原文/哈希 build/airdrop/p2p-audit/。

新增 filin-rs/src/frame_tx.rs，Linux/Android 的 NL80211_CMD_FRAME 直接使用此编码器：组地址写 DONT_WAIT_FOR_ACK，单播省略，让驱动保留 OTA completion 路径；以前所有帧都强制 no_ack，只选 download completion。软件盲重复发送仅留在 AF_PACKET 后端。Netlink ACK 仍不代表对端接收，本轮未增加 TX_STATUS cookie 跟踪，未实测无线重传/性能。新测试验证实际编码字节、对齐、接口/频率/长度和广播/单播区分。

Rust workspace 报告 427 项通过（包括条件跳过的网络测试/辅助子进程项，不等于 427 项真机测试），filin clippy 与 Android target tests check 通过，Android Debug 构建和 JVM 单测通过。产物仅在本地，未部署本轮单播 ACK 改动，目标仍需真实 TLS/双向文件、稳定发现、无 AP、Wi-Fi 共存与后台验收。

## 2026-10-24 离线推进与社区资料

用户表示当前无可用网络，要求先做可做部分、查社区，暂不做无线测试。手机能扫描新环境 AP，原 LIMO 网络不在范围，yuni 构建主机也 SSH 超时；不能将此环境变化造成的无 IPv4 当作昨晚回退失败的证据。手机当前原厂 scmversion=g939d76a86982，boot_count=78，AirDrop 关闭，未清除网络/配对配置。

0053 构建与短测已完成：远端 yuni 的基线 strip 哈希与手机 0052 一致；新增 0053 用 READ_ONCE 读取 MLO 能力位，保留 MLD/link 状态判断和 vdev 引用，反汇编确认该策略查询没有睡眠锁调用。候选 becca9726d22d3ed8bf2a2baac1b5cfe76b0c4916fb22f3f2b16c87e50d238c7，512/512 导入 CRC 相符，BTF/完整 CFI 与 MLO 竞态未验证。一轮独立 guardian + 实时 kmsg 的创建/捕获/停止/回退中，mutex acquire/release interrupt-context 断言均 0，独立 DELETE-before-STOP 警告仍 1。证据 build/airdrop/driver/candidate-0053/。原厂回退记录 restored；随后跨日现场网络变动，不用过期网关检测当作稳定性结论。

新增 root 后端管道生命周期：MARIONETTE_CONTROL_STDIN 仅在应用启动时开启；Handle.stop 关闭 stdin，后端自行退出，避免只杀 su 包装层留下 8771 孤儿。SEND stderr 始终排空，最终事件先于退出回调收尾。Rust 后台进程 EOF 测试通过；真机无无线接口运行 HTTPS 接收端，连续两轮绑定 8771，EOF 后约 4ms 退出。不能等同完整 app crash/开机恢复。新 APK 已本地构建，遵从最新指示暂停后续无线实测。

社区资料与下一阶段方案见 docs/AIRDROP_RESEARCH.md。关键新增方向：OWL 要求 active monitor 的硬件 ACK，不只 monitor/injection；Qualcomm #60 没有通用内置驱动补丁；无 AP 需要另行审计 P2P vdev/ROC 生命周期，不能删关联检查冒充完成。原始资料 build/airdrop/community-research/，本轮未读取钥匙串身份。

## 2026-10-23 TCP 和驱动退出链排查（21:25–21:37）

已执行两轮 150 秒上限的实验驱动测试，均提前启动电脑端原生 kmsg reader 并确认 ready，独立 guardian 负责停止服务和恢复原厂。首次回退模块成功但 Wi-Fi 框架 DisabledState 与 wifi_on=1 不一致，额外关开 Wi-Fi 后 3/3；已将此恢复分支加入 awdl-stack-load.sh stock，第二轮自动回退报告 restored、网关 3/3。两轮 boot_count 仍为 78，未发生整机重启，不能外推长期稳定。

新修复：radio.sh 原来在整个 dumpsys wifi 搜索 StartedState，把历史/客户端 WifiClientModeManager 错认成热点。现在只按 iw dev 的真实 AP/AP-VLAN 类型判断。4 项脚本测试覆盖路由所有权、冲突、部分失败清理和热点判定。发送客户端改用 10 秒 TCP 连接超时、70 秒读写空闲超时（覆盖 45 秒接收确认）；真实 loopback silent-TLS 测试验证不会无限等待。Rust workspace 报告 422 项通过，Android 单测、Debug 构建、lintDebug 通过。

TCP 新证据（tcp-investigation-20261023/tap2.txt）：SYN 和 Mac 返回 RST 校验和正确；发送端最终收到 ECONNREFUSED。Mac 主动连接手机 8771 的 SYN 进入 TAP，手机返回校验和正确的 SYN-ACK，但未完成握手/TLS。不能把硬编码 8770 当作本机 Mac 的实际 AirDrop 服务端口；必须重新解析并匹配本机 AWDL 地址。已排除这一轮的包校验和和永久 RST 等待，源地址回复路由现在已随 APK 上机。

200ms/400ms 接收窗口试验没有证明改善：该轮手机 TAP 没见 Mac SYN，网关短测 3/5。保留毫秒参数用于对照，默认恢复原来 2000/4000ms；不能宣称共存性能修复。

驱动新证据：两轮停 monitor 均有 target_if_vdev_mgr_rsp_timer_start 的 DELETE-before-STOP WARN。第二轮回退还出现软中断调用睡眠 mutex 的明确断言：
ce_tasklet -> dp_htt_t2h_msg_handler -> dp_rx_peer_unmap_handler -> dp_peer_unref_delete -> dp_vdev_unref_delete -> dp_tx_flow_pool_unmap_handler -> dp_tx_delete_flow_pool -> policy_mgr_is_mlo_sta_disconnected -> qdf_mutex_acquire/release（cannot be called from interrupt context）。
日志：build/airdrop/tcp-short-20261023/capture/kernel-001.log:2250。尚未证明是 21:12 重启原因。下一步审计该调用链及当前 0052 补丁组合，不能再次靠长测碰运气。

证据目录：build/airdrop/tcp-investigation-20261023/、build/airdrop/tcp-short-20261023/。手机已恢复原厂驱动，AirDrop 常驻关闭，HomeKit 保持运行。完整 AirDrop 目标仍未完成，缺真实 HTTPS 双向文件、Finder 稳定展示、后台长期和功耗验收。

## 2026-10-23 持续实现：固定信道与 IPv6 路由

已实现并编译：station 共存模式固定真实基站信道（含广播信道序列），保留外部选举/同步；该模式对端超时从 2 秒调整为 10 秒，覆盖两轮 4 秒接收窗口，避免正常接收空隙删除邻居。信道 52 操作类按现场 0x76 修正。radio.sh 建立独占表 7400 的 fe80::/64 路由、限定 oif 的 10990 规则及限定本机源地址的 10991 回复规则，退出只删除自身成功建立的规则/路由；冲突拒绝、不 flush 其他网络。STA 换信道时退出旧会话由服务重新建立。

21:07 安装首轮 Debug 修复版（install -r，保留数据），21:08 Mac 的 dns-sd -includeAWDL -i awdl0 发现手机 luftlift-22603；21:10 手机 awdl0 抓包出现发往本机 Mac c2:6b:be:4a:48:f0 的 TCP SYN 和该 Mac 返回的 TCP RST，确认真实双向空口数据。目标端口 8770 当时未监听；不能沿用其他对端的端口。发送进程未及时接收 RST，后续须核对包校验和及 Android 入站处理。Mac 主动连接手机 8771 超时，手机 TAP 抓包无对应 SYN，仍未形成真实 HTTPS 文件传输。源地址回复规则与操作类最后修正仅本地验证，未在实验驱动上复测。

21:12 左右 ADB 消失，重连 uptime=58.51 秒、boot_count=78、boot reason=reboot，实验参数消失。用户未主动拔线并认为手机正常；重启因果未定位。pstore 和 SYSTEM_LAST_KMSG 无可用记录。禁止把本轮写成稳定通过；普通联网开启期间另一次短测 3/5。当前手机回到原厂驱动，AirDrop 常驻关闭、HomeKit 运行。后续加载实验驱动前必须启用电脑端 kmsg reader 并确认 MARIONETTE_KMSG_READY；本轮重启后的只读采集在证据目录 recovery-capture。

验证：Rust workspace 421 项报告通过（网络集成测试可能按环境跳过实际广播）、filin host clippy、Android 单测/Debug 构建/lintDebug、3 项路由建立/冲突/部分失败回滚测试。证据目录 build/airdrop/fixed-channel-20261023/。本机 Mac 无免密 sudo/BPF 抓包权限，不能把 tcpdump 权限失败当作零包。整体目标仍在进行：未完成双向文件、Finder 稳定显示、重启/功耗/普通联网共存、联系人验证。

## 2026-10-23 真机 AirDrop 检查（21:02 左右北京时间）

测试设备 510af65c，使用当时已安装 APK，未更新 APK/驱动。结论：Mac Finder AirDrop 看不到手机，未完成文件传输。清理原有孤儿 filin 后应用可启动，真实 Mac 的 AWDL MAC 为 c2:6b:be:4a:48:f0，接收日志确认该地址。曾解析到另一 AirDrop 服务 fe80::38c0:4aff:fef9:7475，不能当作本机 Mac。

确定问题：awdl0 的 fe80::/64 路由在表 1105，但无匹配策略规则，向 Mac 发送即报 ENETUNREACH。临时加入限定 awdl0 / fe80::/64 的规则后路由查询成功，发送仍在 Discover 阶段超时，未进入 Ask/Upload；规则已撤回。另有协议疑点：物理信道固定 52，运行时却把对端序列中的 52 改写成 6/44/149，仍需修复和对照验证。trace 中有 369 条广播提交成功，不代表空口被 Mac 接纳。monitor raw TX 的 submitted/rejected 计数不适用于当前 NL80211 管理发送通道。

实验开启时网关短测 1/3，停止后 5/5（这些样本不代表长期统计）。为恢复联网，已关闭 AirDrop 常驻开关、停止实验、回收本轮接收进程；awdl0/awdlmon 已消失，HomeKit 前台服务仍在。磁盘 vendor 模块是原厂哈希，但运行中的模块有 marionette_* 参数，不能据磁盘哈希声称当前加载原厂。证据：build/airdrop/device-check-20261023/。

## 2026-10-23 接力修复（本机验证）

继续接手后发现两个本地回归并已修复：`filin-rs` 的 Linux 专用
AF_PACKET/TAP/nl80211 后端原先在 macOS host 单测时无条件编译，导致
`cargo test --workspace` 直接失败；现在仅在 Linux/Android 编译真实后端，
macOS 提供明确返回不支持的测试 stub。`awdl-emit` 的跨 burst AW counter
回归测试原先读取了 TLV 4 的 EAW 倒计时字段，已改为读取真实 AW 序号，并让
绝对 epoch 生成的 replay plan 按 epoch 初始化 AW counter。

本轮验证：Rust workspace 360+ 项测试、`filin-rs` host clippy、
`filin-rs` aarch64-linux-android check，以及 Debug APK 的单测和构建均通过。

这份手册给接手项目的人使用。先看结论和日志证据，再动配对或安装状态；不要用“重装一次”代替定位。

## 2026-10-14 低延迟与服务可靠性加固（最新）

最新 Release APK 已通过 USB ADB `install -r` 更新到 Xiaomi 13，保留应用数据、配件身份和配对。KernelSU 模块中的启动脚本及独立 HomeKit 看护也已更新并运行；最新分发包为项目根目录的 `marionette-kernelsu-module.zip`。广播配置已持久化为 `LOW_LATENCY`。用户在家庭 App 操作后确认「目前响应正常」。

本轮针对指令等待、假成功和服务失活增加以下保护：

- Root 使用持久 shell，控制写入优先于后台轮询；每次操作共享截止时间，超时后丢弃失效 shell，不自动重放有副作用的指令。状态读取复用最多 2 秒的真实快照，不可用时明确返回错误，避免以默认 0/false 掩盖故障。
- GATT 请求在有界串行队列执行，保留加密顺序，避免 Root 执行占用 Bluetooth 回调线程；每次事务有短时唤醒锁。加密响应分片为认证标签预留空间，拒绝不支持的 prepared/offset 写入。
- 同一未完成写请求可复用响应，最终响应读完即解除去重，避免控制器复用事务号时吞掉下一次合法按键。该机制不保证跨断连、进程崩溃的动作恰好执行一次。
- 新连接清理旧会话、计数器和待发事件；认证失败、握手停滞、事件确认超时、队列停滞及启动失败均有恢复路径。写后回读在后台执行，亮度/音量随后收到 `On=true` 时保留已有百分比。
- KernelSU 独立看护每 30 秒检查目标服务，服务缺失自动启动；心跳超过 90 秒且连续两次确认同一进程没有更新时回收该应用进程。尊重驱动关闭、首次解锁和模块禁用状态，深睡恢复时先复核心跳。应用内 watchdog 间隔缩短为 5 分钟。

已完成验证：

- 28 个 JVM 回归用例、4 个看护脚本策略用例、Debug/Release 构建、完整 `lintDebug`、shell 语法与 diff 检查通过。下方首轮记录中的 3 项 lint 错误已修复。
- 真机 instrumentation：30 次亮度写入及实际回读全部成功，并验证后续 `On=true` 不重置百分比；测试结束恢复原亮度和自动/手动模式。Root 冷启动中位数 105 ms，持久 shell 中位数 55 ms、P95 61 ms；亮度写入 P95 8 ms；100 次缓存读取耗时不足 1 ms。这些是设备执行器耗时，不是家庭 App 端到端延迟。
- 不打开 Activity，主动停止服务后约 15 秒恢复；对应用进程执行 SIGKILL 后约 22 秒恢复。两次均重新进入前台服务并成功发布 14 个 GATT 服务、启动低延迟广播。
- 18:13–18:14 的家庭 App 操作日志确认 `CONTROL_SESSION_ACTIVE`，连续三次屏幕电源命令均 `success=true`，执行耗时 87、103、63 ms；用户反馈当前响应正常。

验证边界：本轮尚未执行整机重启、整夜 Doze 或长时间弱信号压力测试；不能将上述恢复测试当作完整开机与长期稳定性验证。首次解锁前仍不提供控制。家庭 App 若再次失效，保留现场日志，重点看 `CONTROL_SESSION_ACTIVE`、`CONTROL_COMMAND_RECEIVED`、`COMMAND_EXECUTE`、恢复原因及 KernelSU 看护日志。

## 2026-10-14 开机自启动恢复修复

报告现象：重新开机后无响应，或者可以连接但控制不可用；进入 App 手动重启 HomeKit 服务才恢复。本次通过代码检查修复以下故障路径，尚未完成手机重启实测，不能将它们当作该设备的已确认日志根因：

- BLE adapter/advertiser 暂未就绪、GATT 添加请求被拒绝、广播启动异常，原先部分分支直接退出，通常只能等 30 分钟 watchdog 或手动重启。现在失败后 5 秒自动恢复；蓝牙关闭时等待 `STATE_ON`，蓝牙正在开启时同时保留重试。
- GATT 打开、服务发布或广播启动回调不返回，原先可能永久停在 `starting=true`。现在每个启动阶段有 30 秒超时，超时后清理并重新发布。
- 异步 GATT 打开结果与服务停止/重启之间存在竞争。现在启动结果、服务发布及广播状态在主线程协调，每轮携带 generation；旧 GATT 返回后关闭，旧回调不能推进新数据库发布。
- `rootOutput()` 原先先 `readText()` 再 `waitFor(3秒)`，输出管道或 `su` 卡住时根本走不到超时。状态轮询持有执行器锁，随后所有控制读写也被阻塞。现在 `BoundedShell` 并发排空 stdout/stderr，在同一个 3 秒截止时间内等待进程和输出，超时释放调用方并清理进程；stdout 最多保留 1 MiB。
- 状态轮询的某次异常原先会让 `ScheduledExecutor` 永久停止后续轮询。现在记录 `STATE_POLL_FAILED`，下一轮继续执行。
- 删除 Manifest 中无效的 `USER_UNLOCKED` 监听。该广播[只发送给动态注册接收器](https://developer.android.com/reference/android/content/Intent#ACTION_USER_UNLOCKED)。当前服务使用加密存储保存配对密钥，设计为首次解锁后由 `BOOT_COMPLETED` / KernelSU hook 启动，不支持首次解锁前控制。

服务/IID、CN、配件身份与配对密钥未变更；本次升级不需要重新配对。

本地验证：Debug / R8 Release 构建和 `BoundedShellTest` 的 6 个 JVM 回归用例通过（正常输出、错误退出、大量 stdout/stderr、进程卡住、子进程继承管道、线程中断）。完整 `lintDebug` 仍报 3 项本次启动修复之外的错误：UI 样式引用 API 27 属性但 minSdk 为 26、Activity 接收器旧版本分支的 flag 检查、Manifest 未声明可选 camera feature；Release 的 `lintVitalRelease` 通过。BLE 发布与手机开机恢复仍需下面的真机流程确认。

2026-10-14 16:33（北京时间）已通过无线 ADB 在 Xiaomi 13 上执行 Release APK 的 `install -r`，返回 `Success`。未打开 Activity、未手动重启服务，`MY_PACKAGE_REPLACED` 自动启动了前台服务；日志确认 `GATT_DATABASE_PUBLISHED services=14`、`BLE_ADVERTISING_STARTED ... connectable=true`，且广播仍为 `paired=true`。安装更新后的后台启动已验证，手机整机重启后的启动与家庭 App 控制仍待实测。

### 专用开机回归

1. 使用 `adb install -r` 安装新 APK，保持原数据、配对状态和 KernelSU Root 授权。若通过系统模块部署，使用新模块包更新。
2. 确认驱动开关开启、蓝牙开启、MIUI 允许自启动，然后重启手机。重启前不要 `am force-stop`；被强制停止的应用不会正常接收开机广播，须先从桌面打开一次解除停止状态。
3. 首次解锁后不打开 Marionette。等待广播启动，再从 iPhone 家庭 App 读取亮度/音量，执行屏幕电源与一个系统开关，核对真实系统状态。以 `CONTROL_STATE_READ` 和成功的 `COMMAND_EXECUTE` 为功能可用证据。
4. 读取日志并确认链路：`SERVICE_RECOVERY_REQUEST action=android.intent.action.BOOT_COMPLETED`（或者 KernelSU 启动请求）→ `SERVICE_CREATED` → `GATT_DATABASE_PUBLISHED services=14` → `BLE_ADVERTISING_STARTED` → `CONTROL_SESSION_ACTIVE` → `CONTROL_STATE_READ` / `COMMAND_EXECUTE`。
5. 启动阶段出现 `BLE_STARTUP_FAILED` / `BLE_STARTUP_TIMEOUT` 时，应随后看到 `BLE_RECOVERY_SCHEDULED` 和成功发布。出现 `ROOT_COMMAND_TIMEOUT` 时，检查 KernelSU 授权与 Root daemon；超时不应永久卡死后续指令。轮询异常应看到 `STATE_POLL_FAILED`，后续轮询仍可恢复。
6. 不进入 App，在系统设置关闭再开启蓝牙，确认 GATT 重新发布且家庭 App 可继续控制。最后在 App 关闭驱动开关，确认自动恢复不会把已关闭的驱动重新开启。

```sh
adb -s <设备序列号> logcat -d -v threadtime -s MiHotspotHap:I Marionette-KSU:I '*:S'
adb -s <设备序列号> shell dumpsys activity services dev.local.mihotspot
```

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
STATE_POLLING_STARTED intervalSeconds=2
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

## AirDrop 实验扩展

2026-10-19 接手检查点：0031 的崩溃已定位为**关机顺序问题**，不是并发竞态。`hdd_wlan_stop_modules()` 先 `hdd_hif_close()`（`wlan_hdd_main.c:14922`，释放 HIF/HAL），后 `hdd_check_for_leaks()`（`:14956`，强制释放遗留 vdev）。0028 把 monitor 的 WMA detach 延后，删除超时后 vdev 遗留到关机阶段，才在 HAL 已销毁后进入 DBR 的 `hal_srng_cleanup` 而崩溃。**0028–0032 是回归，不能累积；0027（`de9cebdb…`）仍是最佳候选**。新增 0033 只在 HIF 已关闭时跳过 HAL 环清理、仍释放主机内存；已通过补丁应用检查，编译验证中，未上机。运行期契约改为「monitor 创建一次、运行期不删除」。构建环境第二次丢失后已在 `ohos-arm` VM 重建（pinned clang r450784e 经 Rosetta、GKI symvers + 原厂模块恢复 7876 项 provider 导出）。完整证据见 `native/airdrop/driver/README.md` 顶部与 `build/airdrop/driver/mission-rx-research/dbr-teardown-chain-audit.json`，阶段计划见 `native/airdrop/driver/ROADMAP.md`。

2026-10-16 19:13 历史进展（0028–0031后续均已拒绝）：0027修复mission共享RX环的前缀误差（400→实测272字节），Wi-Fi保持关联时，用户空间首次收到15个真实AWDL Action，包含本机Mac，信道6/149；DMA、重组后和AF_PACKET数量一致。此前“mission未收AWDL”的推测已被DMA及用户空间证据推翻。窗口期间网关43/48、最长3.073秒，之后3/3；删除monitor的STOP/DELETE竞态在0028中尝试修复，但导致删除超时后的 `dp_mon_vdev_timer` 崩溃并重启；候选已隔离，不能使用。0031进一步显示 direct-buffer ring 清理链仍会在 recovery 并发时写入失效地址，后续先做静态所有权审计。整帧TLV检查、RAW IPv6发送、真实文件互传、低延迟调度/功耗及system_server集成均未完成。手机在0031失败后自动重启并恢复原厂三模块，正常Wi-Fi、HomeKit与AirDrop常驻服务已确认；实验接口/锁/观察器已移除。证据 `build/airdrop/driver/mission-rx-research/direct-buffer-teardown-audit.json` 与 `build/airdrop/driver/monitor-timer-reset/`；请先看驱动README最新检查点。

2026-10-14 已加入同一应用中的原生 AirDrop 实验后端与接收确认 UI。当前小米 13 原厂 `kiwi_v2` 模块的 monitor ops 缺少发包回调，**尚未实现 iPhone 无线互传**；已验证 AWDL 广播接收及 USB 转发的 HTTPS 文件接收流程。已知不兼容模块会在切换 Wi-Fi 前停止。驱动证据、验证边界、源码与构建方法见 [AIRDROP.md](AIRDROP.md)。不要将本地协议测试或 `send()` 返回成功描述为无线兼容完成。

后续已安装 AirDrop 常驻待机服务及 KernelSU 看护，保持 Wi-Fi；当前停在「等待兼容驱动」，不能互传，不是 system_server 内置实现。MiCode fuxi 源码和匹配 Clang 已取回，驱动实验补丁、编译边界、Netlink 共存探针结果见 [驱动工作目录](../native/airdrop/driver/README.md)。**不要开启 monitor_mode_concurrency=1 作为联网共存修复**：源码会主动断开已关联 STA；其含义仅是扫描+监听。

驱动推进已完成模块链接和真实提供方 CRC 比对，但前三个候选启用普通 Wi-Fi 时均重启，已隔离。后续诊断抓到 panic 路径，并从原厂 CNSS / WLAN 反汇编确认 SOC-info 缺失 129 字节尾字段导致栈越界。0005 修复两端结构与复制后，2026-10-16 11:28 的候选 `29d912bd…` 首次通过短时普通 Wi-Fi 关联、IPv4 路由与网关往返（绑定 wlan0，3/3），设备未重启，自动恢复原厂成功。证据 `build/airdrop/driver/cnss-boundary/`。RAW TX 仍为 N，AWDL / 共存尚未验证，下一阶段是默认关闭的 cfg80211 管理帧实验。构建容器 `marionette-driver-persistent`，命名卷 `marionette-driver-workdata`，日志 `/work/wlan-module-cnss-boundary.log`；旧 VM 临时目录已丢失，勿依赖旧容器。不要重测被隔离哈希；后续实验先启动电脑端 `capture-runtime.py` 的原生 kmsg reader 并确认 ready 标记。替换 cfg80211 后需重启 wificond 刷新 netlink 连接，回退时同样处理；第一次整组测试模块恢复后联网仍失败，重启 wificond 后已恢复。具体记录见驱动 README。

2026-10-16 17:31 历史驱动检查点（目标仍在进行）：十八份研究补丁；WLAN 506 项导入 CRC 对应，cfg80211 189 项导入/135 项实际导出 CRC 对应，仍不能等同完整 ABI/运行证明。已验证 STA 联网中的 AWDL 管理 TX 完成回调；真实 AWDL 在原厂 global monitor 可见，mission 模式在 WMI/短时 monitor 中均未交付 action/AWDL。已修复 PE CPU-only 会话数组高阶连续内存分配失败（0010），按完整 MLD 归属处理内部 STA 计数（0011），初始化 monitor home 信道（0012）。monitor 会改写共享 RXDMA_BUF 格式，0014 恢复普通 RX 后网关 48/48，但 monitor 无完整帧；0015 同步 1..200ms 捕获/恢复短窗口实测获得 316 raw 帧、网关 47/48。0016/0017 ROC 对齐窗口真实覆盖三个社交信道，但 AWDL=0且有丢包/尾延迟。0018 直接 monitor 短切也成功切换和恢复，九次共924 raw、AWDL=0，网关42/48，结束3/3；实际每次289–406ms。最后候选 SHA `c0da9487affb28f8d7ac4c1067c63e4b462eb374a7c2645ece1dc57fb7406581`，电脑证据 `build/airdrop/driver/direct-slot/`，手机状态 `/data/local/tmp/marionette-stack-test-163302-2415`。当前普通 Wi-Fi 和原厂三模块已恢复，实验接口/异常观察器/测试锁已移除。下一步核对有明确语义的 mission 固件管理 RX 参数与 global monitor 的差异；尚未按猜测写 PDEV 过滤值或修改固件。RAW TX 仍未开启，IPv6数据通道、AirDrop真机互传、后台同步调度、system_server集成和功耗均未完成。当前构建 `/work/wlan-module-direct-slot.log`；完整证据及复现步骤见驱动 README。不要把“关联状态仍在”当作联网通过，必须看绑定wlan0的收发/丢包/延迟。

## 最小回归流程

1. 编译并用 `install -r` 更新，确认服务启动和 14 个服务发布。
2. 在 Home 中打开配件详情，确认每个功能名称不是自动生成名称。
3. 逐个读取屏幕电源、亮度、热点、移动数据、静音、手电筒、GPS、低电量模式、免打扰和音量；同时确认日志有对应 `CONTROL_STATE_READ`。
4. 从 Android 设置或系统动作改变状态，等待一个轮询周期，确认 `CONTROL_STATE_CHANGED → HAP_GSN_INCREMENTED → HAP_EVENT_INDICATION_QUEUED`。
5. 从 Home 写入布尔值和亮度/音量滑块，确认 `CONTROL_COMMAND_RECEIVED → COMMAND_EXECUTE`，并在 Android 侧核对真实状态；亮度应看到 `screen-brightness=... (panel+settings)` 或明确的回退路径。
6. 断开、重连、重启 Home 再做一次全量读取；不要在这个过程中清除配对，除非是在验证全新配对流程。

如需重新配对，优先使用应用内“清除配对状态”，保留配件 ID；只有要模拟新配件时才重置配件 ID。协议参考以 [Apple HomeKitADK BLE 实现](https://github.com/apple/HomeKitADK/blob/master/HAP/HAPBLEAccessoryServer%2BAdvertising.c) 为准，真机日志以本手册的运行时证据为准。

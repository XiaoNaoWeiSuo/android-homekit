# AirDrop 社区核对与下一阶段（2026-10-24）

## 2026-10-27 续测与首次选主同步修复

四轮20秒基础UDP中，200/400ms窗口的一轮首次实现双向往返（手机5/40、Mac3/39），最后新同步代码轮又因Mac无52时段而0/40。完整对照与限制见HANDOFF顶部和 `build/airdrop/ip-probe/continued-20261027.json`。不据单次最佳结果宣布稳定，也不将窗口参数与Mac自身availability变化混作一个因果。

本轮修复apply_action的顺序：先完成peer有效性和选主，再从本帧刚选中的parent采用时钟。真实finder pcap重放首次同步由5.275811秒提前到第一帧；新增状态/非法倒计时回归，master变化撤销旧同步资格。同步误差每帧只记新样本，status synced不再只表示选出外部master。Android新产物已短测，但没有成功往返，仍需无线效果验证。Rust workspace/Android build/clippy通过。四次正常STOP/原厂回退/最终网关3/3，无整机重启；短测不覆盖此前line#1413故障。

## 2026-10-27 基础链路：确认 TX 全局阻塞，保留固件 RDDM 现场

ADB 恢复后按既定分层顺序运行了一轮独立 UDP 探针，未启动 AirDrop Discover/Ask/Upload、未安装 APK。手机开机计数78，原厂网关前置3/3；部署的是已记录的0056模块和上一轮60秒peer租约filin（SHA `15a8dbb3…`）。Mac 当前 AWDL 地址变为 `7e:4c:de:a9:3e:54`，探针使用重新读取的 IPv6。证据 `build/airdrop/ip-probe/lease-20261027/`，原始pcap及 `link-audit.json`、kernel/system日志、TAP日志、两端探针结果均保留。

**实际结果与根因边界**：原始抓包114 Action、2 data；本机Mac仅1个MIF，晚于两包UDP约20ms。已有pending_rx在真机释放两包到TAP（约20.2ms），但手机探针尚未绑定39921，内核回ICMP端口不可达；不是UDP应用接收成功。Mac MIF序列为 `[149,149,149,0,0,0,0,0,6,149,149,0,0,0,0,0]`，无手机固定信道52。peer租约保住了Mac，但第一帧单播（端口不可达回复）一直Hold；原实现因此关闭整个TAP的POLLIN，后续UDP/广播也读不到。runtime只有1次buffered、0次data injected、TX_STATUS无单播提交。双方探针0请求/0回复；手机97提交/23发送错误，Mac105提交/13发送错误，含后述故障及启动阶段，不能作稳态丢包率。

**固件故障**：启动探针约48秒后出现RDDM，SFR `cmnos_thread_patch.c:1170:0x8Asserted in:0x1366428:0x1c34a98, line#1413`，随后SSR回收monitor/awdlsink，未整机重启。该SFR在旧 `build/airdrop/driver/mld-owner/capture-follow/kernel-001.log` 也出现过；与0037–0040 AF_PACKET注入的line#2834不同，但相同字符串不证明相同根因。此轮没有为故障归因，也没有继续重复无线配置。自动guard正常回原厂、网关3/3、scan-always=1、boot_count78，无实验接口。正常STOP的历史6次通过不能覆盖这次运行期固件故障；0056不能视作稳定候选。

**本轮实现**：新增 `pending_tx.rs` 并接入实际runtime：持续读取TAP，单播按对端保序等待；全局32包/64KiB、每对端8包、2个hop周期（约2.10秒）过期，满队列拒绝新包，重试不续期。一次drain超过2ms不再启动后续Netlink发送，避免把单次阻塞放大到整个队列；单次Netlink原有超时仍存在。保持原有信道/peer gate，不通过强行向不共同的信道发送来伪造链路可用。4项回归覆盖实测Mac序列造成的阻塞、其他对端可发送、每对端顺序、过期/容量/删除。该修复尚未上机验证，不解决缺共同信道及固件断言。

探针增加 `AWDL_IP_PROBE_START_FILE`：先bind输出ready，等两端均ready后再创建各自全新gate；10秒等待上限、开启后开始计时。增加2项真实loopback/未开启gate超时回归，后续guard预算须包含这10秒；详见 `tools/BASE_LINK_TESTING.md`。避免启动先后顺序浪费仅有的接收窗口。

验证：Rust workspace、Android filin release构建及Android all-targets clippy通过；探针Mac/Android严格告警编译通过；新增4项TX回归和2项探针测试通过，最终产物及hash见本轮 `filin-queue-fix` / `build-report.json`。未部署修复后的二进制。末次只读复查ADB列表为空，不推断原因；原厂/boot_count78/网关3/3是此前guard结束时的证据，断开后无法再核对。下一步先定位line#1413触发条件和共享RX/STA共存路径，再解决与Mac的共同信道/同步窗口；保留有效Action准入。稳定UDP、Apple无线TLS/双向文件、无AP及长期后台仍未验收，四项AirDrop稳定验收仍0/4。


## 当前问题与资料给出的边界

- AWDL 本身是设备直连。项目当前必须先关联 AP，是 0009/0012 + STA 的 kiwi 固件路径限制，不是 AirDrop 必须联网。不能仅删除 radio.sh 的关联检查：monitor open 和 ROC 在未关联状态已有 ENOTCONN/EINVAL 证据。
- OWL 的 [README](https://github.com/seemoo-lab/owl#requirements) 和 [netutils.c](https://github.com/seemoo-lab/owl/blob/master/daemon/netutils.c) 明确区分 active monitor，实际设置 NL80211_MNTR_FLAG_ACTIVE。普通监听能收到帧不等于能及时回复硬件 ACK；缺 ACK 可导致对端重复发送、吞吐严重下降。
- [Qualcomm #60](https://github.com/seemoo-lab/owl/issues/60) 直接讨论 con_mode=4 和不支持 active monitor，但没有可复用的 kiwi_v2 内置驱动修复；评论转向外置适配器。不能据此声称 Android 内置芯片已经有成熟方案。
- [Android #66](https://github.com/seemoo-lab/owl/issues/66) 的交叉编译进展与运行时不支持 monitor 并存。编译通过不是硬件可用证据。
- [丢包 #38](https://github.com/seemoo-lab/owl/issues/38) 中有广播/发现可见而单播间歇丢失的复现，包含外部设备已发响应而接收侧未见的观察。这里只是相似症状，不是本项目根因证明。
- OpenDrop [README](https://github.com/seemoo-lab/opendrop#current-limitationstodos) 仍列出 BLE 触发、身份验证等限制。[#119](https://github.com/seemoo-lab/opendrop/issues/119) 有较新 iOS/macOS 兼容失败报告，但不能据评论断言协议已整体失效。
- 联系人模式的上游路径使用真实的 Apple AirDrop 身份材料；本项目尚无联系人验证，不把 Everyone 模式等同完整复刻。这轮没有读取或导出用户钥匙串。

## 下一步实现顺序

1. 保留当前只读接收、TX 提交、空口响应、TAP 收包、TCP/TLS/Upload 的分层证据。已有 SYN/RST、SYN/SYN-ACK，仍没有完整 TLS/文件传输。
2. 硬件 ACK 专项：对照本机 Mac 地址、真正的 AirDrop 服务端口，观察 802.11 重试/ACK 与 TCP 回复。不要使用上次别的设备的 8770 端口。用户暂时不做无线测试，这一步待现场恢复。
3. 时隙对齐：以同一 master 的 TSF/AW 相位与 peer 的当前信道作为发包条件；当前 station fixed-channel 是同 AP 局部方案，不覆盖异信道或无 AP。200/400ms 窗口未证明改善，不作为修复发布。
4. 无 AP 方案：静态审计独立 P2P-device/GO vdev 的生命周期、ROC、ACK 地址归属和接收路由。只有有明确源码路径后才实现；避免在 STA 未关联时直接强开监视或删除固件前置条件。互联网访问不是目标前置条件。
5. 生命周期：0053 对 MLO 查询的软中断 mutex 修复已编译/短测；独立 STOP/DELETE 竞态仍需修复，不重新累计已拒绝的 0028 方案。
6. 双向文件端到端后，才进入重复传输、拒绝/超时、睡眠/开机恢复、普通 Wi-Fi 丢包和功耗验收；BLE 唤醒和联系人身份另有明确实现边界。

## 本轮不依赖无线的实现

Android 后端增加可选 MARIONETTE_CONTROL_STDIN：启动者关闭管道/死亡，root 子进程自行退出并释放 HTTPS/mDNS 描述符。普通 CLI 不开启此机制。EOF 退出不是传输成功，也不保证发送 mDNS goodbye；缓存失效仍按网络协议处理。

发送端 stderr 始终排空，避免无日志文件的 SEND 进程被满管道阻塞；等待线程在通知退出前先等待 stdout 事件泵收尾。真实后台 EOF 子进程测试通过；Android 真机离线测试连续两次 HTTPS 绑定 8771，关管道后均约 4ms 退出，第二次绑定成功。未因此证明 app crash/设备重启的全部恢复链。

资料原文与 GitHub issue/comments 保存在 build/airdrop/community-research/。通用 web 搜索接口不可用时直接读取上游 GitHub API/raw 源码；未使用二手推测替代源码。

## 固定源码的 P2P 审计与单播 ACK 修复

本节依据 MiCode WLAN 518147fd 与 kernel 7fad87dc，原文和 SHA-256 在 build/airdrop/p2p-audit/sources.json。

| 层次 | 已核对的约束 | 推进条件 |
| --- | --- | --- |
| cfg80211 mlme.c:722 | P2P_DEVICE 的非 public action 返回 EOPNOTSUPP；AWDL 是 vendor action | 必须有独立、默认关闭的精确 AWDL 门；现有 0002 仅接受 STATION |
| cfg80211 0043 | AWDL data 例外也只接受 STATION | 不能只在 radio.sh 把 wlan0 改成 p2p0 |
| HDD wlan_hdd_p2p.c:156 | ROC 先检查 STA+monitor concurrency，有则直接 EINVAL | 与当前监视接收方案存在冲突；需完整接收替代或经审计的有界共存 |
| P2P wlan_p2p_roc.c:79 | ROC 转成 SCAN_TYPE_P2P_LISTEN，经过有效 vdev、scan 及 ready-on-channel 事件 | 请求接受不是到达目标信道，必须匹配 ready/cookie/frequency 后发 |
| P2P wlan_p2p_off_chan_tx.c:1233 | no_ack 选择 download completion；否则选择 OTA completion callback | 软件提交成功、下载完成与空口确认不得混称 |
| 接收 | 现有 awdlsink 依赖 monitor，尚未证明 P2P 自身能上送第三方 AWDL 数据 | 是无 AP 方案仍未解决的必要条件 |

不为上述未解决路径新增“假就绪”开关。本节不是无 AP 已实现的声明。

已实现的发送修复：filin 的 NL80211 请求原先对所有帧写 DONT_WAIT_FOR_ACK；现在只有组地址设置它，单播不设置，让驱动走已有 OTA 完成路径。Netlink ACK 仍只说明请求接受；本轮没有实现/验证对无线 TX_STATUS 的 cookie 跟踪，也没有证明硬件重传次数。软件盲重发仅保留给原 AF_PACKET 注入后端，避免叠加到 NL80211 单播。

编码迁到可在 macOS 测试的 frame_tx.rs：检查命令、接口、频率、原帧字节、NLA 对齐、单播/广播/组播 flag、短帧及过大帧拒绝。Linux/Android 生产路径直接使用该编码器；主机测试不依赖旧 unsupported OS stub。Android 目标 tests 编译检查、Rust 测试和 clippy 通过；无线行为待用户恢复测试条件后验收。

## TX_STATUS 诊断实现（2026-10-24）

filin 现在动态解析 nl80211 的 mlme multicast group 并订阅，按接口索引和当前单播请求返回的 cookie 跟踪 CMD_FRAME_TX_STATUS。解析器处理同一数据报里的 cookie/Netlink ACK/无线状态、状态早于 cookie、重复或无关事件，拒绝截断与重复关键属性。只有请求已被接受且 cookie 匹配才统计单播 ACK；广播不进入该统计。后端主循环每约 100ms 非阻塞排空迟到状态，每次最多 32 个数据报，等待请求使用一个共享 3 秒截止时间，不等待无线 ACK 阻塞主循环。

GET /status 新增 tx_status（AF_PACKET 后端为 null）：

| 字段 | 含义 |
| --- | --- |
| subscribed | 是否成功订阅 mlme；false 时不能用零确认数推断未发出 |
| requests_accepted | Netlink ACK 成功的请求数，含广播，不能当空口送达 |
| requests_failed | 请求拒绝、超时或解析失败；超时未必表示没有发射 |
| unicast_acked | 驱动报告 ACK 的匹配单播；不代表 TLS/Upload 或应用接受 |
| unicast_no_ack | 匹配单播收到明确未 ACK 状态 |
| status_missing | 已取得 cookie 的单播超过 5 秒没有状态，或 128 项队列上限淘汰 |
| cookie_missing | 已接受的单播没有取得可归属 cookie；观测缺口 |
| pending | 尚待状态的已接受单播数 |

状态/早到缓存均有 128 项上限。不保证驱动一定发送 TX_STATUS 或 cookie 唯一性跨模块重载；重开后端会重建 tracker。本轮是实现和离线验证，遵从用户指示未订阅真机无线组、未发包、未部署 APK。解析/归属测试共 8 项，另有 /status JSON 测试；Android 目标 clippy 和编译检查通过。旧仅测试的 frame_ack 解析器与对应镜像测试已移除，真实发送路径统一使用 tx_status::parse，避免测试与生产实现偏离。

后续验收：真实单播负对照（不存在的对端）、已知对端正对照、广播不计单播确认、最后一帧迟到状态、接口重建后计数归零，再与外部抓包和 TCP/TLS 结果对应。当前不把这些离线用例当作任何无线兼容证明。

## 接收准入和组播误锁定修复（2026-10-24）

源码确认 runtime 的 data RX 分支无条件调用 note_unicast_rx，连 mDNS 组播也被算作单播传输，刷新 transfer pin；在非 fixed-channel 模式会阻止正常信道序列更新。监视接口上的其他目的地址单播也没有明确目的地址过滤，A-MSDU 子帧直接写入 TAP。

新增 data_rx.rs 作为真实 RX 的统一入口：检查 AWDL BSSID、DATA/QOS_DATA 类型、支持的三地址布局及 LLC/SNAP 完整 OUI；排除本机反射与其他目的单播；聚合包逐子帧检查目的地址，仅交付本机和组地址。只有真正交付本机的单播才更新 transfer pin。现有 peer 存在性检查仍保留。未实现的分片、加密、四地址及 HT-control 布局明确拒绝，不把其扩展头当作业务数据。

Action RX 同样先检查 MAC 类型、BSSID 和布局，再处理时钟。错误包不会更新 TSFT bridge。已有 /status 的 mdns_rx_self 同时统计 TAP 出站本机 mDNS，因此其增长不能作为本机无线反射证据。

新增 5 项数据路径回归（单播/组播、第三方/反射、错误头/OUI/截断、QoS/Retry/FCS、混合 A-MSDU）和 1 项错误同步帧不得修改时钟的回归。测试不证明该缺陷是现场 Finder 服务抖动的根因；需要在具备无线条件后对应空口/TAP/mDNS 缓存观察。本轮未操作手机或无线接口。

## 发现事件顺序与 mDNS 重播生命周期（2026-10-24）

源码复现的竞态：spawn_browse 先启动线程，主线程才输出 browsing（Android 收到后清空列表）。缓存命中的 peer 可以先于 browsing 到达，被随后清空，去重表又阻止同样的 peer 重发。现在 spawn_browse_with_started 在同一消费线程先发重置信号，再消费任何缓存事件；只有实际启动成功才发信号。

浏览通道断开原来走立即重试，可能忙循环。现在区分超时与断开，结束时移除已报告的 peer；接收 supervisor 观察线程结束并重建发现会话。地址集合顺序改变不再产生伪“设备变化”。3 项确定性无网络测试覆盖预先缓存的 peer 事件顺序、断开不重试/及时 lost、重复 removal 和地址集合重排。

filin 的单帧重播缓存原来将所有本机 mDNS 目的端口 5353 数据当成宣告，查询和 TTL=0 goodbye 也会被保存。现在只缓存完整、成功的标准响应；查询不覆盖缓存，goodbye/不完整响应清除缓存；缓存重播时间不超过记录中最短 TTL 且上限 45 秒。原始报文仍按原路径发送；此处只改变后续重播，未改 DNS 原始字节/UDP checksum。报文内 TTL 本身未随重播重写，不能据此保证对端缓存恰在本机超时同时消失。它仍是一个报文的缓存，并非完整 DNS 记录缓存；多报文服务重建/进程无 goodbye 强退仍需后续验证。

新增 mdns_cache.rs 与真实发送路径集成，5 项用例验证正向响应、查询、撤销、混合 TTL、截断、压缩指针、IPv4 options/分片以及缓存状态迁移。Rust workspace 报告 450 项（含条件跳过/辅助项）、Android 目标 filin clippy、Debug APK 构建与 JVM 测试通过。未进行无线测试，不把代码竞态证据当作现场 Finder 抖动的唯一根因。

## 真实回环 TLS 与一次性接收确认（2026-10-24）

新增 server_tls_tests.rs，直接运行生产 handle_tls_connection、rustls 双端、确认目录协议、CPIO/dvzip 解码和磁盘保存；监听仅 127.0.0.1 随机端口，不启动 AWDL/mDNS。测试线程清理关闭 socket，临时接收目录在退出时删除。

发现并修复：
- 以前同连接的 approved 布尔值可跳过共享 approval map 的消费，上传后仍留下一次跨连接授权；现所有 Upload 都消费同一份带期限的批准。
- 新 Ask 在读取正文前撤销该 peer 的旧批准，拒绝或解析失败不会留下旧授权。
- handler 过去只按路径分发，GET/PUT /Upload 也可进入保存路径；现在只接受 POST，并在 TLS 入口于 Expect/正文前返回 405。
- TLS 入口与完整 HTTP parser 共用严格请求行解析，缺版本、多余分隔及非法版本不再造成授权门与路径分发不一致。

4 项真实 TLS 回归：
1. 同连接 Discover/Ask/Expect:100-continue/chunked Upload，等待 interim 响应后才发送，8193 字节文件逐字节匹配；随后跨连接重放在正文前 403。
2. 已有批准后拒绝新 Ask，旧批准消失；GET/PUT/HEAD Upload 在读取正文前 405，目录为空。
3. 使用真实 RustlsTransport 每阶段新连接，连续两次接受/上传，重复文件保存 sample.bin/1-sample.bin，原文件保持原内容。
4. 注入过期的批准时间，Upload 在发 100 Continue 前返回 403。
另有请求行解析负例。这里验证的是批准过期，不是等待真实 UI 45 秒的完整超时交互。

仍是按 peer IP 的短期批准，不是 Apple 联系人身份验证；并发多请求的用户界面/审批排队、真实 Mac/iPhone TLS 兼容性、无线链路均未在这轮验收。不能把“Rust 客户端对 Rust 服务端的回环传输成功”写成 Apple 无线互传成功。

## 上游 TLS 身份与 Ask 字段核对（2026-10-24）

再次读取 [OpenDrop config.py](https://github.com/seemoo-lab/opendrop/blob/master/opendrop/config.py)、[client.py](https://github.com/seemoo-lab/opendrop/blob/master/opendrop/client.py)、[server.py](https://github.com/seemoo-lab/opendrop/blob/master/opendrop/server.py)。具体快照哈希在 build/airdrop/community-research/tls-ask-source-snapshot.json；master 是移动分支，复核以保存原文为准。

确认的差异与修改：
- 上游 get_ssl_context 加载本机证书/私钥，发送连接可响应接收端的客户端证书请求；本地原先 with_no_client_auth 显式禁用。现在 build_client_config 为发送进程生成一次自签身份并交给 rustls，整个 Discover/Ask/Upload 复用配置。新增 build_client_config_with_identity 便于显式自有身份和测试，不读取系统钥匙串、不伪造 Apple 签名。
- 上游 send_ask 的 SenderID 是 config.service_id 字符串，默认12位十六进制。本地原来 plist Data 的依据只是不存在的文档引用及镜像测试。现内部仍保存随机6字节，序列化为小写十六进制字符串。测试直接检查字符串类型和值。
- 发送普通文件仅携带 Files，不再带空 Items；补 FileIsDirectory=false 和 ConvertMediaFormats=false，对应上游文件请求形状。未实现 URL 传输，未声称目录支持。
- 服务器证书的握手签名算法列表从实际 ring provider 获取，避免宣称支持 provider 不提供的 SHA1 等算法。自签证书可被接受不等于对端或 Apple 账户可信；已修正文档里的“可信 AWDL 链路”措辞。

新增3项真实 rustls 协议握手回归：TLS1.2/TLS1.3 请求客户端身份时接收并验证证书；要求身份时匿名客户端失败；无需身份时不发送证书。现有实际 loopback TLS 文件收发测试继续通过。workspace 报告458项（含条件跳过/辅助项），Debug构建/JVM单测/lintDebug通过，日志 build/airdrop/apple-wire-*。

限制：这里只是与已公开实现对齐，不是当代 Apple 设备必须要求客户端证书的证明；上次无线 TCP 握手尚未完成，不能把这些 TLS/Ask 差异写作其根因。身份仍为每进程临时证书，未持久化/导入 Apple 联系人认证凭证。没有部署、没有无线测试，完整 AirDrop 目标未完成。

## Monitor 停止/删除静态审计（2026-10-24）

固定源码来源与哈希：build/airdrop/monitor-stop-audit/sources.json；逐文件结论 audit.json。当前确认的顺序：

| 阶段 | 当前行为 | 缺口 |
| --- | --- | --- |
| HDD close | reset DP，等待 pending UP | 没有 STOP 完成屏障 |
| SME/vdev delete | 开始逻辑删除 | 正常 STOP 回调查找需对象有效 |
| WMA detach | DOWN 状态机发异步 STOP | 尚未等待固件 |
| WMA release | detach DP / 立即释放引用 | 对象可开始 DELETE |
| target-if STOP response | 取消 STOP timer，再分发回调 | 提前 DELETE/逻辑删除妨碍迟到响应 |

HDD 名为 hdd_monitor_mode_vdev_status 的函数只等待 UP event，不能作为停止屏障。target-if 的 STOP 回调先停止 response timer，再通过状态校验的对象引用调用 WMA。等待必须放在逻辑删除之前，并保持回调所需对象有效。已拒绝 0028 的 WMA 延迟引用方案曾使 DP timer 和关机泄漏进入错误顺序，不能直接重用。

真正顺序修复的必要条件：在正常上下文发起停止；明确 STOP/DOWN 完成与超时/SSR 失败信号；先同步停止 DP timer，保证持有有效 vdev 引用；不阻塞处理 WMI 响应的线程；仅完成正常 DOWN 后允许逻辑删除，失败交已有 recovery 且不双释放。不以轮询 state 或吞掉 ASSERT 代替这些契约。此完整改动尚未实现，仍需当前构建树的 MLME callback/锁依赖审计。

独立 0054 修复响应参数与索引：delete 回调把 > max_bssid 改为 >=；stop 回调先验证 rsp/vdev_mlme/vdev 和 ID 边界，确认 WMA 槽仍对应受引用 vdev，再分发。没有延迟引用、改结构、改 timer 或抑制原有断言，不宣称解决 STOP/DELETE。

check-vdev-response-boundaries.py 在临时目录应用补丁，提取真实 C 回调编译到最小 host shim。原始源码由 ASan 捕获越界；修正版通过 ASan/UBSan 的空值、边界、对象身份及有效回调用例。该 shim 不模拟 firmware/objmgr 并发，不是内核 ABI/运行验证。远端构建环境上次检查不可达，本轮未完整编译驱动、未部署、未操作无线。

### 恢复构建后的回调链核对（2026-10-24）

通过 Tailscale 实际节点地址 `100.121.129.99` 重新访问原构建树。`wma_utils.c` 与 `wlan_mlme_vdev_mgr_interface.c` 的真实源码进一步确认，先前“异步 DOWN”表述不够精确：状态机事件在当前调用链推进，只有固件 STOP 响应是异步的。

| 源码位置 | 实际行为及影响 |
| --- | --- |
| wma_utils.c:4712 / wma_mon_mlme_vdev_stop_send | 提交 STOP 后不等回复，直接递交 MLME_DOWN_REQ；STOP 发送失败也继续 |
| wma_utils.c:4737 / wma_mon_mlme_vdev_down_send | 提交 DOWN 后立即递交 DOWN_COMPLETE；DOWN 发送失败也继续 |
| wlan_mlme_vdev_mgr_interface.c:1984 / mon_mlme_ops | 未注册 ext_stop_rsp、stop_continue、notify_down_complete；通用 STA/AP 停止回调不适用于此表 |
| wlan_vdev_mgr_tgt_if_rx_api.c:155 | 仅当 ext_stop_rsp 非空才调用外部停止响应；无 monitor 回调时不进入 WMA stop handler |

因此状态变成 INIT 不能证明 STOP 计时器已取消或固件已停。已拒绝 0028 仅修改 WMA 和对象查找，没有补 monitor ops，其 WMA 等待收尾分支在该 monitor 响应链上不可达。这是静态可确认的缺口，不把它写成此前全部崩溃/重启的唯一根因。新修复必须同时覆盖逻辑删除前启动停止、真实 STOP 响应派发、发送失败/超时/SSR 通知、DP timer 停止及引用释放；仅恢复旧补丁或添加等待都不完整。

真实源码快照 `build/airdrop/monitor-stop-audit/wma_utils-current.c` SHA256 `03be8e587083ca20c3c8415e47c88eccf74e12f22f5add0bf9be696b85a5e771`；MLME interface SHA256 `d2c5325c9ec6929abe0121006b0e9be27e2995373be10345b5d8cf78431c18b8`。0054 链接产物的回调反汇编/重定位记录在 `build/airdrop/driver/candidate-0054/`。

0054 在实际 0053 树应用成功，全模块构建退出 0，候选 SHA256 `80680eaec756687e3a47464fd92b78e2749f68b84335d32821f074a440fc4855`。512/512 导入 CRC 对上实际 provider 表；取回构建源码跑 ASan/UBSan 边界回归通过。CFI/LTO 构建选项保留，但没有独立完成 CFI ABI 审核；缺 vmlinux 导致模块 BTF 未生成，浅克隆版本脚本和根 Module.symvers 警告仍在。未加载驱动、未启动无线测试；不把编译、CRC 或 host shim 当作运行验收。

完整模块保留在构建机 `/home/lin/workspace/xiaomi13-airdrop/audit-0054/candidate-0054.ko`，CRC 检查在远端执行。当前 DERP 链路传回大文件未完成/超时，本地只保留完整校验记录、构建日志、源码及反汇编，已删除不完整模块/归档；取回方法及位置见本地 `ARTIFACT.txt`，后续须核对 SHA256。


## 2026-10-26 无线抓包驱动的两项修正

证据目录 `build/airdrop/device-20261026/`，逐轮结论及限制见 `runtime.json` 和 `docs/HANDOFF.md`。0054完整产物已经LAN取回并匹配SHA，当前手机最终已恢复原厂及联网，boot_count78。

1. **mDNS IP hop-limit**：第一轮Android TAP实际响应为1，固定mdns-sd 0.11.5源码的new_socket_bind没有设置相关socket选项。[RFC 6762 §11](https://www.rfc-editor.org/rfc/rfc6762.html#section-11)建议包括单播回复在内使用255，以兼容旧接收者的来源检查；不是所有现代Apple接收端必然丢弃1的证明。新增固定依赖副本及最小socket修正，保留许可证。独立测试检查IPv4/IPv6单播/组播实际socket选项；第二轮实际服务PTR/SRV/TXT/AAAA包hop-limit255。没有改变DNS记录TTL，也没有证明设备发现稳定。
2. **复制服务记录引流到不存在的端口**：第二轮完整pcap177帧中6帧data全部由实际data_rx解析接受。四个Mac QoS帧是SYN，目标53870；该端口正是MIF模板TLV2中_appsvcprepair的SRV值0xd26e，另一个_applicationservicepairing为53869。现有重写只改身份，保留服务协议/端口与AirDrop TXT，实际后端仅实现独立mDNS声明的8771。这会产生未实现服务的错误宣告。真实runtime和awdl-emit改用build_link_announcement，移除TLV2，保留其他TLV和尾部；历史build函数只供模板研究。不能把重命名当成完整复刻现代application-service协议，也不能把这四个SYN写成8771握手到达。代码/构建/回归通过，新APK已装；第三轮前置STACK_LOADED_NO_LINK使其无线效果仍待验证。

四个SYN到达时Mac尚未进入peer表，runtime在解析后还会丢弃未知peer的数据；稍后Mac的action才进入/退出peer表。该过滤是独立的时序问题，但放行未知peer还需要解决发送端信道调度与邻居归属，不能简单删除判断。新增rx-audit示例直接调用实际解析器重放raw frame；没有实现该准入改动。

测试方法纠正：dns-sd使用IncludeAWDL，IncludeP2P-only的空结果不算AWDL发现失败。Mac OpenDrop客户端对awdl0设置SO_RECV_ANYIF(0x1104)；早期curl没有，故不能用它单独证明原生失败。第三轮准备了等效Python连接探针，未进入执行。系统sharingd的原生8771 Discover仍有SYN重试日志，hostname被隐私化，未完成服务实例到手机的唯一关联。不存在“已经打通TLS”的证据。Mac当前AirDrop包含新的application-service路径，传统TCP监听缺席也不能作为关闭状态判断。

现有验收仍0/4稳定打通：无线TLS和双向文件均未成功。两轮实际启动中第一轮ACK6/未ACK3只是驱动报告的链路确认，未归属本机Mac，更不代表文件成功。第二轮自动回退曾留下Wi-Fi关闭，额外恢复后3/3；第三轮最终自动回退3/3。STOP/DELETE正常停止屏障和加载/回退框架同步仍需优先修复。


## 0055/0056：逻辑删除前停止与定时器关闭（2026-10-26，未部署）

按用户要求先完善底层，不再无线试跑。固定实际0054源码审计发现旧0028漏注册monitor STOP响应，SME已逻辑删除后再等也使状态检查引用失败；另有DP timer stop在sync_cancel之后才清RUNNING，原0030只在回调末尾无锁检查，不能关闭check/rearm竞态。

0055将异步停止门放到sme_vdev_delete的逻辑删除之前，复用SME/WMA已有引用。接STOP和pending START（包括legacy monitor失败仅通知LIM的问题）；内部STOP响应携带host失败状态。WAIT→READY→RESUMED只继续一次，nested事件用已持锁SM API，READY回调不调用SME，外层返回后才继续删除。HDD删除等待加START/STOP预算，PE monitor session延后清理。正常STOP响应先由target-if清计时器/psoc引用，再由monitor响应推进DOWN；失败进入既有恢复。仅固定SERIALIZE_VDEV_RESP构建与STA-monitor实验启用时使用异步门，不在softirq直接获取SME锁。

0056对timer start/rearm/stop增加共同锁；STOP先关重挂入口，在锁外sync_cancel，并用stopper计数阻止正在取消时start。重复stop均join回调，deinit关INIT/RUNNING后取消并free。HDD的DP reset原已同步stop，本补丁补足timer内部交错。生命周期锁/引用的实际内核并发仍需审查，不将host shim当成kernel lockdep/KCSAN。

check-monitor-stop.py提取实际函数验证正常/快速/重复响应、START pending/failure、STOP/DOWN失败、timeout、SSR标志、对象身份与引用余额；check-monitor-timer.py提取实际rearm及起停函数，注入取消中start/rearm/二次stop及重复deinit。ASan/UBSan通过。完整候选SHA `bbb6e00602c6a0ce9c5f5f3222d536117356de957c0a6d63f900c12a7d4ade4e`，512/512导入CRC相符，CFI/LTO保留；BTF仍缺vmlinux。合并源码包含39改动文件和5新头文件，每项before/after SHA，并验证固定干净输入可完整重现，不再把散落历史补丁当可重现基线。

当前明确未覆盖：scheduler分配/入队失败与shutdown queue flush交错时的SME/WMA/peer引用、SSR并行与恢复线程全部调用上下文。不能据当前shim宣称所有失败路径安全；候选未部署，继续静态与离线审查。完整通信、STA共存与无AP也尚未完成。

加载器额外修正：原ctl.restart异步返回即svc enable，可能在旧wificond生命周期中操作；现在等新PID/running，关Wi-Fi也观察框架状态。load和stock共用锁，检查卸载结果，部分加载/无链路失败统一恢复原厂，移除过期默认0046。七项mock shell故障测试通过，不代表已修复实机所有HAL/framework恢复问题。未来限时guardian须计入新完整失败/回退预算，不能原样复用150秒旧脚本。


## 分层基础验证与data先于Action（2026-10-26）

用户澄清并未禁止真机，应先把基础驱动/通信验稳再做协议。本轮不运行AirDrop HTTPS/mDNS服务，先执行monitor生命周期，再运行独立固定64字节UDP探针。完整事实在HANDOFF最新项与 `build/airdrop/ip-probe/progress.json`。

正常STOP累计6次成功（同一加载连续重建3次，另有一次单次生命周期、两次IP测试退出），真实STOP完成7.6–14.8ms，之后DELETE，无旧STOP/DELETE WARN，无整机重启；SSR和队列失败不在这六次覆盖内。仍保留原有DP peer状态诊断，不能写成全部内核日志无异常。

加载失败的确证原因来自完整logcat：重启wificond触发Native Failure，WifiSelfRecovery因一小时两次预算耗尽发送CMD_RECOVERY_DISABLE_WIFI；新daemon已running并不代表框架death处理结束。scan-only未退出也是旧卸载条件的缺口，但单独关scan-only不足以解决延迟事件。加载器现临时关scan-always并等ActiveModeManagers=0，重启daemon后明确关Wi-Fi并等12次连续quiet观测，再启用；普通reconcile不再重启daemon。末尾恢复scan-always=1。最后三次修订后加载/回退完成，不能据此宣称所有框架故障已修复。10项新loader故障测试加4项既有测试通过。

基础UDP第一次手机提交130、Mac收到11，Mac提交134、手机收到1，双方echo回复0。Mac比手机运行稍长，末尾4个No route发生在拆接口后，不能计为稳态链路证据。第二次同步启动约60秒，手机120/Mac118、双方接收/回复0。Mac当前基础Wi-Fi信道通过CoreWLAN确认为52，与手机一致。没有TCP/TLS/文件成功。

原始pcap+trace确认：Mac三个单播data分别早于它的Action约23/31ms，旧runtime按未知peer丢弃；peer十秒过期后还导致TX直接丢弃。`pending_rx.rs`只暂存已通过data_rx且含本机单播的delivery，最多16包/64KiB、250ms；不创建peer、不放宽帧校验，仅在Action确认有效peer后释放。runtime统一走deliver_rx交给TAP并更新单播pin。四项测试验证匹配peer、只释放一次、超时、包数/内存边界。`pcap-peer-replay`直接调用生产parser/state/queue，原始三包分别在23452/31544/31428us释放；第二轮只有两包且没有Mac Action，离线与运行均不释放。修复了可复现的先后顺序丢包，不代表改善真实无线性能。

最终手机原厂g939d76a86982、boot_count78、scan-always恢复1、网关3/3；新filin仅独立测试目录使用，未更新APK。Rust workspace报告463项，Android filin release/clippy通过。下一步保持帧/IP范围，审计Action可见性、时钟/可用窗口与peer生命周期；不通过增加上层功能掩盖当前无往返事实。


## 固定信道peer租约修正（2026-10-27）

真实pcap中Mac源`4e:2f:a9:4e:a0:4f`的两次完整MIF相隔约48秒；fixed radio原来用10秒peer timeout，导致后续UDP data到达时peer已被evict。普通跳频仍使用owl式2秒超时，固定信道模式改为上限60秒，并增加状态回归验证48秒仍保留、60秒后删除。目标是避免固定信道低频Action造成无意义的未知peer丢弃，不放宽data帧/OUI/BSSID校验，也不让未知源创建peer。新Android filin release SHA `15a8dbb318599fd43a4abcc0cdd88faca1c52acbc79f222f002d606bc30f8148`，尚未真机部署；ADB在下一轮开始前再次消失。

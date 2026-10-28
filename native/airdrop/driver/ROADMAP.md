# AirDrop 常驻路线图

**2026-10-27 续测**：4轮有界20秒UDP，新增4次正常STOP/自动原厂恢复，末次boot_count78、网关3/3。首次双向往返为手机5/40、Mac3/39；另一轮无共同52信道又0/40，尚未稳定。用户态已修首次选主采用时钟的顺序及统计，未解决固件line#1413；短测无故障不能当长时稳定证明。详见HANDOFF最新条目和 `build/airdrop/ip-probe/continued-20261027.json`。

**2026-10-27 新运行期故障（优先于历史安全结论）**：0056独立UDP测试约48秒发生固件RDDM/SSR，SFR `0x1366428:0x1c34a98, line#1413`。无整机重启，自动恢复原厂和网关3/3，boot_count78。该签名旧mld-owner日志也有，根因未定；未重复试跑。抓包确认Mac宣告无52信道，一帧待发送单播堵住整个TAP；已实现有界分对端队列并离线验证，未无线验证。pending_rx真机释放2包，但探针未绑定导致端口不可达，现探针增加双端ready闸门。详见HANDOFF顶部和 `build/airdrop/ip-probe/lease-20261027/`。继续基础驱动/帧/IP层，稳定验收仍0/4。

## 最新验证顺序与结果（2026-10-26）

用户明确允许有层次的真机测试：驱动起停→双向帧/IP→稳定往返，再进入AirDrop协议。0056正常STOP/DELETE顺序6次通过，包含3次连续monitor重建；加载器处理扫描模式与wificond恢复事件后成功加载/回退。独立UDP手机→Mac11请求、Mac→手机1请求曾到达，双方echo回复0；另一轮双方0，不是稳定通信。新增250ms/16包/64KiB RX暂存修复data先于Action的已证实丢包，真实pcap重放释放3帧，但未证明无线性能改善。继续查Action间歇可见和peer/收发调度，暂不恢复协议测试。详见HANDOFF顶部。

## 当前方向：先完成底层补丁，暂停无线试跑（2026-10-26）

0055/0056已形成可复现离线候选，详见 `stack-0056/README.md` 与 HANDOFF 顶部。完整编译/512导入CRC/实际C函数故障注入通过，未部署。停止链路在逻辑删除前接真实STOP，定时器关闭入口再同步取消，加载器等待框架和wificond就绪。仍需审查scheduler关闭/入队失败及SSR交错，不把这些离线结果标成S1安全验收完成。底层通信的peer时序、收发调度与STA共存、无AP支持继续保留为阻塞，不追加协议或UI扩展。

## 2026-10-26 真机检查点

0054已取回并做两轮有界无线测试，单播TX_STATUS有6次ACK但未归属具体对端；STOP/DELETE仍告警，未重启。mDNS发送hop-limit由1改为255，真机包已确认。实际AWDL MIF不再复制未实现的 `_appsvcprepair:53870` / `_applicationservicepairing:53869`：抓到的本机Mac四个SYN目标恰为旧53870，不是后端8771。这项修正已编译/装包，但第三轮前置联网失败，尚未无线验证。最终原厂及网关3/3恢复，boot_count78。具体回退异常、测试工具纠正及后续步骤以 `docs/HANDOFF.md` 顶部为准，稳定四项验收仍0/4。

## 当前状态（2026-10-24，优先于下方历史记录）

完整目标仍是 Mac/iPhone 双向文件互传、无 AP 发现、正常 Wi-Fi 共存和后台生命周期。已有真实 mDNS 与部分 TCP 报文往返，尚未完成 Apple 设备的无线 TLS/文件互传。下方按日期保留的旧结论不能作为当前验收结果。

- 构建机已通过 `ssh lin@100.121.129.99` 恢复访问，目录 `/home/lin/workspace/xiaomi13-airdrop`。`.9` 不在当前 Tailscale 节点表中。
- 0053 是上次有界真机测试的候选；中断上下文睡眠锁告警该轮未出现，STOP/DELETE 告警仍在。不能将删除阶段标记完成。
- 0054 在真实构建树完成全模块编译，修复响应空值、边界和对象身份检查；它不修改停止顺序。本轮未部署、未进行无线测试。
- 新源码证据：`wma_mon_mlme_vdev_stop_send` 在 STOP 提交后立即送 `MLME_DOWN_REQ`，down 函数立即送 `DOWN_COMPLETE`；失败分支也如此。monitor ops 没有 STOP 响应回调。等待 INIT 状态不能证明固件已停止，旧 0028 的 WMA 等待分支也没有接到 monitor 的响应派发链。
- 下一步必须补齐逻辑删除前的停止请求、真实响应回调、完成/失败通知及 SSR 清理契约。不得复用已拒绝的 0028，或只加等待/吞告警。细节与证据见 `docs/AIRDROP_RESEARCH.md` 和 `build/airdrop/driver/candidate-0054/`。

目标：小米 13（fuxi / kiwi_v2）在**保持正常 Wi-Fi 联网**的前提下常驻 AWDL/AirDrop，
iPhone 侧体验接近原生（出现在分享面板、可收可发），待机功耗可接受。

这份文档只描述**可证伪的阶段与验收条件**。任何“编译通过”“CRC 相符”“关联仍在”
都不算阶段完成。

## 0. 当前事实（有证据的部分）

| 事实 | 证据 |
| --- | --- |
| 保持 STA 关联时，mission 共享环能把真实第三方 AWDL Action 帧送到用户空间 | 0027，15 帧，含本机 Mac，`build/airdrop/driver/mission-prefix/` |
| monitor 删除会触发 STOP/DELETE 顺序 WARN（不崩） | 0027 运行记录 |
| 删除超时后遗留的 monitor vdev 会在关机路径崩溃 | 0031，`dbr-teardown-chain-audit.json` |
| 崩溃是关机顺序问题：`hdd_hif_close()` 早于 `hdd_check_for_leaks()` | `wlan_hdd_main.c:14922 / :14956` |
| monitor 生命周期（建→收→删→重建→删）在 0033 上不崩，STA 关联全程保持 | `candidate-0033/capture-run2/` |
| 本项目 monitor RX **不使用** DBR 环；DBR 卸载日志的 module 1 是 CFR | `target_if_direct_buf_rx_api.h:32`，`DBR_MODULE_CFR=1` |
| 原厂 monitor ops 无 `ndo_start_xmit`，无法注入 | `wlan_mon_drv_ops` 与 `radio.sh` 的哈希拦截 |
| 共享环方案会拖慢正常 Wi-Fi | 0027 窗口期网关 43/48、最大 RTT 3.07 s；0033 复现 43/48、最大 1.84 s |

**2026-10-19 历史状态：RX 已有证据，当时 TX 未通；共存质量不达标。两轮未崩溃不足以证明删除路径安全。**
0028–0031 的候选是回归，不能作为基线；当时使用 0027（sha256 `de9cebdb…`）+ 0033
（sha256 `8ec1e5eb…`）。

## 1. 阶段划分

### S1 关机/删除安全（未完成，以下为早期记录）
- 0033：HAL 已关闭时跳过 `hal_srng_cleanup`，只释放主机侧环内存。
- 运行期契约：**monitor vdev 加载后创建一次，运行期不删除**；删除只发生在驱动卸载/重启。
- 验收：连续 3 次「加载 → 建 monitor → 收包 → 卸载」，不重启、不留 vdev 引用、
  原厂恢复后 Wi-Fi 3/3。
- **实测（2026-10-19）**：两轮通过，设备未重启（uptime 251828 → 252102）。
  第二轮 `run-monitor` EXIT=0：建 monitor → 9 个 capture slot → 940 包/12 个 AWDL Action
  → 删 → 重建 → 再删 → 卸载 → `restored`，全程 STA 关联保持，窗口后 ping 3/3。
  目前是 2 次完整往返（不是 3 次），但已覆盖「删除后重建再删除」这一最危险的序列。
- 附带修正：0033 的 guard 在 0027 基线上**从未被执行**。DBR 日志的 `module(1)` 是
  `DBR_MODULE_CFR`，与 monitor RX 无关；0001–0027 根本不注册 DBR 环。0028–0032 追错了子系统，
  不要回头继续那条线。guard 保留作为防御（只在删除超时导致 vdev 泄漏时生效）。

### S2 RAW TX（真正的阻塞点）
- 0003 只接了发送入口，`dp_vdev_attach_wifi3` 的 monitor 分支在 `dp_tx_vdev_attach` 前返回，
  TX 生命周期没有建立（README 障碍 6）。
- **静态审计已定位两个独立缺陷**（`build/airdrop/driver/tx-research/tx-path-audit.json`）：
  1. 入口要求 `con_mode == QDF_GLOBAL_MONITOR_MODE`(4)，而设备跑的是
     `QDF_GLOBAL_MISSION_MODE`(0)——**唯一能保持关联的模式**。所以入口在任何共存配置下都不可达。
  2. monitor 分支 `return` 早于 `dp_tx_vdev_attach()`，`htt_tcl_metadata` 全零、
     `tx_encap_type` 从未赋值，注入帧会被描述成 vdev 0（即 STA）。
- 0034 补丁已写好（`0034-monitor-vdev-tx-lifecycle.patch`），改这两处，**尚未编译通过**：
  重建环境缺原始 `-D`/`CONFIG_` 集合，见 `build/airdrop/driver/BUILD-RECIPE-0034.md`。
- 验收：monitor 注入一帧可被外部嗅探器收到（**不是** `send()` 返回值），
  且 STA 流量不受影响；再验 AWDL Action 帧被 iPhone 侧看到。
  自包含替代方案：注入一帧 addr2=STA、addr3=AP 的请求，让网关回包并在正常路径上看到。

### S3 链路层（filin on Android）
- 目标：`awdlmon` 上跑通 election / sync（TSFT 时间戳）/ 信道驻留。
- 验收：与真实 iPhone 完成 AWDL 同步（对端 availability window 对齐），
  能收到并回应 election，`awdl0` 上能跑 mDNS。
- 关键：TSFT 必须来自硬件时间戳；用户态到达时间不可用（见 `docs/awdl-sync.md`）。

### S4 应用层（luftlift）
- 已有：ARM64 交叉编译产物、`Discover/Ask/Upload`、dvzip/CPIO、TLS。
- 验收：Mac/iPhone → 手机、手机 → Mac/iPhone 双向文件实际落盘且内容一致，确认/拒绝行为正确；BLE 触发与联系人认证作为明确的兼容性项目验证。

### S5 常驻与功耗
- monitor 常开 = 全信道收包，功耗不可接受。需要：
  1. 固件侧只投递 Action/AWDL（按 OUI 过滤），而不是全部帧；
  2. 空闲时降低驻留占空比，有 BLE/近场触发再升到全速；
  3. 息屏/Doze 策略与 KernelSU 看护对齐。
- 验收：整夜待机电流增量可测且可接受；iPhone 分享面板仍能发现设备。

### S6 系统集成
- 从「前台服务 + 看护脚本」升级为系统级常驻（system_server 或 init 服务），
  去掉常驻通知，保留用户开关。
- 验收：重启、锁屏、Wi-Fi 切换、飞行模式往返后自动恢复，无需打开 App。

## 2. 硬约束（不要再试错的方向）

- `monitor_mode_concurrency=1` 的语义是 **STA scan + monitor**，会主动断开已连接 STA；
  不能当作联网共存开关。
- 不要在 monitor vdev 上挂 `hdd_hard_start_xmit`；必须走 DP exception 路径。
- 不要在未确认 HAL 存活时调用任何 `hal_srng_*`。
- 不要用「关联还在」判断联网正常；必须看绑定 `wlan0` 的收发、丢包和时延。
- 不要重复加载已隔离哈希（见 `rejected-candidates.json`）。

## 3. 风险与备选

| 风险 | 影响 | 备选 |
| --- | --- | --- |
| 固件拒绝从 monitor vdev 注入数据帧 | S2 卡死 | 用 P2P/GO vdev 承载 AWDL 数据，monitor 只收 |
| 共享环无法同时满足 monitor 与 STA | 共存质量不达标 | 双 pdev/DBS 或分时窗口 |
| CFI/ABI 与固定内核不匹配 | 加载即崩 | 严格用 pinned clang + 真实 provider symvers |
| 长期维护成本 | 每次 OTA 重做 | 把改动收敛成最小补丁集并保留复现脚本 |

兜底方案：USB monitor+inject 网卡（carl9170 已验证）跑现有 filin/luftlift，
Wi-Fi 不动。体验差（外置），但能在驱动路线受阻时先拿到可用互传。

## 4. 本轮（2026-10-19）交付

- 新增 `0033-dbr-hal-closed-teardown-guard.patch`，已编译并**两轮上机通过**。
- 新增 `build/airdrop/driver/mission-rx-research/dbr-teardown-chain-audit.json`。
- 新增 `build/airdrop/driver/candidate-0033/`：`build.json`（含运行期结果）、`kiwi_v2.ko`、
  `capture-run2/`（内核日志、state 目录、计数器快照）。
- 重建构建环境：pinned 源码 + pinned clang 已取回，VM 内可编译（见 README「固定来源」）。
- `volatile-stack-test.sh` 候选白名单加入 0033；`volatile-stack-rollback.sh` 守护超时 90s → 180s
  （monitor 全生命周期装不进 90s），仍然有界。

## 5. 下一步（S2 起手）

1. 读 `dp_vdev_attach_wifi3` / `dp_tx_vdev_attach`，确认 monitor vdev 在哪个分支提前返回。
2. 先做**纯静态**的 TX 生命周期设计：monitor vdev 的 TX descriptor 归属、detach/attach 对称、
   失败回收，以及它是否必须依赖 `dp_tx_vdev_attach` 还是可以走 exception path。
3. 再做一次「只建 monitor 不发包」的对照，确认当前 `send()` 失败的具体返回值（0003 已有入口）。
4. 验收必须是外部嗅探器收到注入帧，`send()` 返回 0 不算。

## 6. 本轮（2026-10-20）交付：S2 主机侧链路已通到固件

### 6.1 0036 — monitor netdev 创建时就被 pause 的 TX 队列（已通过）

0035 修掉 `noop_qdisc` 后，tracepoint 显示 `qdisc_dequeue: txq_state=0x1`，即
`__QUEUE_STATE_DRV_XOFF`：驱动主动停了队列，`sch_direct_xmit()` 永远 requeue，
`ndo_start_xmit` 进不去。源码审计闭合：

- 驱动只有 4 个 `netif_tx_stop_all_queues()` 调用点，全在 `wlan_hdd_netif_queue_control()`；
- `hdd_open_adapter()` 的 `QDF_MONITOR_MODE` case 落到 station 创建分支，结尾必然
  `WLAN_STOP_ALL_NETIF_QUEUE_N_CARRIER / WLAN_CONTROL_PATH`；
- monitor netdev 创建时是 DOWN，`__hdd_mon_open()` 从不撤销（STA 有 `__hdd_open()`
  main.c:4640 的 "Enable Tx Queues only when we are connected"，monitor 没有）；
- `transition_one_qdisc()` 不清 netdev_queue state 位，DRV_XOFF 跨 up 存活。

修复：`__hdd_mon_open()` 用 `WLAN_START_ALL_NETIF_QUEUE_N_CARRIER` 做创建时 stop 的
**精确逆操作**。结果：24 个格式完好的帧第一次真正进入 `hdd_mon_awdl_start_xmit()`
（`rejected` 0 → 26 = 2 负控 + 24 正测），普通 Wi-Fi 全程 12/12 零丢包。

### 6.2 0037 — monitor vdev 的 TX descriptor flow pool（已上机，结论明确）

0036 之后只剩 kmsg 里一行：`dp_tx_send_msdu_single: Tx_desc prepare Fail vdev_id 3
queue 3`。`queue 3` 就是 `DP_TX_GET_DESC_POOL_ID(vdev) = vdev_id`，所以缺的是
`soc->tx_desc[3]` 本身：`QCA_LL_TX_FLOW_CONTROL_V2` 下 pool 按 vdev 动态创建，
`dp_tx_desc_alloc()` 拒绝 `FLOW_POOL_INACTIVE`；STA 的 pool 由固件
HTT flow-pool-map 消息或 policy manager 的 `hdd_v2_flow_pool_map()` 创建，
monitor vdev 两者都得不到。

修复：`__hdd_mon_open()` 对 monitor vdev 调 `cdp_flow_pool_map()`（纯 host 侧，
8192 desc × 6 pool，`dp_tx_create_flow_pool()` 幂等）。

**结果：一帧真正提交进固件，固件进 RAMDUMP。**

```
submitted=1 submitted_bytes=84          (84 = 97-13，去掉 radiotap 头一帧)
cnss: MHI status cb is called with reason RDDM(4)
kiwi_v2: wlan_hdd_pld_uevent: Received firmware hang event
mhi: device ee: RAMDUMP DOWNLOAD MODE dev_state: SYS ERROR
```

且这一轮**完全没有** `Tx_desc prepare Fail`，说明 0037 达到目的。这回答了 0003/0034
都留着的未决问题：**固件不接受普通 vdev-based TCL MSDU 形式下发到未关联 monitor vdev
的裸 802.11 数据帧**。注意不存在"固件接受过同一帧"的对照组——0036 时帧根本到不了固件。

### 6.3 0038 — exception-frame 契约（已上机，结论明确）

思路：改用驱动自己给 OCB / mcopy-tx-sniffer 用的 exception 契约。HDD 侧可设的元数据
只有 `is_tx_sniffer` / `ppdu_cookie`：置位后 `dp_tx_send_exception()` 会调
`dp_tx_add_tx_sniffer_meta_data()`，填出带 `SEND_AS_STANDALONE` + `HOST_OPAQUE_VALID`
的 HTT MSDU 扩展头并置 `exception_fw`，`dp_tx_prepare_desc_single()` 前插该头并标记
`TO_FW`，`dp_tx_send_msdu_single()` 置 TCL 元数据 `VALID_HTT`——固件被告知"这是一个完整
的自建帧，单独发，不要做 peer 搜索"。

补丁：`0038-monitor-exception-frame-contract.patch`（`wlan_hdd_tx_rx.c`，
`metadata.is_tx_sniffer=1; metadata.ppdu_cookie=1`）。

**结果与 0037 逐字相同**：

```
submitted=1 submitted_bytes=84 rejected=25
cnss: MHI status cb is called with reason RDDM(4)
mhi: device ee: RAMDUMP DOWNLOAD MODE dev_state: SYS ERROR
```

### 6.4 0039 — 补齐 exception 契约的 TX 参数（已上机，结论明确）

0038 给了我们一个新工具：**能读到固件自己的 assert 原因**。
`mhi_process_sfr` 会把固件 SFR 字符串打出来：

```
mhi mhi_1107_00.01.00: [E][mhi_process_sfr] cmnos_thread_patch.c:1170:0x8Asserted in:0x18dc754:0x1801164, line#2834
```

0037 与 0038 走的是**两条不同的主机路径**（vdev-based TCL MSDU vs. exception /
TQM-bypass MSDU），却打出**逐字节相同**的 SFR。这说明固件不是在选择哪条路径上有意见，
而是在帧本身上有意见：TQM-bypass 的 MSDU 终究要变成 PPDU，而 monitor vdev
没有 rate control、没有 peer、没有 tx-spec 可退回，全零的 ext2 头等于没给固件
任何可发的东西。

0039 按树内唯一一个"自带完整 TX 参数的 host 自建 exception 帧"生产者——
`dp_tx_extract_mesh_meta_data()`（MESH 路径）——把 `htt_tx_msdu_desc_ext2_t` 填全：

- `host_tx_desc_pool = 1`：fw-api/fw/htt.h 明确写着"只要固件对 data TID 走
  TQM-BYPASS，host 就必须置位"，而 `dp_tx_prepare_desc_single()` 的
  `#if !TQM_BYPASS_WAR` 分支恰恰把每条 exception 帧都送进 bypass。
- `update_peer_cache = 1`。
- `valid_pwr / valid_mcs_mask / valid_nss_mask / valid_preamble_type /
  valid_retries / valid_bw_info / valid_encrypt_type = 1`，
  取值 OFDM preamble、mcs 0（6 Mb/s）、nss 1、20 MHz、10 dBm、3 次重试、不加密。
- **故意不设 `learning_frame`**：htt.h 写着置位后"固件会丢弃该帧而不是送进 TQM"，
  与注入目的相反（MESH 对不加密帧也清零该位）。

补丁：`0039-monitor-inject-tx-spec.patch`（`qca-wifi-host-cmn/dp/wifi3.0/dp_tx.c`）。

**结果：第三次得到逐字节相同的 SFR 断言。**

```
submitted=1 submitted_bytes=84 rejected=25
mhi mhi_1107_00.01.00: [E][mhi_process_sfr] cmnos_thread_patch.c:1170:0x8Asserted in:0x18dc754:0x1801164, line#2834
```

三次尝试给出**同一 PC**（`0x18dc754:0x1801164`）。ext2 头的内容从"只有
SEND_AS_STANDALONE"变成"完整 rate/power/bw/nss"，固件走的分支一字未变
——它压根没走到读 ext2 的地方。因此可以排除"缺 TX 参数"这一类假设。

**可用的对照组**：0036 同样 `raw_tx=Y`、monitor vdev 同样起来（5260/5220，
`vdev_start_cmd_tlv: vdev_id 3`），只是帧被 DRV_XOFF 挡在驱动里没出去——
**没有 RDDM**。所以断言确由"一帧进入固件"触发，而不是 monitor vdev 建立本身。

### 6.5 结论：主机侧已闭合，剩余是固件契约

| 候选 | 主机侧改动 | 进入固件 | 固件反应 |
|---|---|---|---|
| 0035 | netdev carrier | 否（noop_enqueue 丢） | — |
| 0036 | TX 队列解 pause | 是 | 干净接受，描述符分配失败 |
| 0037 | monitor vdev 的 TX 描述符 pool | 是 | **RDDM**，SFR A |
| 0038 | exception-frame 契约 | 是 | **RDDM，SFR A（逐字相同）** |
| 0039 | exception 契约 + 完整 TX 参数 | 是 | **RDDM，SFR A（逐字相同）** |

主机侧现在**没有任何已知缺口**：24 帧全部通过帧形校验进入
`hdd_mon_awdl_start_xmit()`，`cdp_tx_send_exc()` 接受，描述符分配成功，
TCL 元数据是正确的 vdev-based（peer_id=0xffff 落在干净分支，未置
HOST_INSPECTED），HTT ext2 头带了完整 TX spec。固件在同一 PC 上、与帧内容无关地
assert，属于**结构性拒绝**：monitor vdev 在这个固件上不是一个可承载数据帧 TX 的 vdev。

### 6.6 诊断方法论（重要，勿重复踩）

- **mksh 的 `echo` 不报告写错误**：kprobe 注册失败会被误判成"0 命中"，用 `tee` 才有输出。
- **`CONFIG_LTO_CLANG_FULL` 下模块函数的 kprobe 可能落在死的外联副本上**
  （`wlan_hdd_netif_queue_control` 全程 0 hit，而必然调用它的 `hdd_stop_no_trans`
  命中 1 次）。模块符号的"0 命中"不能当证据；只有核心内核符号
  （`dev_hard_start_xmit` / `noop_enqueue` / sch_generic）可靠。
- **`%pK` 在 Android 上打印全 0 是脱敏**，不是 NULL（曾差点误判成 vdev 为 NULL）。
- 验收用模块参数 `marionette_awdl_submitted/rejected`，不要用 kprobe 计数。
- **`mhi_process_sfr` 是唯一能拿到固件侧证据的口子**：RDDM 时固件把 SFR 字符串
  交给 MHI，主机打进 kmsg。没有它就只能看到 `RDDM(4)`，会把三种完全不同的主机
  改动误判成"同一个未解释的失败"。
- QCACLD-2 官方注入提交（`qcacld-2.0: Add packet injection in monitor mode`，
  Codeaurora 3d3ad78）证明 Qualcomm 做过 monitor 注入，但契约完全不同：
  只用 `CONFIG_HL_SUPPORT`，且让**固件解析 radiotap 头**自己取 rate/retries。
  QCACLD-3 这棵树没有该机制（TX 侧无 radiotap 解析），也不能照搬。
- `struct htt_tx_msdu_desc_ext2_t` 的 `learning_frame` 位会让固件**丢帧**而非发送。
- **设备上的 monitor/vdev 改动期间不要挂着调试串口（UART）**：24 帧那次重启的
  crash log 是 `ttyS0 uart_detect_rx` / `uart_altsource`，与 Wi-Fi 无关，
  但会把整段内核日志推进 console loglevel 冲击，让 `kmsg-reader` 的 NUL 填充
  掩盖真相。稳妥做法是拔掉串口排线，且**永远先跑 `MARIONETTE_INJECT_COUNTS=1`**。
- **持久化日志**：`cat /dev/kmsg > /data/local/tmp/kmsg-run.log &` 加一个
  `while :; do sync; sleep 0.2; done`，日志实时落盘；硬重启后仍可读
  （`/data/local/tmp` 未清）。这是 14:06 重启后能定位到注入窗口的唯一原因。

### 6.7 0040 — 换 vdev 承载同一帧（已上机，结论明确）

**2026-10-20 21:00 单帧对照（`build/airdrop/driver/candidate-0040/`）**。0037/0038/0039
用的是 monitor 自己的 vdev，每次都在第一帧拿到固件 assert；
把同一帧改由 vdev 0（*实际关联着的 STA*）承载，同一帧根本没有被提交：
```
0041-run  MARIONETTE_INJECT_COUNTS=1   submitted=0 rejected=25
0039-run                                    submitted=1 rejected=3
```
主机计数与被改的字段之间没有可解释的因果链——而 `marionette_awdl_raw_tx_vdev=0`
当时指向的 vdev 0 上没有任何 peer/搜索索引条目（STA 语义要求 peer 与地址搜索
索引），这条路径在主机侧注定被拒。0037–0039 的 `submitted=1` 才是**主机的
最后一票**：帧已经进去，是固件拒绝发送。

依据（`candidate-0040/injection-0040.log` 与设备侧 `kiwi_v2` 的
`ERR:ExecTclend vdev <n>` / `ERR:Peercr` 序列）：
- 0040 帧一定**没有进入**任何 TQM/TCL 队列，也未进入 peercr 路径；
- 0037–0039 帧**进入了**该路径，`ERR:ExecTclend` 与 monitor vdev 相关，
  PC 恒为 `0x18dc754`（三种元数据组合零位移），即主机提交点不是缺陷所在。

### 6.8 0041 — 精确定位主机侧的"静默 drop"（待编译）
`0041-awdl-tx-drop-tracing.patch` 给 `hdd_mon_awdl_start_xmit()` 的 15 个
drop 点编号、给 `dp_tx_send_exception()` / `dp_tx_send_msdu_single()` 的
每次拒绝加打印。**不动任何行为**，只回答"帧在哪一步被拒"。
预期读到的日志：
- `marionette: tx drop site=N` → 主机某个门拒了（N 见补丁头）；轨迹走到 vdev 0
  的最大嫌疑是 `dp_tx_vdev_get_ref_by_id` 失败（vdev 0 上没有 AWDL 的 peer）。
- `marionette: exc ... send queued` → 帧已交给硬件，040 的拒绝发生在
  `tx_hw_enqueue`，继续追 PPDU 为何被 TQM 丢弃。

### 6.9 0041 之后的判定树
```
读 site=N?
├─ 1..15  → 主机门。site=3/11/13 是帧形状/地址问题；site=16 是 CDP 层拒绝，
│            用 dp_tx 的打印决定是 vdev ref、desc pool 还是 peer 索引。
└─ 16     → CDP 返回了 skb；跟 dp_tx.c 的打印：
             ├─ vdev ref failed / desc pool → 主机资源问题，可修
             ├─ hw_enqueue status != 0     → TQM 拒绝：peer/速率/加密
             └─ send queued + SFR assert  → 固件契约：换 P2P/GO vdev（原方向 1）
```

## 7. 下一步（待 0041 结果）

1. 跑 `MARIONETTE_INJECT_COUNTS=1 sh volatile-stack-test.sh run-rawtx-sta-vdev …`，
   读 `marionette: tx drop site=` 与 `marionette: exc`。
2. 若 `send queued` 且再次 SFR：确认"固件不接受任何 host-built 帧"，则按
   方向 1 实现 P2P/GO 承载（0034 的 `dp_tx_vdev_attach` 分支已就绪，
   只差 GO vdev 的创建与 `marionette_awdl_raw_tx_vdev` 指向它）。
3. 若 host 侧可修：补 peer/搜索索引后重测，再回到 monitor vdev 承载
   （0035/0036 的 netdev 队列/载波修复已让入口可达）。

## 8. 0042 与异常路径的终局（2026-10-21）

### 8.1 0042 = 0041 + 保留 radiotap 内联
`0042` 唯一改动是把 `hdd_mon_awdl_start_xmit()` 里的 `skb_pull(skb, 13)`
注释掉，让固件收到 radiotap 前缀而不是裸 802.11 帧。动机是 QCACLD-2 的
monitor 注入从不剥离 radiotap——那里的固件自己解析 `it_present` 取速率/重试。

两次运行，两个结论：

| 载体 vdev | 结果 |
|---|---|
| STA vdev（`run-rawtx-sta-vdev`） | **宿主断言**，`dp_tx_hw_enqueue_be` `dp_be_tx.c:816` |
| monitor vdev（`run-rawtx`） | 宿主接受（`submitted=1 rejected=2 submitted_bytes=97`），固件仍 SFR 断言，**PC 与 0037–0039 完全相同** |

### 8.2 STA 载体撞上的宿主断言是元数据错误，不是固件拒绝
```
Assertion failed! (tx_exc_metadata->tx_encap_type == 6) ||
                 (tx_exc_metadata->tx_encap_type == vdev->tx_encap_type)
```
`6` 是 `CDP_INVALID_TX_ENCAP_TYPE`。异常路径的 `metadata.tx_encap_type` 要的是
**cdp encap 类型**，而 0037 起一直塞的是 `htt_cmn_pkt_type_raw`（= 0）。
monitor vdev 的 `tx_encap_type` 恰好也是 raw（`dp_main.c:7128`），所以
0037–0039 能过这道断言直达固件；STA vdev 是 ethernet，于是必然撞断言。
→ **0040/0041 的 STA-vdev 结果从未到达固件，不能作为固件证据。**

### 8.3 固件判死
monitor vdev + radiotap 内联 + 良构帧 → 固件断言
`cmnos_thread_patch.c:1170 ... 0x18dc754:0x1801164, line#2834`，与裸帧逐位相同。
帧内容（含 radiotap）、载体 vdev、tx-spec 全部不影响断言位置。
**固件在解析帧之前就拒绝，异常路径（TQM-bypass）不可用。**

宿主侧内核这次没有 panic，只有 `RDDM_DONE(10)` + SSR 恢复，STA 掉线后重连。

## 9. 转向固件原生发射命令（进行中）

`wlan_hdd_mgmt_tx()` **不校验帧类型**：只特判 auth，其余原样交给
`wlan_cfg80211_mgmt_tx()` → WMI MGMT_TX。挡住数据帧的是 cfg80211 里一行
`if (!ieee80211_is_mgmt(mgmt->frame_control)) return -EINVAL;`。

`0043` 在 0002 的 AWDL 动作帧门旁边再加一道数据帧门（STA + root 参数 +
data 帧 + LLC/SNAP `aa aa 03 00 17 f2 08 00`），让 AWDL 数据帧走 WMI MGMT_TX。
这条命令是固件原生支持的（P2P/action 帧日常走它），不涉及异常路径。

### 9.1 已验证的工具与结果
`native/airdrop/tools/awdl-mgmt-tx.c`（NDK 静态编译）：
- 常量必须从目标内核头文件读出来，不能手抄。第一版把
  `NL80211_CMD_FRAME` 写成 54（实为 59）、`DONT_WAIT_FOR_ACK` 写成 52
  （实为 142），内核把那条请求当成别的命令并回了成功——**假阳性看起来和
  发射成功一模一样**。现在 `freq=0` 返回 `-22`、非 AWDL 类别返回 `-107`，
  负对照能咬住。
- 单播到真实 AP 的 public action 帧 `--ack`：3/3 `mgmt TX status: acked`。
  这是"空口真的发出去了"的正面证据。
- 广播 AWDL 动作帧（`--dst` 省略）：接受且报告完成。**广播帧的 ack 只是
  驱动报告完成，不是对端 ACK，不能当空口证据**（与 README 2026-10-16 一致）。
- `--data`：当前 `-22`，即被 cfg80211 的 `is_mgmt` 挡住——正是 0043 要开的门。

### 9.2 结果：AWDL 数据帧已上空口（2026-10-21 14:35）

0043（cfg80211，189/189 导入匹配）+ 0044（qcacld，508/508 导入匹配）联调：

| 测试 | 结果 |
|---|---|
| 数据帧发往 AP（5260，`--dst` 单播） | `mgmt TX status: acked` ×3 |
| 数据帧广播到 2437 / 5220 / 5745 | 接受，`no ack`（广播正常） |
| ch6 单播打"只在 5260 存在的 AP MAC" | **acked** → 证明驱动忽略了 freq 参数，实际仍在当前信道发 |
| `iw dev wlan0 roc start 2412 800` + 2412 单播打 YUNI-OFFICE-2.4G | **acked** → 真·空口证明 |

最后一行是决定性的：`94:83:c4:ae:61:70` 只存在于信道 1，它 ACK 了，说明
帧确实在 2412 上发出去并被第三方设备收到。链路：
`cfg80211 0043 → wlan_hdd_mgmt_tx → p2p_process_mgmt_tx → WMI MGMT_TX → 固件 → 空口`。

ROC 结束后 STA 自动回到 5260 并恢复关联，网关 ping 3/3，无崩溃、无重启。

### 9.3 由此确定的发射模型

- **必须先 ROC 再发**：`NL80211_ATTR_WIPHY_FREQ` 单独给会被忽略（wiphy 没有
  `WIPHY_FLAG_OFFCHAN_TX`）。正确姿势是 `NL80211_CMD_REMAIN_ON_CHANNEL`
  把 vdev 调到目标信道，然后在 ROC 窗口内发帧。
- **ROC 会短暂中断 STA**：单射频的必然结果，AWDL 本身就是靠信道跳变工作。
- **速率由固件 MGMT_TX 决定**：目前是最低基础速率，尚未做 AWDL 要求的
  6/12/24 Mbps OFDM 控制，这是体验层要补的下一项。
- **地址不受限制**：addr1/addr3 都不被 cfg80211 或驱动检查，可以填 AWDL
  BSSID `00:25:00:ff:94:73` 和对端地址。

### 9.4 下一步
1. `filin` 侧把 `awdl-raw-tx` 的发送后端从 packet socket 换成 NL80211 mgmt_tx
   （工具已有完整参考实现 `native/airdrop/tools/awdl-mgmt-tx.c`）。
2. 做 ROC 调度：在 6/44/149 之间按 AWDL 可用窗口跳变，同时保留 STA 的
   周期性回连。
3. 速率控制：确认 MGMT_TX 用的速率，必要时用 WMI 的 rate 字段或改用
   普通数据路径的 peer 发送。
4. 端到端：iPhone 侧看到 awdl0 对端 → OPACK → 传输。

## 10. 真实 AWDL 帧格式 + 首次被 Apple 设备 ACK（2026-10-21 下午）

§9 的发射链路已经打通，但当时发出去的 body 是手搓的合成 TLV，Mac 的 `awdl0`
从不回 ACK。本轮把格式问题彻底解决，并第一次拿到真实 Apple 设备的空口回应。

### 10.1 手搓帧有两个确定的格式错误

把 C 工具里的构造和 `crates/filin-rs` 里从 owl 移植过来的实现逐字节比对，
发现两处错误，任何一处都会让 Apple 对端直接丢弃：

| 位置 | 工具原值 | 正确值 |
|---|---|---|
| 三地址数据帧 addr3 | 对端 MAC | AWDL BSSID `00:25:00:ff:94:73` |
| AWDL data head（LLC/SNAP 之后） | 序号 | `0x0403` |

结论：**AWDL 帧格式只有一个真相来源**。现在由 `filin-rs` 生成字节，
注入器只负责送出去（`--plan` 模式）。

### 10.2 新的帧生成路径

- `runtime::build_announce_80211()`：PSF/MIF 公告帧，**不带 radiotap**。
  `NL80211_CMD_FRAME` 要的是纯 MAC 帧，驱动/固件自己补 PHY 头；带 radiotap
  前缀反而会被当成 802.11 头发出去。原 `build_announce_frame()` 保留给
  monitor 注入路径，内部改为调用它再加前缀。
- `crates/filin-rs/src/bin/awdl-emit.rs`：输出带时序的回放计划
  `<offset_us> <KIND> <len> <hex>`。**整份计划共享一个 AW 时基**——`SyncState`
  的相位是相对 `last_update_us` 算的，逐帧起新进程会让相位每帧重置，对端永远
  同步不上。
- `announce_wire_tests`（6 项，在设备上跑通）：用 RX 侧解析器反解自己的输出，
  锁住 TLV 顺序 PSF=[4,5,18,24,6,12,21] / MIF 多 7、16，以及 addr1/addr2/addr3、
  sync TLV 首字节=锚信道、chanseq=`0f 03 00 03 ffff`+16×(chan,opclass)、
  data-path social bits、data head=`0x0403`。全部 232 项测试通过。

### 10.3 ACK 判定必须由注入器自己做

`iw event` 的 stdout 是块缓冲，`kill` 会丢掉尾部整段日志——第一轮据此算出的
计数是错的（38 个探测只看到 8~10 个 status）。现在注入器订阅 nl80211 的
`mlme` 组，自己收 `NL80211_CMD_FRAME_TX_STATUS`。

两个必须记住的驱动行为：

- cfg80211 对 `CMD_FRAME` 回**两条**消息：type=family 的 genl 回复（带
  `NL80211_ATTR_COOKIE`），然后才是 `NLMSG_ERROR` ack。cookie 在前者里。
- 本驱动给每个 `CMD_FRAME` 的 **cookie 恒为 256**，所以只能按到达顺序归属，
  不能按 cookie 值匹配。每帧发送前要排空积压事件，否则上一帧迟到的 status
  会被算成下一帧的 ACK（假阳性）。

### 10.4 ROC 时长上限 2000 ms

`iw dev wlan0 roc start <freq> <ms>`：2000 接受，**2048 返回 -EINVAL**。
`cfg80211` 用 `wiphy->max_remain_on_channel_duration` 卡这个值。常驻调度必须
按 ≤2000 ms 切片。

### 10.5 结果

注入器自带 ACK 判定后，先做对照（同一信道、同一条件）：

| 对照 | 目标 | ACK / 总数 |
|---|---|---|
| 阳性 | 自家 AP BSSID（5260，无 ROC） | **10 / 10** |
| 阴性 | 未使用的 `02:00:00:00:00:99`（5260） | 0 / 10 |
| 阴性 | 同上，改在 2437 + ROC 下 | 5 / 114（三轮） |

阳性 10/10、阴性 0/10 说明信号本身是准的；ch6 上 4.4% 的本底是驱动上报的
残留噪声，已记录为已知误差。

对真实对端（本机 Mac 的 `awdl0` = `02:57:dc:2d:32:26`），38 个单播数据帧/轮：

| 信道 | 轮次 | ACK / 总数 | 命中率 |
|---|---|---|---|
| 2437 (ch6) | 3 | **23 / 114** | 20.2% |
| 5745 (ch149) | 3 | **17 / 114** | 14.9% |
| 5220 (ch44) | 1 | 0 / 38 | 0% |

命中率是本底（4.4%）的 3.4~4.6 倍，二项检验 z≈3.8、p<0.001。20% 的命中率
与 AWDL 可用窗口约 25% 的占空比吻合——帧只在 Mac 的 AW 内才可能被 ACK。

**这是本项目第一次证明：自建的 AWDL 数据帧被真实 Apple 设备的 `awdl0` 在
空口上接收并回 ACK。** ch44 为 0 说明该时刻 Mac 的 AWDL 不在 ch44 上。

### 10.6 尚未证明的部分（不要过度解读）

- ACK 只证明"帧到了、被听到了"，**不证明对端已把本机纳入 AWDL 对端表**。
  后者要求对端接受我们的 election / sync TLV 并进入同步状态。
- 仍未收到对端的 PSF（RX 侧在 STA 路径收不到 AWDL 帧，0006~0008 已证伪），
  所以无法从本机确认同步是否建立。
- 速率仍由固件 MGMT_TX 决定（最低基础速率），未做 AWDL 的 6/12/24 Mbps 控制。

### 10.7 下一步
1. 让 Mac 侧 AirDrop 面板出现本机名字（MIF 的 ARPA TLV 已带 `marionette`），
   这是"对端接受公告"的可视判据。
2. 把 ROC + 回放做成常驻调度（≤2000 ms 切片，6/44/149 轮转 + 回连）。
3. 补速率控制。
4. 解决 RX：目前唯一能看到真实 AWDL 帧的路径是 monitor，且与 STA 共存受限。

## 11. RX 定论：受支持路径确认收不到 AWDL 帧（2026-10-21 傍晚）

§10 打通了 TX，但 AirDrop 面板不会因为 ACK 出现。面板要的是 RX：没有 RX 就没有
同步、没有 mDNS、没有发现。本节用受支持路径把 RX 这件事查到底。

### 11.1 先把主机侧的门查清（结论：门是开的）

`NL80211_CMD_REGISTER_FRAME` 之前一直返回 -22，一度被当成驱动限制。实际是两件事：

1. **`NL80211_ATTR_FRAME_MATCH` 在本内核是强制的**。`nl80211_register_mgmt()`
   第一句就是 `if (!info->attrs[NL80211_ATTR_FRAME_MATCH]) return -EINVAL;`。
   不带这个属性 → -22，看起来和"驱动不支持"一模一样。
2. `wlan_hdd_txrx_stypes[NL80211_IFTYPE_STATION].rx` **本来就包含**
   `BIT(SIR_MAC_MGMT_ACTION)`（`SIR_MAC_MGMT_ACTION = 0xD = 13`），
   `wlan_hdd_cfg80211.c:553`。所以 action 帧的 rx 位一直是置着的。

常量也必须按目标内核取：`NL80211_ATTR_FRAME_TYPE = 101`（不是常见的 52）、
`NL80211_CMD_REGISTER_FRAME = 58`、`NL80211_CMD_REMAIN_ON_CHANNEL = 55`。

### 11.2 阳性对照：mgmt RX 交付是活的

`wlan0` 在 2437 上 `--rx-ms 2000 --type 0x0040 --match-hex 00`：
**收到 11 个 probe request**，帧体完整（`4000...` = FC 0x0040）。
同一接口、同一信道、同一套注册/ROC 代码路径。所以注册、ROC、cfg80211 交付、
mlme 组订阅全部正常。

### 11.3 AWDL 帧：稳定为 0

`--rx-ms 2000`（6 字节 match `7f 00 17 f2 08 10`，注册返回 0，ROC 返回 0）：

| 信道 | 轮次 | rx_frames |
|---|---|---|
| 2437 | 3 | 0 / 0 / 0 |
| 5745 | 2 | 0 / 0 |

而 §10 已证明 Mac 的 `awdl0` 在 2437 上有 ~20% 的时间在听（并 ACK 我们的帧）。
**同一个信道、同一个时刻，它能收到我们的单播数据帧，我们收不到它的广播 action 帧。**

### 11.4 结论

固件在 STA 模式下按地址/BSS 过滤 RX：来自未知 BSSID
（`00:25:00:ff:94:73`）的广播 action 帧在 RX filter 就被丢掉，根本不会产生
WMI 管理帧事件。这与 0007 在 WMI 层观察到的 AWDL 计数 0 一致，但这次是通过
受支持路径 + 阳性对照确认的，不再是"某次实验没收到"。

**要 RX，只能让固件进入不过滤的状态——目前唯一被证明能收到 AWDL 的是
global monitor（con_mode=5），而它与普通 Wi-Fi 互斥。**

### 11.5 一处需要更正的旧结论

§9.3 写的"wiphy 没有 `WIPHY_FLAG_OFFCHAN_TX`"**是错的**：
`wlan_hdd_cfg80211.c:18490` 无条件 `|= WIPHY_FLAG_OFFCHAN_TX`。所以"必须 ROC
才能换信道"这个**实测现象**成立，但当初给的解释不成立。真正原因在
`wlan_hdd_mgmt_tx` 对 `NL80211_ATTR_WIPHY_FREQ` 的处理，需要重新查。
若 offchannel TX 其实可用，常驻调度可以省掉 ROC 切片，这一点值得优先复核。

### 11.6 下一步（需要选路）
1. **复核 offchannel TX**：若成立，TX 调度大幅简化（无需 ≤2000ms 切片）。
2. **RX 只有两条路**：接受 monitor 独占（AirDrop 与普通 Wi-Fi 二选一），或继续
   攻固件 RX filter（0020 的 promiscuous 命令无效，需要新的证据/入口）。
3. 在 RX 解决前，端到端（iPhone 发现 → OPACK → 传输）无法推进。

---

## 12. RX 已打通：保持关联的占空比捕获（2026-10-21 傍晚）

**§11 的结论是错的，而且错得很关键。** §11 只测了 cfg80211 `REGISTER_FRAME` 这
一条路（wlan0 + ROC），那条路确实收不到 AWDL。但项目里**早就存在另一条路**：
0021/0027 的 monitor vdev 共享环 raw RX，它绕开 STA 的管理帧过滤。README 第 5 行
记着 2026-10-16 的实测（15 个 AWDL 帧、含本机 Mac），后来因为转攻 WMI TX 而没有
和 TX 合并过。本轮把这条路重新跑通，并第一次给出**可长期运行的工作点**。

### 12.1 操作路径（已验证）

```
# 前提：marionette_awdl_sta_monitor=Y（默认），con_mode=0，STA 已关联
iw phy phy3 interface add awdlmon type monitor
ip link set awdlmon up
# 内核日志确认：MARIONETTE_STA_MONITOR_INIT_RESULT ret=0
#              MARIONETTE_MONITOR_RAW_RX vdev=N type=0 queued_status=0
marionette-awdl-witness awdlmon 15 &          # 或 marionette-awdl-capture
echo 2437:200 > /sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms
```

捕获参数的格式是 **`<频率MHz>:<毫秒>`**，频率只接受 2437/5220/5745，毫秒 1..200。
写 `6:200` 会被当成非法频率返回 `-EINVAL`（`echo` 只报 1，极易误判为权限问题）。

### 12.2 满占空比会掉线（第一次失败）

连续背靠背写时隙（无间隔），3 秒内写了几十次，10 秒后 STA 报
`ROAM_TRIGGER Reason: "BEACON MISS"` → `locally-generated disconnect
QCA_DISCONNECT_REASON_BEACON_MISS_FAILURE`。

原因：时隙期间共享 mission RX 环整体被导向 monitor，STA 自己的 beacon 也收不到；
而 `policy_mgr_update_connection_info: can't find vdev_id 2 in pm_conc_connection_list`
说明 policy manager **根本没登记 monitor vdev**，因此不会为 STA 让出 beacon 时机。
这是需要后续修的根因（把 monitor vdev 注册进并发表），但在修好之前，占空比就够了。

### 12.3 占空比工作点：200ms 时隙 + 400ms 间隔

| 指标 | 结果 |
|---|---|
| 时隙 | 200 ms（`2437:200` / `5745:200` 交替） |
| 间隔 | 400 ms |
| 12 秒收到 AWDL action 帧 | **86** |
| 期间 `wlan0` | 全程保持关联 5260 |
| 期间 ping 网关 | **6/6，0% 丢包，avg 11.2 ms，max 14.4 ms** |

**这是本项目第一个「普通 Wi-Fi 无感 + 稳定收 AWDL」的工作点。**

### 12.4 收到的确实是真实 AWDL 生态

12 秒内 8 个不同 AWDL 设备，PSF（subtype 0）与 MIF（subtype 3）都有：

```
02:57:dc:2d:32:26  ← 本机 Mac
b2:15:77:16:5c:74  52:33:4b:eb:5f:19  8a:41:5d:dd:62:cb
ba:99:79:dc:a4:1c  2e:1a:3f:c6:56:08  c6:42:8c:98:bc:9e
ce:8f:4e:e4:b2:fd  06:4e:bc:59:aa:9f  16:8f:1b:0e:e3:c7
```

证据：`build/airdrop/driver/coexist-rx/frames-2026-10-21.log`（45 个完整帧）。

### 12.5 由此得到的真实线上格式（重要，直接修正 filin-rs）

对 45 个完整帧逐字节解析，`TLV` 链从**帧偏移 40** 开始（= action body 偏移 16），
此前按 39 解析会得到垃圾 type。header 16 字节：

```
24: 7f 00 17 f2 08 10      vendor (category 0x7f, OUI 00:17:f2, type 08, subtype 10)
30: 00 / 03                AWDL subtype (PSF / MIF)
31: 00                     保留字节（filin-rs 已有，勿删）
32: phy_tx    (u32 LE)
36: target_tx (u32 LE)
40: TLV 链（type u8 / length u16 LE / value）
```

真实 TLV 序列：

| | 序列 |
|---|---|
| PSF | `4, 5, 18, 19, 6, 24, 12, 7, 17, 21, 33` |
| MIF | `4, 5, 18, 24, 12, 7, 17, 16, 21, 6, 2×9` |

对照 `runtime::build_announce_tlvs`（当前 `4,5,18,24,6,[7,16],12,21`）的差异：

1. **5 与 18 的语义搞反了**。真实 TLV 5（21 字节）带 master 地址
   `c6:42:8c:98:bc:9e`，是 **sync tree**；真实 TLV 18（41 字节）以
   `0f 03 00 03 ff ff` 开头，是 **election params**。我们现在把 election 写进 5、
   把 channel sequence 写进 18。
2. **PSF 缺 19 / 17 / 33**，且 24 的位置应在 6 之后。
3. **TLV 4 是 73 字节**（含 master 地址、`2b0600001d97` 这类计数器、channel 相关
   字段），远大于"12 字节 sync params"的直觉。
4. **MIF 的 TLV 2 是服务描述**，每个 2 号 TLV 内是 `type(u16) len(u16) 名字 + TXT`。
   实测到的真实内容：

```
_applicationseervicepairing   → sn=com.apple.sharingd.AirDrop, at=..., sid=E265363C-...
_appsvcprepair                → 同上
dmn=刘强强 Mac Pro            → 设备名（UTF-8）
flags=111611
```

**这就是"Mac 能不能看见我们"的判据**：MIF 里必须带正确的服务 TLV，光有 PSF 不够。

### 12.6 一条必须记住的约束

**monitor vdev 存在时 Wi-Fi 无法重新关联。** 实测：STA 掉线后带着 `awdlmon` 反复
`svc wifi disable/enable` 都连不上，`iw dev awdlmon del` 之后立刻恢复。
所以常驻服务必须按连接状态管理 `awdlmon` 的生命周期：关联成功后再建，检测到掉线
先删再让系统重连。

### 12.7 下一步
1. 按 §12.5 修正 `build_announce_tlvs`（5/18 语义、TLV 集合与顺序、TLV 4 长度、
   MIF 服务 TLV），再抓真实帧做逐字节回归。
2. 把 TX 塞进同一个时隙：时隙期间射频已经在社交信道上，可省掉 ROC。
3. 修 `policy_mgr` 不登记 monitor vdev 的问题，从根上消除 beacon miss。
4. 用 MIF 服务 TLV 让 Mac 的 AirDrop 面板出现 marionette。

## 13. TX 为什么没有真的上天：三个独立缺陷的分离

§12 之后的目标是"完整 AirDrop 服务"，也就是 RX→TX→发现 三段打通。这一节记录
把 TX 卡点拆成三个互不相关的缺陷的过程，以及每个缺陷的实测证据。

### 13.1 缺陷一：ROC 只能时间片共享，不能独占驻留

§11.5 怀疑 `wlan_hdd_mgmt_tx` 忽略 `NL80211_ATTR_WIPHY_FREQ`，本轮证实了。
但更重要的发现是**ROC 也不是解法**。

判决性测试：`roc_start(2437, 2000)` 期间并发 ping 网关。

```
10 packets transmitted, 10 received, 0% packet loss
rtt min/avg/max/mdev = 8.190/21.310/52.017/14.850 ms
```

0% 丢包，但 seq 4/5 的时延从 ~14 ms 跳到 52/48.8 ms，正好落在 ROC 窗口内。
结论：**cfg80211 的 ROC 在这个驱动上被 policy manager 实现为与 STA 的时间片共享**，
它会按时返回主信道取信标和缓存帧。这解释了为什么 ROC 不丢链路——但也意味着
它永远无法把电台"停"在社交信道上。

### 13.2 缺陷二：捕获槽绕过 policy manager，信标监视器照常计时

捕获槽走 `wlan_hdd_set_mon_chan` 直接改 monitor vdev 的信道，policy manager
完全不知道这次离信道，所以 STA 的信标看门狗继续计时。实测：

```
ROAM_TRIGGER Reason: "BEACON MISS"
locally-generated disconnect ... QCA_DISCONNECT_REASON_BEACON_MISS_FAILURE
```

阈值大约 10 个连续丢失信标（100 ms 间隔即约 1 秒）。一旦掉线，链路就没了，必须
`iw dev awdlmon del` 之后让系统重连。

> **更正（§14.1）**：这里曾经把后续 TX 的 `-107 ENOTCONN` 归因于掉线。**那是错的。**
> `-107` 来自 cfg80211 的 AWDL 放行开关 `marionette_awdl_mgmt_tx` 默认关闭，
> 与链路状态无关：链路好好的时候也一样返回 `-107`。详见 §14.1。

工作点因此受硬约束：**离主信道的时间必须显著小于在信道的时间**。§12.4 的
200 ms 槽 + 400 ms 间隔能活 12 秒，150/300 立刻死，就是这个比例关系。

### 13.3 缺陷三：每个槽的换信道开销约 280 ms

槽是同步的，写一次参数的墙钟时间 = 驻留 + 换信道开销。实测 6 个 200 ms 槽
在 600 ms 的循环里落在 870–910 ms 间隔，即**每槽额外约 280 ms**。这 280 ms
同样不在主信道上，于是"短驻留 + 长间隔"的工作点根本做不出来：驻留压到 60 ms
时，开销仍占 340 ms，社交信道占空比跌到 7%，没有实用价值。

### 13.4 因此正确的机制是分离两个关注点

三条缺陷指向同一个设计错误：**把"装滤镜"和"移信道"绑在一起做**。

- 装滤镜必须是驱动内部的（`marionette_capture_phase`），因为 monitor 的全套
  RX 滤镜只在这个相位里生效；
- 移信道必须走 policy manager 认可的路径（STA 上的 ROC），否则信标看门狗会杀人。

补丁 0045（`marionette_awdl_capture_hold`）把两者拆开：参数只切滤镜，不碰信道。
用户态用普通的 ROC 驱动信道，于是 policy manager 按计划让出信标窗口，链路存活，
而滤镜在整个 ROC 窗口里保持开启。

### 13.5 本轮排除掉的假设

- **`pkt_capture` 捷径**：`CONFIG_WLAN_FEATURE_PKT_CAPTURE` 只在
  `CONFIG_CNSS_QCA6490 && CONFIG_ARCH_LAHINA` 下开启，构建出的模块里没有
  `packet_capture_mode` 字符串。已排除。
- **"钉在 5220 白拿一个社会信道"**：`LIMO-5G ceshi` 确实同时广播在 5220，
  如果 STA 关联在 5220，槽就是 `home == target`、零换信道。但实测三个社会信道的
  AWDL 流量为 **2437: 11 帧 / 5220: 0 帧 / 5745: 16 帧**，本地 Apple 设备不用 5220。
  这条捷径不成立。
- **监视器创建会打断关联**：实测建 `awdlmon` 后 4 秒内每 400 ms 采样，链路始终
  Connected。真正打断链路的是槽，不是建监视器。
- **不写槽也能收帧**：不写槽时 `CAPTURE_SUMMARY total=0`，连非 AWDL 帧都没有。
  滤镜相位才是 RX 的开关，ROC 单独对 RX 完全无效。

### 13.6 关于自己发的帧能否被自己收到

15 个不同发送者的 60 个 AWDL 帧里，`our_tx_seen=0`。monitor 不回环自己的发射，
所以"监视器看见我们的帧"不能作为辐射证据。**判据只能是 Apple 设备开始向我们
单播**（`frames_addressed_to_us`）。

## 14. TX 打通：Apple 设备开始 ACK 我们

§13 把"装滤镜"和"移信道"拆开之后，TX 第一次被 Apple 设备在 802.11 层确认。
这一节记录最终生效的机制、判据，以及三个曾经把方向带偏的误判。

### 14.1 误判一：`-107` 不是掉线，是 cfg80211 的放行开关没开

`cfg80211_mlme_mgmt_tx` 对非 public 的 action 帧有一道严格检查：

```c
if (ieee80211_is_action(mgmt->frame_control) &&
    mgmt->u.action.category != WLAN_CATEGORY_PUBLIC &&
    !cfg80211_marionette_awdl_action(wdev, params)) {
        ...
        case NL80211_IFTYPE_STATION:
                if (!wdev->connected) { err = -ENOTCONN; break; }
                if (!ether_addr_equal(wdev->u.client.connected_addr, mgmt->bssid)) {
                        err = -ENOTCONN; break;
                }
```

AWDL 用的是自己的 BSSID（`00:25:00:ff:94:73`），永远不等于关联 AP 的 BSSID，
所以只要放行函数返回 false 就一定 `-ENOTCONN`。放行开关是 **cfg80211 模块参数**
`marionette_awdl_mgmt_tx`，**默认 N**：

```
/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx = N   →  -107
                                                          Y   →  0
```

它和 WLAN 驱动无关，所以 `awdl-stack-load.sh` 重载驱动栈时必须单独打开它。
**链路正常时也会返回 `-107`**，这是本轮最贵的误判。

### 14.2 误判二：monitor 存在时 ROC 的时长上限从 2000 ms 掉到 200 ms

实测矩阵（wlan0，`--roc-only`）：

| 频点 | 200 ms | 250 ms | 1500 ms |
|------|--------|--------|---------|
| 2437 | 0      | -22    | -22     |
| 5220 | 0      | —      | -22     |
| 5745 | 0      | —      | -22     |
| 5260 | -22    | —      | -22     |

两条结论：
1. **对 STA 自己的主信道做 ROC 一定失败**（`-22`），合理；
2. **有 monitor vdev 时 ROC 上限正好 200 ms**（250 就 `-22`），而没有 monitor 时
   2000 ms 可用。所以长计划必须用 **200 ms 切片链**覆盖，`roc_keep` 里
   `ROC_SLICE_MS = 200`、余量 `ROC_MARGIN_MS = 50`，实测一次 1.5 s 的计划
   `roc_rearms=7`、`roc_failed=0`。

`EBUSY` 要当成"窗口还在"而不是失败：cfg80211 不允许原地替换进行中的 ROC，
下一帧再试即可，不要为了重开窗口把已经进入的窗口拆掉。

### 14.3 最终生效的机制

```
echo 1 > /sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold   # 只开滤镜
marionette-awdl-mgmt-tx wlan0 2437 1 --plan <plan> --roc               # 200ms ROC 链 + 发射
echo 0 > /sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
```

- `capture_hold`（补丁 0045）只切 `marionette_capture_phase`，**不碰信道**；
- `--roc` 用 cfg80211 正式 ROC 驱动信道，policy manager 登记在案，所以信标有保障；
- 于是射频真的停在 2437，滤镜全程有效，STA 不掉线。

实测一轮（1.5 s 计划、28 帧）：

```
plan_frames=28 submitted=28 rejected=0 roc_rearms=7 roc_failed=0
CAPTURE_SUMMARY total=943 mgmt=157 apple_oui=72 awdl=72
freq histogram: 72 freq=2437        ← 全部收在社会信道上
link after: Connected
```

对比 §13 的槽方案：同一时间段只有 8 个 AWDL 帧、全部 `freq=5260`（根本没换到
社会信道），而且 1–2 秒内必掉链路。

### 14.4 判据：Apple 设备的 ACK

monitor 不回环自己的发射（`our_tx_seen=0`），所以"自己看见自己"不能当证据。
**唯一硬证据是单播帧的 802.11 ACK**：只有帧真的上了 2437、地址和帧结构都对，
对端才会回 ACK。

发给 Mac 的 AWDL 地址 `02:57:dc:2d:32:26`，单播 DATA 探针：

```
DATA off=    5000 len= 88 cookie=259 no_ack
DATA off=   55000 len= 88 cookie=259 no_ack
DATA off=  105000 len= 88 cookie=257 ACKED     ← 决定性
DATA off=  155000 len= 88 cookie=258 no_ack
plan_frames=8 submitted=8 rejected=0 unicast_acked=1 unicast_noack=3
```

四轮重复：`1 / 1 / 1 / 0`，合计 **4/16 ≈ 25%**。这个比例正好对应 AWDL 可用窗口
的占空比——对端只在自己的窗口里收，我们发在窗口外就不回 ACK，是正常行为，
不是帧被拒。

结论：**TX 链路层已打通**。我们的 AWDL 帧能被 Apple 设备正确接收并确认。

### 14.5 还没做到的

- **发现**：对端仍未向我们单播（`frames_addressed_to_us=0`）。ACK 只证明帧合法，
  不证明我们的 PSF/MIF 被接受为同步源。下一步要按对端可用窗口对齐发射时机，
  而不是均匀撒点。
- `FELL_BEHIND`：`--ack` 的 300 ms 状态等待会把计划时序拉长，统计用没问题，
  测时序时要换成 `DONT_WAIT_FOR_ACK`。
- **MAC 不稳定**：连接后是随机 MAC（`1e:5c:68:7b:e5:80`），未连接时是硬件 MAC
  （`e8:f7:91:1f:f8:fe`）。计划里的 addr2 必须等于发送接口当时的 MAC，
  否则 cfg80211 直接 `-EINVAL`。常驻服务必须先把身份钉死。

## 15 加入簇实验（身份）：结论是"必要但不充分"

### 15.1 观察

10 秒监听（`roc-listen`，464 帧）里 9 个节点全部属于同一个簇：

```
tx=c6428c98bc9e tlv4.master=2e1a3fc65608 tlv5=0902 n=73
tx=064ebc59aa9f tlv4.master=2e1a3fc65608 tlv5=0902 n=71
tx=0257dc2d3226 tlv4.master=2e1a3fc65608 tlv5=0902 n=66
...
tx=2e1a3fc65608 tlv4.master=2e1a3fc65608 tlv5=0902 n=7   <- 主自己
tx=5a0abcae5a1f tlv4.master=5a0abcae5a1f tlv5=1702 n=6   <- 自称主的孤立节点
```

最后一行是对照组：**唯一一个自称主的节点（`5a0abcae5a1f`）确实是孤立的**，
没有任何其他节点引用它。这正是我们此前的位置。

TLV 24 的两个 6 字节段（`[0:6]` 与 `[6:12]`）对每个叶子都等于簇主地址，
不是发送者自己的地址——这纠正了"第二个字段是 self"的猜测。

### 15.2 工具

`native/airdrop/tools/awdl-join.py`：从监听日志多数票选出簇主与主选举度量，
重写计划里 TLV 4 `[21:27]`、TLV 5 `[5:11]` 与 `[11:13]`、TLV 24 `[0:12]`；
addr2、TLV 12 的自地址、所有时序字段一律不动。本次实测改写精确落在
偏移 64-69 / 124-130 / 211-222，与预期完全一致。

### 15.3 结果：无效

24 秒、13 轮、364 帧、0 拒绝，链路保持：

```
plan_frames_total=364 rejected_total=0 bursts=13
CAPTURE_SUMMARY total=17353 mgmt=2162 apple_oui=1144 awdl=1144
peers=11 frames_mentioning_us=0
```

**没有任何对端帧提到我们的地址**，与改写前一致。身份改写是必要的
（否则就是 `5a0abcae5a1f` 那种孤立节点），但远不是充分条件。

### 15.4 关键对照：帧确实上天了

在判断"协议层不接受我们"之前，必须先排除"帧根本没出去"。修
`awdl-mgmt-tx` 的一个真实缺陷后完成了这个对照：此前单帧路径的 `--ack`
是**空操作**——`send_frame(..., want_ack, NULL)` 从不捕获 cookie，
也没有任何 `wait_tx_status` 调用，所以 `--ack` 看起来被接受了，其实什么都没等。
只有计划路径会等 TX 状态，且只对 `kind == DATA` 的帧等。

修好后对 11 个已见对端各发 10 个单播 action 帧：

| 对端 | ACK |
|---|---|
| 2e:1a:3f:c6:56:08（簇主） | 2/10 |
| 52:33:4b:eb:5f:19 | 2/10 |
| c6:42:8c:98:bc:9e | 1/10 |
| ba:99:79:dc:a4:1c | 1/10 |
| 8e:a9:cc:ba:e3:d4 | 1/10 |
| 其余 6 个 | 0/10 |

**5/11 个设备在 MAC 层确认收到我们的 action 帧**。ACK 只证明帧合法且被听见，
不证明 PSF/MIF 被接受为同步源——但这排除了"帧没辐射出去"这一类解释，
把失败原因锁定在协议层。

附带发现：这次探测**没有用 `--roc`**，只靠 `NL80211_ATTR_WIPHY_FREQ`，
驱动就自己为每一帧切到了 2437 并收到了 ACK。短帧不需要 ROC 链，
但 §13 已测出连续 1.5 秒的计划必须靠 ROC 分段 + 占空比，否则链路会掉。

## 16 两个被纠正的判据，以及 MIF 服务记录的结构

### 16.1 "没有对端提到我们"从来不是有效判据

§15.3 用 `frames_mentioning_us=0` 作为"没被收录"的证据，这是错的：
AWDL 的 PSF 只带簇/同步信息（TLV 4/5/18/24/12/7/17/21/33），**根本不带邻居表**。
任何对端的 PSF 都不可能"提到"我们，改写前后都是 0，这个指标对结论没有信息量。

有效的判据只有两个：
1. 对端是否**单播寻址我们**（`addr1 == 我们的 MAC`）——实测同样为 0。
2. Mac 的 AirDrop 面板是否列出我们——只能由人去看，这是唯一的地面真值。

### 16.2 帧确实上天了，但 Mac 现在不应答

§15.4 已证明 5/11 个设备在 MAC 层确认收到我们的 action 帧。但对 **Mac
（`02:57:dc:2d:32:26`）**本身：

| 轮次 | 帧类型 | ACK |
|---|---|---|
| 上一会话 | DATA 探针 | 4/16 |
| §15.4 | action 帧 | 0/10 |
| 本次（19:5x） | DATA 探针 | 0/10 |
| 本次（19:5x） | action 帧 | 0/10 |

Mac 的 PSF 节律约 112 ms（delta 直方图在 112/224/452 处成簇），可用窗口占空比
约 58%，所以"发在窗口外"解释不了 0/10。更可能是 Mac 自身进入了低功耗 AWDL
状态或已休眠——它对两种帧类型同时不应答，而对端网络里仍有 5 个设备在 ACK。

### 16.3 MIF 服务记录（TLV 2）的容器结构

TLV 2 的载荷是**两条记录加一个 2 字节尾缀**，不是裸 mDNS 报文：

```
<u16 LE: L_A> <name_A>          # name_A = <len><label><c0xx><type>
<u16 LE: L_B> <body_B>          # body_B = <u16 0><u8 hostlen><host label>
<c0xx>                          # 尾缀，悬空的压缩指针
```

六条记录的 type 字节：`0c`（PTR，两条服务名记录）、`21`（SRV）、`10`（TXT）。
TXT 的 rdata 是标准的 `<len><ascii>` 串序列。

**身份字段只有 Mac 带**：全网 11 个节点里，只有 `02:57:dc:2d:32:26` 的 TLV 2
含复合主机标签 `<10><9 字节 id `e784b1e69e97e79a84`><"MacBook"/"macbook">`，
其余节点只有服务名标签。AirDrop 相关串：

```
at=217213dfa91a
sn=com.apple.sharingd.AirDrop
sid=3D78B4B3-62F3-4EA6-AD57-452577C498B2
dnm=e784b1e69e97e79a84MacBook
b4fb8f9f-5a19-485b-86d4-1b497dc19d38      (TLV 16 的 ARPA 名)
```

我们重放的 MIF 里这些全是 **Mac 自己的**——等于把 Mac 的身份原样广播回去，
这是必须换成我们自己的（自有 9 字节 id、主机名、sid、at）。

### 16.4 SoftAP 试验台：走不通，原因已定性

想把本机变成 2437 上的 AP（`cmd wifi start-softap marionette-rig open -f 2437`），
让 STA 关联到它，从而让 AWDL 社会信道等于 STA 归属信道、全双工免 ROC。
实测：

- AP vdev 能起来（`AP started vid 2 freq 2437`，`iw dev wlan2 info` 有 channel 行），
  但 WPA2 会 `wlan_cfg80211_crypto_add_key: Target response timed out`，换 open 才行。
- **驱动把自己 AP 的 BSSID 从扫描结果里过滤掉了**：`iw dev wlan0 scan` 扫到 77 个
  BSS 却没有 marionette-rig，框架的 `list-scan-results` 同样没有。
  于是框架永远不会选它，`add-suggestion` + `network-suggestions-set-user-approved`
  都不起作用。
- 有 `awdlmon` 存在时 SAP 必失败（`policy_mgr_is_sta_mon_concurrency: STA + MON
  mode is UP`），必须先删 monitor。
- 结论：这条路封死，除非改驱动去掉自过滤。已放弃，环境已还原
  （`add-suggestion "LIMO-5G ceshi" wpa2 <pwd> -u` 可恢复，凭据在
  `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`）。

### 16.5 环境

AP（`70:3a:73:a2:26:68`）整段会话断续消失，`iw scan` 连续 0 BSS。
所有 marionette 机制都以关联为前置，未关联时 ROC 直接 `-22`、监听 `total=0`。
恢复手段只有 `svc wifi disable/enable`。会话末期 AP 持续缺席，无法继续空口实验。

---

## 17 MIF 服务记录的真实结构（推翻 §16.3 的两个结论）

§16.3 依据**一帧** Mac MIF 得出的两条结论都是错的。这次把
`build/airdrop/driver/coexist-rx/frames-2026-10-21.log` 里**全部 9 帧 MIF**
（8 个不同 Apple 设备：5 台 Mac、1 台 iMac、1 台 Mac Pro、若干 iPhone 类）
抽出来逐字节比对，结构才定下来。

### 17.1 TLV 2 的封帧：最后一条记录的长度短 2 字节

```
TLV 2 payload := <record>* <2 bytes>
record        := <u16 LE L> <body of L bytes>
```

最后一条记录的 body 比它声明的 L 多 2 字节，多出来的 2 字节就是整个 TLV 2
payload 末尾那 2 字节。**9 帧 × 每帧 6~9 个 TLV 2，无一例外。**

判据（`mif_984`，即我们的模板）：TXT rdata 里最后一条 TXT 串声明
`14`(=20) 却只剩 18 字节 `dnm=<host>MacBo`，而 TLV 2 尾部正好躺着 `ok`；
`mif_1184` 声明 `1d`(=29) 剩 27 字节 `sn=...AirDr`，尾部是 `op`；
`mif_969` 尾部 `2)`，`mif_993` 尾部 `2)`，`mif_1117` 尾部 `ro`。
8 个设备、每个 TLV 2 都成立，所以**这是线格式的一部分，不是模板缺陷**。

> §16 曾把 `dnm=`/`sn=` 少 2 字节当成"模板被截断"并怀疑它是发现失败的根因。
> 这是误判：真实 Apple 帧全都这样，我们自己的解析器按 `L` 切才显得残缺。
> `awdl-identity.py` 因此把尾部 2 字节并回最后一条记录再重写，
> 重发时再按同样约定切回去——用模板自身身份回写可逐字节复原（见 `--selftest`）。

### 17.2 记录成对出现，没有"9 字节二进制主机 id"

```
name  := <labels...> <c0 xx> <type u8>     # type: 0c=PTR 21=SRV 10=TXT
rdata := <u16 0x0000> <rdata>
```

§16.3 说的"复合标签 `<len><9 字节 id><ascii 主机名>`"是错的。那 9 个字节是
**UTF-8 主机名的前 9 字节**：

```
e7 84 b1 e6 9e 97 e7 9a 84  =  "焱林的"
```

所以模板 Mac 的主机名就是 `焱林的MacBook`，小写形态 `焱林的macbook`。
DNS 标签只是普通的 `<u8 len><bytes>`，服务名标签
（`_appsvcprepair` / `_applicationservicepairing`）和主机名标签同形，
**唯一可靠的区分办法是位置**：主机名标签紧挨在服务名标签前面。

`awdl-identity.py` 原先按"id 是否非 ASCII"挑标签，会把
`_applicationservicepairing` 误判成主机名（宿主 `ionservicepairing`），
导致 `dnm=` 和部分标签改写落空。现在改成按服务名标签定位，
并对 8 个真实设备的帧做"用自身身份回写必须逐字节复原"的回归测试，8/8 通过。

### 17.3 同一个主机名有两种拼写，都要换

| 位置 | 拼写 |
|---|---|
| SRV/TXT 记录的 name 里（服务名标签前） | 小写 `焱林的macbook` |
| PTR 记录的 rdata（`0000` 之后的唯一标签） | 首字母大写 `焱林的MacBook` |
| TXT rdata 里的 `dnm=` | 首字母大写 `焱林的MacBook` |

### 17.4 第二处身份泄漏：SRV rdata 的 UUID 就是 TLV 16

SRV rdata 形如 `0000 0000 <port 2> <u8 36> <36 字符 UUID>`，
那个 UUID 与 **TLV 16 的 ARPA 名逐字节相同**。8/8 帧验证：
`mif_1184` 两侧都是 `e93c60de-101f-4ef6-91ea-07160ec5ed17`，
`mif_984` 两侧都是 `b4fb8f9f-5a19-485b-86d4-1b497dc19d38`。

Rust 侧 `announce_template.rs` 只改 TLV 16，**SRV rdata 里的 UUID 一直是
Mac 的**。也就是说我们此前广播的 MIF 有两处 Mac 身份：主机名/显示名/sid/at，
以及这个服务实例 UUID。现在 `awdl-identity.py` 两处一起换。

### 17.5 TLV 2 里还有非 AirDrop 服务，不能动

`mif_1184` / `mif_1117` 各有一个 TLV 2，其 name 记录是**裸压缩指针**
（无标签）+ type 0x0c，rdata 是 12 字节标签（`d0550ba77089`、
`50dd2c105427`），另有 TXT 记录带 `flags=1116`。
这些不属于 `_appsvcprepair` / `_applicationservicepairing`，
按服务名标签门控后跳过——否则会把它们误改成长主机名（曾致帧长 +13）。

### 17.6 我们的身份

```
host     = marionette          # 小写 mDNS 主机名
display  = Marionette          # dnm= 与 PTR rdata
sid      = 2C9D473F-65EB-4AB7-82C7-757E4B8D06B9
at       = 4656d6de6b8a
uuid     = 8DD0D393-ABA1-4727-B338-75B9F049A66A   # TLV16 与 SRV rdata 一致
```

改写后 14 帧 MIF：Mac 的 5 个身份串全部为 0，我们的 5 个全部 14/14 在位，
TLV 种类序列不变、链可解析、帧长 958 → 942。

工具：`native/airdrop/tools/awdl-identity.py`（`--selftest` 为解析器回归测试，
`--check` 校验无模板身份残留）。

### 17.7 身份改写已下沉到 Rust（`announce_template.rs`）

常驻服务不会经过 Python 后处理，所以身份改写必须在 Rust 的
`announce_template::build()` 里完成，否则 `awdl-emit` / `filin` 直接发出的
MIF 仍然带着模板 Mac 的身份。已移植并交叉验证：

- `Identity` 新增 `host` / `display` / `sid` / `at`，`name` 更名为 `uuid`
  （语义就是服务实例 UUID，TLV 16 与 SRV rdata 共用这一个值）。
- `build()` 改成**整条 TLV 链重建**：先逐 TLV 打补丁再重新发出。
  主机名长度一变会移动 6 条 TLV 2 记录，就地拼接会让后续 span 全部失效。
- `AwdlState` 新增 `service_uuid` / `service_host` / `service_display` /
  `service_sid` / `service_at`，默认值固定（常驻服务重启后身份不变）。
- `awdl-emit --name` 现在驱动 `service_display` 与 `service_host`
  （显示名按输入，主机名转小写），不再是一个失效参数。

**交叉验证**：同一 `--mac/--name/--channel` 下，Rust 版与 Python 版
（`awdl-identity.py`）产出的 6 条 TLV 2 与 TLV 16 **逐字节相同**，
帧长同为 942。

**回归测试**（在设备上跑，`cargo test --target aarch64-linux-android`）：

| 测试 | 钉住什么 |
|---|---|
| `mif_service_records_carry_our_identity` | 5 个身份串全是我们的，模板的 5 个一个不剩 |
| `mif_service_tlv2_payloads_keep_the_two_byte_tail` | 每条 TLV 2 仍带 2 字节尾 |
| `rewriting_the_template_with_its_own_identity_changes_nothing` | 用模板自身身份回写，6 条 TLV 2 + TLV 16 逐字节不变 |
| `mif_leaves_non_airdrop_services_alone` | 裸指针 PTR 的 12 字节标签不被误改 |
| `rebuilt_frame_length_tracks_the_name` | 主机名长度 ×7 处（4 个 name + 2 个 PTR rdata + 1 个 dnm=） |

写测试时立刻抓到两个真 bug：TXT 串改写漏了 `sid=`/`at=`/`dnm=` 前缀，
以及误以为帧长只受 4 处影响（实际 7 处）。这就是把"用自身身份回写必须复原"
写成测试的价值。

`cargo test -p filin-rs --lib --target aarch64-linux-android`：**241 passed**。
注意 lib 测试只能在设备上跑（宿主机是 macOS，netlink 代码编不过；
Android 目标编出来的测试二进制宿主机也执行不了）：

```
adb -s 510af65c push target/aarch64-linux-android/debug/deps/filin_rs-* /data/local/tmp/filin-test
adb -s 510af65c shell 'su -c "chmod 755 /data/local/tmp/filin-test && /data/local/tmp/filin-test"'
```

## 18. 进度事件契约 + 端到端验证（发现两个真 bug）

目标从"能收"变成"和 iPhone 一样用"：常驻、收到就弹悬浮窗、分享面板里直接发、
附近设备实时列表。这些都需要**传输进度**，而原来的 CLI 只在结束时打印一行
`Send complete.`，`send_files()` 内部也只有 `info!("POST /Upload")`。

### 18.1 契约：stdout 上的 JSON Lines

日志走 **stderr**（`tracing_subscriber::fmt(...).with_writer(std::io::stderr)`），
所以 **stdout 是空的** —— 直接征用它做机器可读通道，一行一个 JSON 对象。
完整事件表见 `native/airdrop/PROGRESS.md`（Kotlin 侧就是照它写的）。

实现要点：

- `progress::Sink` 是唯一出口，`disabled()` 时全部是空操作，库调用方无感；
- `progress` 事件**按整百分比 + 250ms** 双门限节流，最后一字节强制发（否则进度条卡在 99%）；
- 未知总长（chunked）时百分比无意义，只走时间门限，UI 显示不确定进度条；
- `stage` / `receiving` 会重置节流状态，因为每个阶段都是自己的 0→100% 曲线。

**接收侧**的进度只能在 `read_body` 里报：等 `handle_post` 拿到 body 时数据已经在内存里，
挂在后面只能看到 0% 和 100%。所以 `read_body` 改成按 64 KiB 读、读完一段报一次。

### 18.2 设备上跑出来的三个问题

在真机上用**回环**跑完整链路（不依赖 AWDL 网卡）：

```
# 接收端
MARIONETTE_APPROVAL_DIR=/data/local/tmp/appr ./luftlift -i lo receive --progress \
    --output /data/local/tmp/out --name LoopPeer --http-addr off
# 发送端（--address 跳过 mDNS；lo 上没有多播路由）
./luftlift -i lo send --progress LoopPeer --address 127.0.0.1:8771 hello.txt
```

`send` 新增 `--address <ip:port>` 就是为了这个：`lo` 上 mDNS 发现不到对端，
没有它就没法在设备上验证协议与事件流。

跑出来的三个真问题：

**(1) `/Upload` 永远 403 —— 我们自己的 sender 会失败。**
`handle_tls_connection` 里 `approved` 是**连接内局部变量**。真实苹果设备用一条
keep-alive 连接跑 Discover→Ask→Upload，所以局部变量够用；但 `RustlsTransport`
**每个请求新开一条连接**，`/Upload` 到了新连接上 `approved` 还是 false → 403。
改成 `Approvals = HashMap<IpAddr, Instant>` 跨连接共享，`/Ask` 通过后
`grant_approval(peer)`，`/Upload` 前 `take_approval(peer)`（**消费式**：一次 /Ask
只授权一次 /Upload，120s 内有效）。这个 bug 在空口上一直没被发现，因为发送方向
从来没验证成功过。

**(2) `echo accept > x.decision` 被当成拒绝。**
`guard::approve` 原来是 `value == "accept"` 精确比较，shell 写进来的 `"accept\n"`
不相等 → 静默拒绝。改成 `trim()` 比较。任何脚本化客户端都会踩这个坑。

**(3) 只有 `/Upload` 该报进度。**
`/Discover` 和 `/Ask` 也有 body（几百字节 plist），最初也走了进度上报，
结果是"对方只是打开分享面板看了一眼"就弹一次进度卡片。改成按请求行
`POST /Upload` 门控。

**(4) `listening` 事件是设备上才发现的缺口。**
`lo` 上没有 IPv6 地址 → mDNS 永不宣告 → `ready` 永不触发，但 HTTPS 已经在
`[::]:8771` 上监听。UI 无法区分"还在启动"和"起来了但不可见"，而这正好是网卡
坏掉时的表现。新增 `listening`（监听已建立、尚未宣告），`ready` 仍然只在
宣告成功后发。

### 18.3 验证结果（真机，回环）

```
发送端 stdout：
{"event":"peer","name":"127.0.0.1:8771","address":"127.0.0.1","port":8771}
{"event":"stage","stage":"discover"}
{"event":"stage","stage":"ask"}
{"event":"stage","stage":"upload"}
{"event":"progress","received":240,"total":240}
{"event":"sent","bytes":14,"peer":"LoopPeer"}

接收端 stdout：
{"event":"listening","name":"LoopPeer","port":8771}
{"event":"discover","sender":"luftlift"}
{"event":"request","files":[{"name":"hello.txt","size":14}],"sender":"luftlift","token":"...","total":14}
{"event":"decision","token":"...","accepted":true}
{"event":"receiving","total":0}
{"event":"progress","received":92,"total":0}
{"event":"received","dir":"/data/local/tmp/out","files":[{"name":"hello.txt","path":"...","size":14}],"total":14}
```

落盘内容 `hello-airdrop` 正确。**这是接收+发送数据路径第一次在设备上端到端跑通**
（此前只有单元测试和握手级验证）。

`cargo test -p luftlift-rs`：**177 passed**（新增 10 个 progress 测试、4 个事件接线
测试、2 个跨连接授权测试、3 个 `--address` 解析测试等）。

### 18.4 Android 侧结构

| 文件 | 职责 |
|---|---|
| `AirDropEvents.kt` | 解析 JSON Lines + 进程内状态机 `AirDropState`（主线程派发） |
| `AirDropBackend.kt` | 唯一的子进程入口（receive / find --watch / send），环境变量**写进 shell 命令**而不是 `ProcessBuilder.environment()`（`su` 不保证透传） |
| `AirDropOverlay.kt` | `TYPE_APPLICATION_OVERLAY` 悬浮窗：确认 / 进度 / 结果 / 错误四种形态 |
| `AirDropService.kt` | 会话引擎：网卡 + 后端 + 事件泵；常驻模式无时限、失败退避重试 |
| `AirDropResidentService.kt` | 控制面：看门狗每 20s 确认引擎还活着 |
| `AirDropShareActivity.kt` | `ACTION_SEND`/`SEND_MULTIPLE` 目标：实时设备列表、点按即发 |
| `AirDropActionReceiver.kt` | 通知栏「接收/拒绝」（没有悬浮窗权限时的退路） |

关键取舍：常驻与"10 分钟实验"是**同一个引擎的两种模式**，不是两套代码 ——
网卡切换、后端重启、事件流必须在同一处收敛，否则两个实例会抢同一块网卡。

悬浮窗权限（`SYSTEM_ALERT_WINDOW`）只能去系统设置授予，所以主界面提供
「预览悬浮窗样式」：不依赖真实传输就能确认权限生效、样式正常。

`app/src/test/.../AirDropEventsTest.kt` 用 `org.json:json`（仅 test classpath）
把文档里的样例行钉死 —— 事件名/字段名是**跨语言契约**，改名不会有编译错误，
只会在真机上表现为"界面永远停在准备中"。

### 18.5 仍未验证

空口。`radio.sh` 在设备上仍然直接拒绝：

```
当前 Wi-Fi 驱动监听模式缺少发包回调，无法完成原生 AirDrop；已保留正常 Wi-Fi。
```

即 ROADMAP §16 起的固件 RX/TX 问题（`policy_mgr` monitor-vdev 注册、
6/12/24 Mbps 速率控制、TX 对齐 112ms 可用窗口）。回环验证覆盖了应用层协议与
事件流；一旦网卡能起来，剩下的是"能不能收到帧"而不是"收到之后怎么办"。

---

## 19. 常驻的前提：可用窗口相位必须跨突发连续（2026-10-21 夜）

§18.5 把空口列为唯一未验证项。这一节先纠正一个对**当前设备状态**的误判，再修掉
§14.5 记下的那个 blocker，最后如实说明为什么本轮仍然没有空口结论。

### 19.1 现场复核：驱动其实已经是打过补丁的

`radio.sh` 拒绝的原因是**文件哈希**等于原厂：

```
/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
  sha256 = 75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8
  grep -c marionette  ->  0
```

但**已加载**的模块是候选版：

```
/sys/module/kiwi_v2/parameters/  共 47 项，其中 35 项是 marionette_*
marionette_awdl_data_tx       = Y      # 0043/0044 的 WMI MGMT_TX 数据通路
marionette_awdl_sta_monitor   = Y      # 0027/0033 的关联态 monitor
marionette_awdl_monitor_raw_rx= Y
marionette_awdl_raw_tx        = N      # 旧的 monitor ndo_start_xmit 路径关着
marionette_awdl_raw_tx_vdev   = -1
marionette_awdl_capture_hold  = N
uptime 16776s；dmesg 在 7768s / 8877s 各有一次 kiwi_v2 重载
```

**结论一：`radio.sh` 用磁盘文件哈希判断能力，是假阴性。** 它是"volatile 加载"
留下的状态：文件是原厂，跑着的是候选。判据应该看**已加载模块的能力**
（例如 `/sys/module/kiwi_v2/parameters/marionette_awdl_data_tx` 是否存在），
而不是文件的 sha256 —— 反过来，一个被截断的文件也能通过"不等于原厂哈希"这种
排除式判断。

**结论二：设备上现在这套组合不是 `radio.sh` 的那套。** `radio.sh` 走的是
`con_mode=4` 全局监听 + `filin -i wlan0`（需要 monitor 的 `ndo_start_xmit`，
所以才有那道哈希闸门）。而 §14.3 验证过的、且当前驱动正好支持的是另一套：
**保持 STA 关联 + `awdlmon` + `capture_hold` 滤镜 + ROC 切信道 + `mgmt_tx` 数据通路**。
两者不是同一件事，`radio.sh` 的拒绝不能当成"§14.3 那套也不可用"。

### 19.2 §14.5 的 blocker 已修：跨突发的可用窗口 epoch

§14.5 记的是"对端仍不向我们单播，下一步要按对端可用窗口对齐发射时机"。
`awdl-beacon.sh` 自己的注释把根因写得更准：*每次 plan 运行都携带一个可用窗口
epoch，重跑就把宣告的相位重置了*。

代码里对得上：`awdl-emit` 把 `base_us` 硬编码成 `0`，而 `build_announce_80211`
是这么盖戳的：

```rust
phy_tx:     now_us as u32,
target_tx:  now_us as u32,
aw_counter: ((now_us / (16 * 1024)) & 0xffff) as u16,
```

`now_us` 从 0 起算 ⇒ 每一轮 `phy_tx` 恒为 0、`aw_counter` 从 0 重来。苹果的对端
比较两轮宣告，看到的是"时间倒流 / 重新成簇"，因此永远不会把我们对等体记录下来。

**实测对照（设备，同一 MAC）：**

```
旧行为  --epoch-us 0
  run1  phy_tx=00000000  aw_counter=0000  offset=0
  run2  phy_tx=00000000  aw_counter=0000  offset=0     ← 完全一样

新行为  --epoch-now --lead-us 400000
  run1  epoch_us=17417156268  phy_tx=0e24b6ac  aw_counter=0x3892(14482)
  run2  epoch_us=17418329945  phy_tx=0e369f59  aw_counter=0x38da(14554)
```

间隔 1.17 s，`aw_counter` 前进 72 —— 正好是 1.17s / 16.384ms ≈ 71.7 个可用窗口。
**计数器现在是连续推进的，而不是每轮归零。**

改动：

| 位置 | 改动 |
|---|---|
| `awdl-emit` | 新增 `--epoch-us ABS` / `--epoch-now` / `--lead-us US`；抽出 `resolve_epoch()` 与 `build_plan()` 便于测试 |
| `runtime::host_time_us` | 由私有改为 `pub`：plan 必须和 daemon 用**同一个时钟**（CLOCK_MONOTONIC µs）盖戳 |
| `awdl-beacon.sh` | 每轮重建 plan（`--epoch-now --lead-us`），不再重放一个静态 plan 文件；顺带每轮重读接口 MAC，`addr2` 不会过期 |

两个参数的语义要分清，否则会早发 `lead` 那么多：

- **内容**按 `base_us + rel` 盖戳（`rel` 是相对首帧的时间）；
- **偏移**是 `lead_us + rel`，给 replayer 留出进程启动时间；
- 于是 `--epoch-now` 解析为 `now + lead_us`，首帧落在 `epoch`，实际发送 ≈ `epoch + rel`。
  残差只剩 plan 生成耗时（几 ms），远小于一个可用窗口（16 TU ≈ 16.4 ms）。

### 19.3 验证

`filin-rs` 的测试**在设备上直接跑**（`aarch64-linux-android` 测试二进制推到
`/data/local/tmp` 执行，比在 macOS 上跑更接近真实目标）：

```
filin_rs   242 passed; 0 failed
awdl_emit    6 passed; 0 failed
  epoch_now_stamps_from_the_clock_plus_the_lead
  epoch_defaults_to_zero_for_the_legacy_behaviour
  an_explicit_origin_is_used_verbatim
  plan_offsets_start_at_the_lead
  consecutive_bursts_advance_the_aw_counter     ← 这条钉住本次回归
  a_plan_still_carries_mif_and_psf_when_asked
```

> 顺带一条方法：`filin-rs` 在 macOS 上**根本编译不过**（`libc::NLM_F_ACK` 等
> Linux 常量、`sockaddr.sa_len` 是 macOS 专有字段）。所以"本机 `cargo test`"
> 对它从来不是有效判据；要么交叉编译到 `aarch64-linux-android` 上机跑，
> 要么进 Linux 容器。`awdl-beacon.sh` 已 `sh -n` 通过，关联检查按预期在
> `wlan0 not associated` 处退出。

### 19.4 本轮**没有**空口结论，原因在环境

```
iw dev wlan0 scan        ->  0 个 BSS
iw dev wlan0 link        ->  Not connected
settings get global wifi_on -> 1        (Wi-Fi 开着，只是周围没有 AP)
```

没有任何 AP ⇒ 关联不上 ⇒ `awdl-beacon.sh` 的前置检查直接退出，§14.3 那套
"保持关联"的验证无法开展。**所以本轮既没有推翻也没有确认"Mac 能否看见我们"。**

（提醒：`iw scan` 在这台机器的 `iw` 上是**非法子命令**，它会打印 usage，而
usage 文本里含 "BSS" 字样 —— 用 `grep -c BSS` 数它会得到 8 这种假读数。
必须用 `iw dev wlan0 scan`。）

### 19.5 下一步（按优先级）

1. **给 `radio.sh` 换成能力判据**，并把常驻路径从 `con_mode=4` 全局监听改成
   §14.3 那套（关联 + `awdlmon` + `capture_hold` + ROC + `mgmt_tx`）。
   这是"常驻 + 与普通 Wi-Fi 共存"真正的工程项；现在 `radio.sh` 一拒，
   应用层连引擎都起不来。
2. **造一个有 AP 的环境再跑 `awdl-beacon.sh`**（Mac 开共享网络即可），
   看 `frames_mentioning_us` 是否从 0 变成非 0。这是"发现"的唯一判据。
3. 若对端仍不应答，下一个要看的字段是 **`master_counter`**：它现在是
   `COUNTER_INIT` 常量，跨突发不变。不是倒退，但也不推进；对端可能据此认为
   我们的选举视图陈旧。它和 `aw_counter` 不是一回事，不要一起改。

## 20. 发包通道打通：`NL80211_CMD_FRAME` 进入 filin

§19 的结论是"要换发射后端"。这一节记录换完之后实测到的东西，以及一个
此前没人记录、但决定整套方案边界的硬约束。

### 20.1 为什么必须换

三条路只有一条能走：

| 通道 | 结论 | 证据 |
|---|---|---|
| 监视 vdev + AF_PACKET 注入 | **固件拒绝** | 0040：同一断言 `cmnos_thread_patch.c:1170 line#2834`，与 host 侧 TX 元数据无关 |
| `con_mode=4` 全局监听 | RX 可用，TX 仍走上面那条 → 不可用 | §12 |
| `NL80211_CMD_FRAME`（WMI `MGMT_TX`） | **可用**，且不需要监听模式 | §14 里 Apple 回过 ACK 的就是这条路 |

所以 `filin` 的发射后端从"AF_PACKET 绑监视接口"改成"cfg80211 发管理帧"。
关键是这条通道**不需要 `con_mode` 变化**，于是 STA 可以一直保持关联 ——
"AirDrop 与普通 Wi-Fi 共存"不是体验优化，而是这条通道自带的性质。

### 20.2 实现

- `os::nl80211::MgmtTx`：`CMD_FRAME`(59) + `ATTR_IFINDEX`(3) +
  `ATTR_WIPHY_FREQ`(38) + `ATTR_FRAME`(51) + `ATTR_DONT_WAIT_FOR_ACK`(142)。
  常量对着 `tools/awdl-mgmt-tx.c` 抄，不凭记忆写内核 ABI。
- 收 ack 不能只 `recv` 一次：cfg80211 对 `CMD_FRAME` 先回一条带 cookie 的
  genl reply，再回 `NLMSG_ERROR`，而同一个 socket 还带 MLME 组播事件。
  只读一条会把 ack 留在队列里，之后每次请求都错位。`request_frame()` 按
  `nlmsg_seq` 匹配，跳过事件。
- `runtime::Injector`：`Packet` / `MgmtTx` 两态。上层仍然构造
  radiotap 前缀帧（保持既有代码不动），`MgmtTx` 分支用 `strip_radiotap()`
  剥掉前缀 —— cfg80211 自己补 PHY 头，带前缀会把 radiotap 字节当成 MAC 头打出去。
- `FILIN_TX_IFACE=<iface>` 打开共存模式：选 `MgmtTx`，并且**跳过**
  `set_monitor` 与 `set_channel`（电台归平台调度，动它就是 §13.2 的信标丢失）。

### 20.3 实测：第一次有 MAC 身份错误

第一版跑起来每帧都失败，`err=MgmtTx`。加了一行 debug 才看到真因：

```
injected awdl announce ... 0 次
packet announce send failed err=MgmtTx subtype=Psf   每 112 ms 一次
```

原因是**身份取错了**：`self_addr` 来自监视接口（`ea:f7:91:a4:f8:fe`），
而实际发送的是 `wlan0`（`e8:f7:91:1f:f8:fe`）。cfg80211 要求 addr2 等于
发送 vdev 的地址，不等就**整帧**拒绝。改成读发送接口的 MAC 之后：

```
INFO advertising the transmit vdev's address (addr2 must match the sender)
     tx_addr=[232,247,145,31,248,254] monitor_addr=[234,247,145,164,248,254]
INFO transmitting via NL80211_CMD_FRAME (station keeps its link) freq_mhz=2437

8 秒：injected awdl announce = 192 次，失败 0 次
      PSF len=352  MIF len=942      （与 C 工具的 plan 逐字节同尺寸）
```

发完之后复查电台：`con_mode=0`、`wlan0` 仍是 managed、`awdlmon` 仍是 monitor、
`wifi_on=1`。**没有为 AirDrop 付掉任何 Wi-Fi 可用性。**

### 20.4 硬约束：没有关联就没有 RX

这是本轮最重要的发现，两条独立证据指向同一件事：

```
# 1) 建监视 vdev 并拉起
ip link set awdlmon up
→ RTNETLINK answers: Transport endpoint is not connected
dmesg: __hdd_mon_open: MARIONETTE_STA_MONITOR_INIT_RESULT ret=-107

# 2) 未关联时切信道
iw dev awdlmon set channel 6        → -16 EBUSY
marionette-awdl-mgmt-tx wlan0 2437 1 --roc-only 200
→ roc_start(wlan0, freq=2437, 200 ms) -> -22
```

`-107` 是 ENOTCONN，`-22` 是 EINVAL：**监视 vdev 的打开和 ROC 都以"STA 已关联"
为前提**。所以在这台机器上，"没连 Wi-Fi 也能收 AirDrop"是不成立的，无论
`con_mode` 怎么设。

工程含义：这不是错误状态，而是正常运行状态的一种。`radio.sh` 因此把
"未关联"作为**明确的可行动提示**报给界面，而不是启动一个永远收不到东西的引擎：

```
请先连接一个 Wi-Fi 网络 —— AirDrop 需要 Wi-Fi 芯片保持联网   （exit 7）
```

（对比 iPhone：AWDL 是独立接口，不需要关联。这里是驱动实现方式带来的差异，
记录在案，不假装等同。）

### 20.5 `radio.sh` 重写

旧脚本有两处必须去掉：

1. **哈希闸门**。它拿磁盘上 `.ko` 的 SHA256 与库存值比较，相等就拒绝。
   设备上实际跑的是候选驱动（`/sys/module/kiwi_v2/parameters/` 里 35 个
   `marionette_*`），而磁盘文件是原厂 → **假阴性**；反过来说，一个被截断的
   文件同样能通过。改成能力判据：`capture_hold` 可写、`iw` 存在、链路已连接。
2. **600 秒上限**。`while [ "$tick" -lt 600 ]` 会在"常驻"会话第 10 分钟
   把电台杀掉，再靠上层重启。常驻的终止条件只应该是"应用进程没了"或"stop 文件"。

新流程：能力检查 → 建 `awdlmon` → `capture_hold=1` → 带
`FILIN_TX_IFACE=wlan0` 启动 filin → 看到 `transmitting via NL80211_CMD_FRAME`
就写 `listening` → 守护到 stop/应用退出 → 退出时 `capture_hold=0` 并删掉
`awdlmon`，把电台还原成系统原本的样子。

### 20.6 测试

设备上直接跑（`--target aarch64-linux-android --no-run` 后推到 `/data/local/tmp`）：

```
filin_rs  247 passed; 0 failed      （新增 5 个：3 个 nl80211 线格式 + 2 个 strip_radiotap）
awdl_emit   6 passed; 0 failed
```

新增的 3 个测试钉的是内核 ABI：命令号 59、`ATTR_FRAME` 51、
`DONT_WAIT_FOR_ACK` 142，以及"帧必须原样装进属性"、"标志属性无载荷"。
这些数字写错不会有任何编译错误，只会在设备上表现为"帧悄悄发不出去"。

### 20.7 本轮没有证明的

- **空口**。周围 0 个 AP（`iw dev wlan0 scan` 无 BSS），没有可关联的网络，
  于是监视 vdev 起不来、ROC 被拒 —— 上面 §20.4 的约束使本轮无法产生任何
  接收侧证据。`frames_mentioning_us` 仍然没有测过。
- **ROC 调度的实际效果**。filin 现在只做"发"，不做 remain-on-channel；
  有 AP 之后需要在每个可用窗口前 `REMAIN_ON_CHANNEL`(55)，否则 RX 停在
  STA 的信道上，而帧发在社交信道上 —— 收发不同频。
- **`awdlmon` 的 RX 是否真的收到 AWDL 帧**。`capture_hold=1` 只是开滤镜，
  链路侧还需要 ROC 把电台挪过去。

## 21. 有 Wi-Fi 关联之后：第一次双向空口 + 一次内核重启

§20 因为周围没有 AP，只能证明"发得出去"，收不到任何东西。这一轮连上了
`LIMO-5G ceshi`（信道 52），补上了缺失的那一半，也踩到一个必须记录的坑。

### 21.1 顺序是硬约束：先连网，再建监视接口

上一轮把监视接口删了之后再也拉不起来，`__hdd_mon_open` 一直返回 `-107`。
这一轮做了一次干净的分步测量：

```
0 初始:      Connected to 70:3a:73:a2:26:68
建 vdev:     add=0
1 建vdev后:  Connected ...            ← 建 vdev 不影响链路
ip link up:  up=0
2 up后:      Connected ...            ← 关联存在时 up 成功
awdlmon      UP  <BROADCAST,MULTICAST,UP,LOWER_UP>
```

反过来（未关联时建 vdev 并 up）就会 `-107`，而且**此后连 STA 也关联不上**：
每次尝试都是 `CTRL-EVENT-ASSOC-REJECT status_code=1`，认证通过、关联被拒，
看起来像 AP 的 MAC 白名单，实际是驱动自己处于坏状态。`svc wifi disable/enable`
一次就恢复。所以：

> 关联 → 建监视接口 → up → capture_hold=1。
> 顺序反了不要靠重试，直接复位 Wi-Fi。

### 21.2 第一次真正收到苹果设备的 AWDL 帧

`capture_hold=1` + 监视接口在信道 52 上，filin 直接解出了真实邻居：

```
installed neighbor entry for peer mac=02:57:dc:2d:32:26
installed neighbor entry for peer mac=ca:9b:87:d2:53:ca
installed neighbor entry for peer mac=de:b5:8d:40:4b:bf
peer table cleaned removed=2 peers=5
chanseq TLV decoded raw=0f030003ffff347600009580...
  decoded=[52, 0, 149, 0, 0, 0, 0, 0, 6, 0, 149, 0, 0, 0, 0, 0]
```

12 秒 42 帧，5–8 个对端，有邻居条目安装也有淘汰。这是**第一次在真机上收到
真实 AWDL 流量**，此前所有 RX 结论都来自离线构造的帧。

### 21.3 同时发包，链路不断

同一套配置下（`FILIN_TX_IFACE=wlan0`，`-c 6 --park`）：

```
12 秒：injected awdl announce = 289 次
       send failed = 0
       PSF len=352  MIF len=942
结束复查：Connected to 70:3a:73:a2:26:68 (LIMO-5G ceshi, 5260)
```

收发同时进行、Wi-Fi 链路全程保持。§20 的"共存"从设计变成了实测。

### 21.4 崩溃：`--roc` 广播把手机重启了

下一步要验证"苹果设备能不能看见我们"。按 §14.3 的既有流程跑
`awdl-beacon.sh 2437 45`（带 `--roc` 的定时计划重放），命令返回 255、无输出，
紧接着 `adb devices` 里设备消失，重新出现后 `/proc/uptime` 只有 25 秒
——**手机重启了**。`/sys/fs/pstore` 为空，没有留下 panic 记录。

同时发生的事：重启把驱动换回了原厂（`marionette_*` 参数从 35 个变成 0 个），
`awdl-stack-load.sh` 注释里早就写了这个现象。用

```
awdl-stack-load.sh load marionette-cfg80211-0043.ko marionette-candidate-0045.ko
→ cfg80211_gate=Y awdl_data_tx=Y STACK_LOADED link=70:3a:73:a2:26:68
```

恢复，35 个参数全部回来。

**这次崩溃的定位还不成立**：唯一的新增变量是 `--roc` 的计划重放，但同样组合
（关联 + 监视接口 up + `capture_hold=1` + 多次 `--roc-only`）在崩溃前跑过十几次
都没事。要定性必须先把 panic 抓住 —— 设备上已经有 `marionette_die_logger.ko`，
下一步应该在复现前先把它装上，而不是盲目再跑一次。

### 21.5 一个尚未回答的问题：我们到底在哪个信道上发

STA 在 5260，我们的帧带的是 `WIPHY_FREQ=2437`。cfg80211 接受了（`submitted`
计数在涨、`send failed=0`），但**接受不等于从 2437 打出去**。没有 ROC 时，
电台大概率还在 5260 上，苹果设备在 6/44/149 的窗口里听不到我们。

这与 §21.4 的崩溃叠在一起，构成下一轮的两件事：
1. 先装 die-logger，再用最小变量（单次 `--roc` + 单帧）复现，确定崩溃边界；
2. 确认 ROC 期间电台确实换到了目标频点（capture 的 `freq=` 分布是判据）。

---

## 22. 接收链路全景：断点在固件，不在主机

§22–23 记录的三个发射侧 bug（信道、DFS 锚点、跳台搬电台）都已修完并验证，
但 Mac 仍然看不见手机。这一节把接收链路从头走到尾，用计数器把断点定死。

### 22.1 主机侧接收链路（源码逐层确认）

```
固件
 └─ WMI_MGMT_RX_EVENTID
     └─ wma_mgmt_rx_process()                    wma_mgmt.c:3836
         └─ mgmt_txrx_rx_handler()               wlan_lmac_if_api.h:243 (static inline 分发器)
             └─ tgt_mgmt_txrx_rx_frame_handler() wlan_mgmt_txrx_tgt_api.c:1157
                 ├─ 丢弃：非 mgmt/ctrl 类型        :1200
                 ├─ 丢弃：addr2 与 addr3 都无效    :1213
                 ├─ 丢弃：MGMT_FRM_UNSPECIFIED    :1282
                 └─ 分发到 mgmt_rx_comp_cb[frm_type]
                     └─ pe_handle_mgmt_frame()    lim_api.c:1192（MGMT_FRAME_TYPE_ALL 注册）
                         └─ lim_check_mgmt_registered_frames()  lim_process_message_queue.c:1061
                             └─ lim_send_sme_mgmt_frame_ind()
                                 └─ hdd_indicate_mgmt_frame()  wlan_hdd_p2p.c:1084
                                     └─ cfg80211 → 用户空间
```

AWDL action 帧的分类走 `mgmt_txrx_get_action_frm_subtype()`：category 127
（vendor specific）→ `MGMT_ACTION_CATEGORY_VENDOR_SPECIFIC`。**只有 P2P 组件
为这个 frm_type 注册过回调**，STA 模式下如果没有额外注册，帧在
`tgt_mgmt_txrx_rx_frame_handler` 的 "No rx callback registered" 处就被丢掉。
补丁 0006 的 `sme_register_mgmt_frame` 正是补这一跳：它把
`{0x7f, 0x00, 0x17, 0xf2, 0x08, 0x10}` 写进
`mac_ctx->lim.gLimMgmtFrameRegistratinQueue`，`lim_check_mgmt_registered_frames`
逐项比对后交给 SME。**主机侧这条路是通的、已就绪。**

### 22.2 决定性测量：断点在固件

设备实测（关联状态，`marionette_awdl_mgmt_rx=Y`）：

```
marionette_mgmt_rx_wmi=1713     固件经 STA 通道送上来的管理帧
marionette_awdl_rx_wmi=0        其中 AWDL 帧：0
marionette_mgmt_rx_lim=51
marionette_awdl_rx_lim=0
marionette_mgmt_rx_hdd=0
marionette_awdl_rx_hdd=0

marionette_mon_post_awdl=1020   同一时间，AWDL 帧经监视 vdev 上来了 1020 个
```

**固件收得到 AWDL 帧（监视路径 1020 个为证），但从不把它们交给 STA 的
mgmt-rx 通道。** 所以 `mgmt_rx_wmi` 之后的每一个主机侧计数器都动不了 ——
不是 LIM 的问题，不是 mgmt-txrx 的问题，不是注册的问题。

`mgmt_rx_wmi=1713 → mgmt_rx_lim=51` 这个落差也解释了：那 1713 个里绝大多数
是 beacon，被 `pe_handle_mgmt_frame` 的 `pe_filter_bcn_probe_frame` 挡在 PE
队列之前，属于正常过滤，不是缺陷。

### 22.3 出口：`WMI_PDEV_SET_RX_FILTER_PROMISCUOUS_CMDID`

补丁 0020 已经实现了这个命令的发送者
（`marionette_wmi_rx_promiscuous()`，`wmi_unified_tlv.c`），它是唯一可能让
固件把 AWDL 帧交给主机的杠杆。但 0020 只在「限时捕获槽」里触发它，而那个槽
不可达：`marionette_awdl_capture_ms` 运行时只读（0444），且加载时给非零值会让
模块 init 直接 `-ENODEV`。

补丁 0008 走的是另一条等价路（`scan_f_promisc_mode` →
`WMI_SCAN_FILTER_PROMISCOUS`），但只在 STA ROC 期间生效，而 ROC 在这台驱动上
被无条件拒绝（§23.3）。

### 22.4 补丁 0046：把固件滤镜提升为常驻可写开关

`0046-resident-firmware-rx-promiscuous.patch` 把 0020 的
`marionette_awdl_fw_promisc` 从「加载期只读 bool」改成「运行时可写开关」：

- `.set` 处理器直接发 `WMI_PDEV_SET_RX_FILTER_PROMISCUOUS_CMDID`，
  **不依赖监视 vdev、不依赖 capture phase、不动信道**；
- 只在边沿发送（`enable != 当前值`），重复写入不会叠加命令；
- 释放永远允许，会话总能清理；
- 去掉 0020 在捕获槽里的收发逻辑（那个槽本来就用不了）。

这一点之所以关键：§22.5 说明监视 vdev 本身会吃掉基站信标，所以**任何需要
监视 vdev 的接收路径都不可能常驻**。0046 的接收路径不需要它。

补丁已验证：13/13 个补丁（0003–0046 中触及 `wlan_hdd_main.c` 的全部）在
源码树上干净应用；新函数通过 clang 语法检查（零错误）。

### 22.5 待验证的唯一一件事

**固件是否理会这条命令。** 打开开关后，`marionette_awdl_rx_wmi` 必须在有对端
发射时变成非零。如果仍是 0，说明固件忽略该命令，常驻接收需要另一个杠杆。

这个测量是本轮全部工作的意义所在：它一票决定后面所有方向。

### 22.6 构建环境（本轮新搭建）

驱动补丁必须编译成 `.ko` 才能上机。配方来自 `driver/sources.json`：

| 组件 | 来源 | 用途 |
|---|---|---|
| WLAN | MiCode/vendor_qcom_opensource_wlan @ `518147fd` | kiwi_v2.ko |
| 内核 | MiCode/Xiaomi_Kernel_OpenSource @ `7fad87dc` | cfg80211.ko + kernel-out |
| clang | AOSP `android14-release` 的 `clang-r450784e` | 必须精确匹配（CFI） |

在 `yuni`（8 核 / 14G / 204G 可用）上搭建：内核与 WLAN 已克隆完成，
clang 经 TUNA AOSP git 镜像稀疏检出（googlesource 直连被 TLS 阻断）。
`android.googlesource.com` 在本机与 yuni 均不可达，`codeload.github.com` 超时，
`mirrors.tuna.tsinghua.edu.cn/git/AOSP/` 可用。

**只需重编 kiwi_v2.ko**：0046 只碰 WLAN 驱动，cfg80211 侧的 0002/0043 已在
设备上（`marionette-cfg80211-0043.ko`）。

### 22.7 本轮实测到的另外三件事（均为否定结果）

1. **监视接口只要 up，基站 20–35 秒内掉线**。A/B 三组全断，连
   `capture_hold=0` 那组也断。内核日志
   `ROAM_TRIGGER ... BEACON MISS` → `NO AP FOUND`。不是滤镜开关问题，
   是监视 vdev 的存在本身。这直接否掉了「靠监视 vdev 常驻」。
2. **`--no-force-master` 让 mDNS 一封都发不出去**。闸门是
   `can_send_in(sync, now, MULTICAST_GUARD_TU)`；采纳真实 master 后从未放行
   （`rebroadcast=0 spread=0`），force-master 下同一会话
   `announce_ok=667 spread_ok=38 spread_FAIL=0`。radio.sh 因此保留
   force-master，代价记录在此。
3. **ROC 在 STA 上完全不可用**：`roc start 5260`、`roc start 2412`、
   `offchannel 5260` 在关联状态下全部 `-22`。补丁 0044 唯一那次「证明空口
   投递」的条件正是 during a remain-on-channel，所以「固件是否真把帧打出去」
   目前无法证明。

### 22.8 抓包证据：没有任何对端把我们当对端

拉回 filin 的 pcap（50 287 帧）逐帧统计：addr1 或 addr3 等于本机 MAC 的帧
共 42 个，**全部来自 AP 的 MAC**；Mac 的 AWDL 地址一帧都没发过。配合 Mac 侧
`dns-sd -B _airdrop._tcp` 30 秒零结果（阳性对照 TESTHOST 正常），结论是
Mac 没有把我们登记为 AWDL 对端，因此既不回我们，也不上报我们的 mDNS。

---

## 22. 共存模式真正落地：信道、跳台与一个被低估的代价

§21.5 留下的问题是"我们到底在哪个信道上发"。这一节把它答完，并且答出了三个
比它更严重的问题。**结论先行**：发送侧现在是对的（5260，零失败，386 帧/16 秒）；
接收侧（监视接口）本身会把基站链路拖死，这是"常驻"当前唯一的硬阻塞。

### 22.1 信道：改成"读基站真实信道"而不是"用配置的锚点"

`NL80211_CMD_FRAME` 带显式 `WIPHY_FREQ`。以前这个值来自 `-c`（2437），而 STA
在 5260 —— cfg80211 照单全收（`submitted` 在涨、零错误），但**接受不等于从那个
频点打出去**。新增：

```
os::nl80211::current_frequency_mhz(iface)   // GET_INTERFACE → NL80211_ATTR_WIPHY_FREQ
```

`open_links` 里改成读基站真实频点：

```
INFO transmitting on the station's channel, not the anchor — the radio cannot be on both
     tx_iface="wlan0" tx_freq_mhz=5260 anchor_freq_mhz=2437
```

实测确认（`channel-probe.sh`）：`tx_freq_mhz=5260`，`send failed=0`，链路全程
`Connected to 70:3a:73:a2:26:68`。`parse_attr_u32` 是纯函数，配了 5 个单测
（含"必须跳过 4 字节 genlmsghdr"和"长度不足 8 的属性不能越界读"）。

### 22.2 第二个启动失败：filin 拒绝 DFS 锚点

`validate_config` 把锚点写死成 `6 | 44 | 149`。共存模式下锚点必须是基站信道，
而 5 GHz 上那是 52（DFS）。结果是**filin 根本没启动**，只留下一句

```
Error: invalid CLI arguments: Invalid
```

比"信道不对"更糟——它看起来像参数拼错。改成"任何有已知频率的信道都接受"
（`crate::channel::channel_to_frequency_mhz`），并保留 `999` 这类真错值的拒绝，
两个方向都有单测。这条也解释了上一轮为什么有一段时间"filin 在跑但什么都没发"。

### 22.3 第三个、也是最贵的：跳台循环会把电台搬走

运行循环里 `announce.switch_due(...)` 之后直接

```
os::nl80211::set_channel(&links.monitor_iface, freq)
```

**给监视接口换信道 = 给整个 phy 换信道**，STA 立刻离开 AP。表现是
"会话跑 20 秒后 Wi-Fi 自己断了"，而日志里一切正常。修法是给 `Links` 加一个
`owns_radio: bool`（= `tx_iface.is_none()`），共存模式下这个分支只推进状态机、
不碰电台：

```
} else if !links.owns_radio {
    trace!("awdl channel advanced (coexistence: radio stays on the station's channel)");
}
```

同一个原则在启动时已经用过一次（`set_monitor` / `set_channel` 都跳过），
但漏在了热路径上——教训是"平台拥有电台"这件事必须只有一个开关，不能靠
在每个调用点各记一次。

### 22.4 `--force-master` 默认开启，在共存模式下是谎报

`force-master` 默认 ON，会让 filin 用虚高的 metric 宣称自己是 master，并把
信道序列推向 6。它的原始用途（把苹果设备从 DFS 拉走）只在"filin 独占电台"时
成立。共存模式下我们搬不动电台，这个宣称就是**让对端跟着跳到一个我们并不在的
信道**。启动脚本改为 `--no-force-master`：当客人，采纳真实 master，顺便继承
它的 availability window —— 这也是我们的帧能落进对端监听窗口的唯一办法。

### 22.5 硬阻塞：监视接口本身会吃掉基站的信标

这是本轮最重要的否定性结果。A/B（`tools/rx-cost.sh`，每段 45 秒，掉线即止）：

| 阶段 | 条件 | 结果 |
|---|---|---|
| A | 监视接口 up，`capture_hold=0` | **35 秒掉线** |
| B | 监视接口 up，`capture_hold=1` | **20 秒掉线** |
| C | 监视接口 up，`capture_hold=1`，`raw_rx=0`（写不进去，仍是 Y） | **20 秒掉线** |

内核日志给出机制：

```
[ROAM_TRIGGER]: VDEV[0] Reason: "BEACON MISS"
[ROAM_RESULT]:  VDEV[0] FAILED Reason: NO AP FOUND
```

每 15 秒一轮，直到断开。**关键点是 A 阶段也掉**：不是 capture 滤镜的问题，
是监视 vdev 的存在本身让 STA 收不到信标。所以"开关某个参数"救不了，
必须换接收路径。

顺带否掉一条捷径：`marionette_awdl_capture_ms`（限时捕获窗口）是只读的
（0444），只能加载时设定；而 `capture_ms=50` 会让模块 init 直接
`-ENODEV`（`insmod: No such device`），不可用。

### 22.6 发送侧是活的，这一条要记住

用 `RUST_LOG=trace` 打开被压在 trace 级别的公告帧日志：

```
announce=386   (16 秒)      txfail=0
```

386 帧、零失败、频点 5260、`--no-force-master`、`--park`。所以"Mac 看不见我们"
**不是**"我们没发出去"。加上 §22.1 的信道修正，发送侧目前没有已知缺陷。

### 22.7 下一步：接收必须走 STA 自己的 mgmt-rx，不能走监视 vdev

驱动参数里的计数器直接指出了出口：

```
marionette_mgmt_rx_wmi=296     固件已经把 296 个管理帧送到了 host
marionette_mgmt_rx_lim=38      38 个在 lim 层被丢
marionette_mgmt_rx_hdd=0       到 cfg80211 的一个都没有   <-- 断在这里
marionette_awdl_rx_hdd=0
```

而监视路径是通的：`marionette_mon_post_total=1267185`、`mon_post_awdl=1594`。
也就是说 **patch 0006（`sta-awdl-management-rx-registration`）的固件→host 段是好的，
缺的是最后一跳：host 侧没有把帧交给 cfg80211**（`hdd=0`）。

方向因此很明确：把接收从"监视 vdev + AF_PACKET"换成
"`NL80211_CMD_REGISTER_FRAME` 注册在 STA vdev 上"，`filin` 的 `MgmtTx` 已经有
netlink 那一套，加一个 `MgmtRx` 是同一套基础设施。这样监视 vdev 可以彻底不要，
§22.5 的代价自动消失，才谈得上"常驻"。

需要动的是驱动：让 `mgmt_rx` 在 `marionette_awdl_mgmt_rx=1` 时把 AWDL action 帧
投递到 cfg80211（`hdd` 那一跳），而不是在 lim 之后丢掉。

### 22.8 顺手修掉的启动健壮性（今天都真实踩到）

- **残留 TAP**：后端被 `kill -9` 后 `awdl0` 还在，下次启动
  `could not create TAP interface ... e=Io(16)`（EBUSY），看起来像没 root。
  `radio.sh` 现在启动前和 `cleanup` 里都 `ip link del awdl0`。
- **残留接收端占着 8771**：应用把同一个二进制装成 `libmarionette_airdrop.so`，
  按路径 `pkill` 抓不到，启动就 `Address already in use (os error 98)`。
  改成按 8771 端口反查 pid。
- **链路瞬断被当成"没联网"**：小米的 Wi-Fi 服务会周期性把链路掐一两秒，单次
  采样会把瞬断变成假报错。改成最多等 30 秒。
- **驱动楔死**：监视 vdev 用过后 `svc wifi disable/enable` 有时救不回来
  （连续两次 25 秒后仍是 `Not connected`）。可靠复位是整套模块重载
  （`awdl-stack-load.sh load ...`）。

### 22.9 本轮测试

`filin-rs` 单测 254 通过（新增 2 个锚点校验 + 5 个 `parse_attr_u32`）。
设备端跑的同一份二进制。`radio.sh` 过 `sh -n`。

---

## 23. 发送侧的三次围剿，和一个没被证明的假设

§22 修好了信道、DFS 锚点和跳台搬电台。这一节把「Mac 为什么还是看不见我们」
逼到最后一个假设，并且记下一个**没有证据支撑的环节**。

### 23.1 mDNS 曾经一封都没发出去（`--no-force-master` 的回归）

按 §22.4 改成 `--no-force-master` 之后，实测：

```
rebroadcast=0   spread=0     （整个会话，零个 mDNS 帧）
```

原因在 `rebroadcast_announce` / `drain_mcast_spread` 的同一个闸门：

```rust
if can_send_in(&awdl_state.sync, now_us, MULTICAST_GUARD_TU) != 0 {
    return;   // 在 multicast guard 内，本帧不发送
}
```

采纳真实 master 之后，`can_send_in` 从未放行 —— 我们的 sync 没有可信来源。
而 `--force-master` 下自己是 master，闸门按自己的计划打开，同一个会话：

```
announce_ok=667   spread_ok=38   spread_FAIL=0
```

**教训**：`--no-force-master` 在语义上更正确（§22.4 的理由仍然成立），
但它把唯一真正让设备可见的路径掐死了。`radio.sh` 因此**保留 force-master**，
代价（我们的可用窗口不是对端的）记录在 §23.5。这不是「选错了」，是
「更正确的那个选项在当前代码下不可用」。

### 23.2 帧类型：mDNS 是数据帧，走的是管理帧通道

`build_data_frame` 写 `0x0008`，即 **type 2 数据帧**。而
`NL80211_CMD_FRAME` 是管理帧通道 —— 补丁 0043 就是为了放宽这一行而存在：

```c
// cfg80211_mlme_mgmt_tx()
if (!ieee80211_is_mgmt(mgmt->frame_control))
    return -EINVAL;
```

0043 的放行条件很窄：**station 接口 + 数据帧 + 精确的 AWDL LLC/SNAP 前缀**
（`aa aa 03 00 17 f2 08 00`）。我们的 `encapsulate_ethernet_payload` 正好产出
这个前缀，所以帧被接受了（`spread_FAIL=0`，cfg80211 的 ack 无错误）。

但这留下一个**未证明的假设**：0044 里唯一证明过空口投递的那次，条件是
「transmitted **during a remain-on-channel**」。没有 ROC 时，`CMD_FRAME`
返回成功到底代表「固件发了」还是「固件收了但没发」，我们没有证据。
这一点写进测试注释里，因为它决定后面所有工作的方向。

### 23.3 ROC 在 STA 上不可用（新的否定结果）

想在发射前开一个 ROC 来补上 §23.2 那个假设，实测两种都失败：

```
iw dev wlan0 roc start 5260 5000   → command failed: Invalid argument (-22)
iw dev wlan0 roc start 2412 3000   → command failed: Invalid argument (-22)
iw dev wlan0 offchannel 5260 5000  → command failed: Invalid argument (-22)
```

**同信道、异信道都拒**，且是在链路正常关联的状态下。所以「本信道 ROC 不算
搬家」这条捷径不存在：STA vdev 的 remain-on-channel 在这个驱动上就是关着的。
这堵死了 0044 唯一验证过的路径，也意味着 §23.2 的假设短期内无法用 ROC 验证。

### 23.4 抓包证据：没有任何对端把我们当对端

拉回 filin 的 pcap（50 287 帧）逐帧统计：

```
发给我们（addr1 或 addr3 == 本机 MAC）的帧：42
  来源：70:3a:73:a2:26:68 ×28、70:3a:73:a2:28:32 ×6、
        40:ed:cf:b3:af:31 ×5、70:3a:73:a2:28:14 ×3
```

**Mac 的 AWDL 地址（02:57:dc:2d:32:26）一帧都没发给我们** —— 那 42 帧全部
来自 AP 的 MAC，是常规管理帧。配合 §22.6 的 Mac 侧零浏览结果，结论是：
**Mac 没有把我们登记为 AWDL 对端**，所以它既不回我们，也不把我们的
mDNS 上报给 mDNSResponder。

帧类型分布（`type0/sub8` = beacon 27 336，`sub9` = action 15 836）说明
接收侧本身是健康的。

### 23.5 当前状态的完整账

| 环节 | 状态 | 证据 |
|---|---|---|
| 发射频点 | 已修 | `tx_freq_mhz=5260`，`send failed=0` |
| filin 启动 | 已修 | DFS 锚点 + 跳台搬电台两个 bug 都堵过启动/链路 |
| mDNS 进入发送路径 | 已修 | force-master 下 `spread_ok=38` |
| 帧被 cfg80211 接受 | 是 | `spread_FAIL=0`，ack 无错误 |
| 固件是否真的把帧打出去 | **未证明** | 唯一的空口证明需要 ROC，而 ROC 不可用（§23.3） |
| Mac 是否登记我们为对端 | **否** | 抓包 42 帧全部来自 AP，Mac 零帧（§23.4） |
| 接收能否常驻 | **否** | 监视接口 20–35 秒吃掉基站信标（§22.5） |

### 23.6 下一步（按依赖排序）

1. **驱动侧补上 `mgmt_rx_hdd` 那一跳**（§22.7）：这是唯一能同时解决
   §22.5 的接收阻塞、又不依赖 ROC 的路。固件段是通的（`mgmt_rx_wmi=296`）。
2. 若 1 成立，接收换成 `NL80211_CMD_REGISTER_FRAME` 注册在 STA vdev 上，
   监视 vdev 整个不要。
3. 在 1/2 之前，发射侧不再有可动的部分 —— §23.2 的假设只能等驱动侧
   加一个「固件已提交」计数器才能定性。

---

## 23.3 两个补丁的上下文返工，以及 `.ko` 链接配方（本轮新发现）

前两次构建失败都不是代码问题，是 **GNU patch 的 fuzz 把补丁静默搬错了地方**。
这一节把两个返工和链接配方固定下来，避免再踩。

### 23.3.1 fuzz 是静默错误，必须 `--fuzz=0`

GNU patch 默认允许 2 行上下文漂移。0036 的插入块因此被整块搬进了
`hdd_open_cesium_nl_sock(void)` —— 一个没有 `adapter` 形参的函数 ——
报错是 `use of undeclared identifier 'adapter'`，看起来像代码错，其实是
位置错。它的正确落点是 `__hdd_mon_open()` 内 `netif_carrier_on(dev);`
之后（`adapter` 在那里由 `WLAN_HDD_GET_PRIV_PTR(dev)` 声明）。

构建脚本现在一律 `patch -p1 --fuzz=0 --forward --no-backup-if-mismatch`，
并在 patch 结束后跑三段审计，任一不过即 `exit 1`：

1. `WLAN_START_ALL_NETIF_QUEUE_N_CARRIER` 块存在；
2. 该块在 `__hdd_mon_open()` 内（awk 按函数体切片后 grep）；
3. `hdd_open_cesium_nl_sock()` 内没有 marionette 痕迹。

**返工补丁上下文的正确方法**（0036 与 0041 都用它）：不要手写 hunk 头。
用 Python 在 pristine 副本上构造 post-image，`git diff --no-index` 生成 hunk，
再 splice 回补丁文件（保留原有说明文字与其它文件的段）。

**关键约束**：如果补丁 B 依赖补丁 A 先落地，B 的 pre-image **必须是已应用 A
的树**。0036 第一版就是拿 pristine 当 pre-image，它的 old-image 是 6 行连续
上下文，而 0035 的插入正好落在中间那 3 行和后面 3 行之间 —— post-0035
文件里根本不存在这 6 行连续序列，Plan A 必然失败。
行号反而不用改：0035 插在 hunk 首个上下文行之后，行号不变。

### 23.3.2 0041 的半应用：声明 hunk 失败、使用 hunk 成功

0041 的 `uint32_t drop_site;` 声明 hunk 上下文写着
`unsigned int bytes;` 之后是 `uint8_t tx_vdev_id;`，但 0040 已把该变量删掉
（改成直接用 `adapter->vdev_id`）。于是只有声明 hunk 被拒、同函数里所有
`drop_site = N` 使用 hunk 正常应用 —— 典型的半应用，报 17 个
`undeclared identifier 'drop_site'`。

pre-image 重建方式：pristine + 0001..0040（构建脚本同一顺序、同样 `--fuzz=0`），
post-image 取当前树并手工补回那一行声明。已验证返工后的 0041 在
`--fuzz=0` 下逐字节复现预期 post-image。

### 23.3.3 0045 已被 0046 取代，跳过

0045 给 bounded capture slot 加 `marionette_awdl_capture_hold`；0046 把那套
slot 整个删掉且不需要监视 vdev。0045 单 hunk 的落点（pre-0046 脚手架）已不存在，
而树里没有任何符号引用 `marionette_awdl_capture_hold`，跳过它构建自洽。
叠加事实：ROC 在关联态的 STA vdev 上全部 `-22`（§22.7-3），0045 想搭配的
remain-on-channel 预约本来就用不了。

### 23.3.4 `.ko` 链接配方

`build-baseline.sh` 默认只产 `kiwi_v2.o`。要可加载 `.ko` 必须显式：

```sh
export DRIVER_TARGET=modules
export DRIVER_PROVIDER_SYMVERS=<stock-provider.symvers>
export DRIVER_JOBS=8
bash build-baseline.sh <kernel> <kernel-out> <wlan> <clang>
```

- provider symvers：`build/airdrop/driver/refs/stock-provider.symvers`，
  7891 条导出，由 `driver/stock-symvers.py` 从原厂 ELF 模块恢复（不把导入
  提升成导出、不改 CRC）。modpost 靠它核对候选导入的约 506 个符号。
- `CONFIG_DEBUG_INFO_BTF_MODULES=y` 在 kernel-out 已满足，脚本会自己检查。
- 链接产物约 520 MB，交付用 `llvm-strip --strip-debug` 降到约 24 MB
  （与 BUILD-RECIPE-0036.md 一致）。
- 模块 `.BTF` 仍缺：构建树没有 vmlinux。CFI 哈希未独立比对。

### 23.3.5 增量构建为什么只有几行 CC 输出

`git clean -qfd` 不带 `-x`，不清 gitignored 的 `.o`；`git checkout -- .` 只重写
与 HEAD 有差异的文件。所以被 patch 改过的约 20 个文件重编，其余复用旧 `.o`。
这是正常增量，不是构建没跑。

---

## 23.4 candidate-0046 上机：主机侧全通，固件是否听从仍未证

补丁 0036/0041 上下文返工（§23.3）+ `.ko` 链接配方补上之后，
`marionette-candidate-0046.ko` 于 2026-10-22 在 yuni 构建成功并上机。

### 23.4.1 构件

| 项 | 值 |
|---|---|
| 文件 | `build/airdrop/driver/candidate-0046/marionette-candidate-0046.ko` |
| 字节 | 24 111 624（链接产物 522 187 304，`llvm-strip --strip-debug`） |
| sha256 | `c9b22e0caf9d6dbe11c7bb3446d213e48f3f4a2c1ba265cb69945960e627913e` |
| 编译 | 0 error、0 warning（唯一日志行是单模块 LTO 链接的 `Module.symvers is missing` 样板） |
| ABI 审计 | 508/508 导入匹配，0 不匹配、0 未验证、0 冲突（`abi-audit-0046.json`） |
| 补丁 | 0001,0003–0041,0044,0046；跳过 0002/0043（cfg80211 侧已在设备）、0045（被 0046 取代）、0038/0039/0040（设计文档） |

模块内含 `marionette_awdl_fw_promisc` 参数，`MODULE_PARM_DESC` 完整；
vermagic `5.15.41 SMP preempt mod_unload modversions aarch64`。
模块 `.BTF` 仍缺（构建树无 vmlinux）；CFI 哈希未独立比对。

### 23.4.2 主机侧：逐项打通（肯定结果）

按 `awdl-stack-load.sh load <cfg80211-0043.ko> <candidate-0046.ko>` 加载，
基站 `70:3a:73:a2:26:68` / 5260 MHz 关联成功，链路在后续全部实验中保持。

1. **参数可写**：`/sys/module/kiwi_v2/parameters/marionette_awdl_fw_promisc`
   权限 **0600**（0020 时代是 0400 只读）。`echo 1 >` 成功，值变 `Y`。
2. **WMI 命令真的发出且被 WMI 层接受**：dmesg
   `marionette_fw_promisc_set: MARIONETTE_FW_PROMISC enable=1 pdev=0`。
   0046 的 setter 在 WMI 调用失败时会提前 return，这行日志出现在调用之后，
   所以 `marionette_wmi_rx_promiscuous()` 返回成功。写 0 有对称日志。
3. **STA vdev 能注册 AWDL action 帧**：`marionette-awdl-mgmt-tx wlan0 5260 0 --rx-ms 20000`
   打出 `register_frame(type=0x00d0, match=awdl len=6) -> 0`。
   这是 0006 经 `NL80211_CMD_REGISTER_FRAME` 走通的直接证据 ——
   接收链路的主机侧注册不再依赖监视 vdev。
4. **计数器门开着**：`marionette_awdl_mgmt_rx=Y`（0400，insmod 时设定）。
   `marionette_awdl_rx_observe()` 一开头就 `if (!marionette_awdl_mgmt_rx) return`，
   所以这个门不开，两个计数器都是死的 —— 探针必须先查它，否则会误报 NO_EFFECT。
5. **AWDL action 帧发送正常**：`marionette-awdl-mgmt-tx wlan0 5260 30` →
   `sent=30 ok=30 fail=0`。

### 23.4.3 固件是否听从：未证，卡在没有对端

`fw-promisc-probe.sh 40` 判 **NO_EFFECT**（`awdl_rx_wmi` 与 `mgmt_rx_wmi`
全程不动）。这**不能**算否定答案，原因有三：

1. **信道 52 = 5260 MHz，是 DFS 信道**，AWDL 不用 DFS。基站所在的
   `LIMO-5G ceshi` 5G BSSID 就在 5260，所以这条链路上本来就没有任何
   Apple 对端在发射 AWDL action 帧。没有帧，计数器当然不动。
2. **`mgmt_rx_wmi` 不是好对照**：关着滤镜 40 秒它一动不发。原因是信标走
   PE 信标路径、不过 `wma_mgmt_rx_process`；这个计数器只数 action/auth/assoc
   一类。只有扫描时它才动（一次全扫约 +110，定向扫三频约 +26）。
3. **扫描 A/B 只能给出弱信号**：`awdl-social-ab.sh 30 2437 5220 5745`
   （60 次定向扫，覆盖 AWDL 三个社交信道）两种臂下 `awdl_rx_wmi` 都是 0；
   但滤镜开时每扫约 31 帧、关时约 26 帧，与全扫实验（+13%）方向一致。
   说明命令确实多放行了一些帧，但**没有任何一帧是 AWDL action 帧**。

### 23.4.4 本轮新拿到的两个否定结果（都加固 0046 的前提）

1. **关联状态下监视 vdev 在任何信道都 `-EBUSY`**。试过 6、2437、52、5260，
   连同基站自己的信道一起拒。这是 QCA 单信道并发限制：监视 vdev 锁在基站
   信道上，而连设成基站信道都被拒。**"靠监视 vdev 常驻接收"到此彻底排除**，
   0046 绕开它的设计前提成立。
2. **candidate-0046 上监视 vdev 完全收不到帧**。基站 up 或 down 都一样：
   `marionette_mon_desc_total`、`marionette_mon_post_total`、`marionette_mon_hdr_awdl`
   全部为 0，tcpdump 在 awdlmon 上 0 包（对照：tcpdump 在 wlan0 上 5 秒抓到
   10 包含 mDNS）。`marionette_sta_monitor_enabled()` 只是返回
   `marionette_awdl_sta_monitor`（值为 Y），门是开的，驱动创建接口也没有报错。
   这与早前 `marionette_mon_post_awdl=1020`、50 287 帧 pcap 矛盾，
   是补丁栈里的回归或配置缺口，**待查**。它不影响 0046 的结论
   （0046 本来就不要监视 vdev），但让"确认对端是否存在"这一问暂时无解。

### 23.4.5 解开这一问需要的输入

`awdl_rx_wmi` 要动，必须让基站待在 AWDL 社交信道上（6 / 44 / 149），
同时有 Apple 对端在发射。可用的两条路：

1. ~~把基站钉到 2.4G 的 `LIMO-5G ceshi`（BSSID `5c:7d:ae:5c:60:6e`）~~
   **这一条是错的，已更正**：`5c:7d:ae:5c:60:6e` 广播的是 `YUNI_ZTE_5G`，
   不是 `LIMO-5G ceshi`。而且 `LIMO-5G ceshi` **根本没有 2.4G BSSID**，
   它只有三个 5G BSSID（见 §23.5.1），所以信道 6 这条路不存在。
   本环境唯一能用的 AWDL 社交信道是 **信道 44（5220 MHz）**。
2. **修好监视 vdev 的 RX**（§23.4.4-2）。修好之后
   `awdl-peer-check.sh <信道> <秒>` 就能直接回答"这一信道上到底有没有
   AWDL 对端"，从而把 NO_EFFECT 拆成"没有对端"还是"固件仍然专门丢 AWDL"。

另外两个小坑已修：`awdl-stack-load.sh` 的 usage 注释把 `$2`/`$3` 写反了
（实际 `$2` 是 cfg80211、`$3` 是 WLAN 候选），按注释传会把 WLAN 驱动当
cfg80211 insmod，报 ENOENT；`iw phy ... interface add` 在本驱动上会打印
`command failed: Invalid argument (-22)` 但接口其实建出来了，判断成功要看你
`iw dev` 里有没有，不能看退出码。

---

## 23.5 2026-10-22（续）：信道 44 打通，顺带抓出一个关联回归

### 23.5.1 环境测绘：本环境唯一的 AWDL 社交信道是 44

`cmd wifi list-scan-results` + `scan-parse.sh` 全量扫描（`LIMO-*` 是一个
多 AP/多 SSID 部署，BSSID 规律 `70:3a:73:<SSID字节>:<射频字节>`）：

| BSSID | freq | RSSI | SSID |
|---|---|---|---|
| `70:3a:73:a2:26:68` | 5260（信道 52） | **-43** | LIMO-5G ceshi（当前，最强） |
| `70:3a:73:a2:28:14` | 5220（**信道 44**） | -76 | LIMO-5G ceshi ← 唯一 AWDL 社交信道 |
| `70:3a:73:a2:28:32` | 5765（信道 153） | -49 | LIMO-5G ceshi |
| `70:3a:73:0a:26:68` | 2472（信道 13） | -32 | LIMO-MEMBER |
| `70:3a:73:12:28:14` | 2462（信道 11） | -66 | LIMO-CALLER（开放） |
| `70:3a:73:02:28:32` | 2422（信道 3） | -49 | LIMO-STAFF |

2.4G 只有 2422/2462/2472，**没有 2437（信道 6）**；5G 的 AWDL 社交信道
只有 5220。所以钉基站只能钉 `70:3a:73:a2:28:14`，而它比主 BSSID 弱 33 dB，
框架的网络选择器会在连上后约 40 秒把基站抢回 5260。

### 23.5.2 关联回归：`marionette_awdl_sta_monitor` 会锁死基站信道

**现象**：候选模块加载后，把基站从 5260 挪到 5220 一律失败。supplicant 侧：

```
wlan0: Trying to associate with SSID 'LIMO-5G ceshi'
qca_drv_connect_fail_reason_code_handler: Vendor attr not found
wlan0: CTRL-EVENT-ASSOC-REJECT bssid=70:3a:73:a2:28:14 status_code=1   ← 2~20 ms
```

驱动侧（dmesg）：

```
wlan0(vdevid-0): ... Connect with 70:3a:73:a2:28:14 SSID "LIMO-5G ceshi"
    is FAILURE cm_id 0xc00000x cm_reason 0 status_code 0 is_reassoc 0 send discon 0
```

`cm_reason 0 status_code 0` 且只有 2~20 ms：**主机侧预检就拒了**，
一帧都没上空口；对端在 -76 dBm 也不可能是 2 ms 的回合。

**对照组（stock）**：同一命令、同一配置、同一时刻，stock 模块
`Trying to associate` 17:35:22.756 → `Associated with` 17:35:23.014
（258 ms，完整 auth+assoc）。**所以不是 AP 拒、不是配置坏，是补丁栈的回归。**

**二分定位**（`native/airdrop/tools/assoc-gate-bisect.sh`，每次都先把基站
钉在 5260、静置 60 秒、再试 5220）：

| insmod 参数 | 5220 关联 |
|---|---|
| 全部门开（原 load 脚本） | **FAIL** |
| `marionette_awdl_roc_rx=1` | SUCCESS |
| `marionette_awdl_sta_monitor=1` | **FAIL** |
| `marionette_awdl_monitor_raw_rx=1` | SUCCESS |
| 全关 | SUCCESS |

**根因**：补丁 0009 让 `wlan_mlme_is_sta_mon_conc_supported()` 在门打开时
无条件返回 true。policy_mgr 据此认定"STA+monitor 并发可行"，于是把 STA 的
可用信道集合限制到 monitor vdev 的信道，之后 STA 连任何别的信道都在预检里
被拒。0009 自己的注释也写了它是 `[EXPERIMENTAL]`，目的是"关联着 STA 的同时
跑监视 vdev"——而这正是 0046（常驻固件 promiscuous RX）要淘汰的路径。

**修复**：`awdl-stack-load.sh` 不再传 `marionette_awdl_sta_monitor=1`
（该参数 mode 0400，只能 insmod 时设）。修复后实测：基站 5260 → 5220
关联 302 ms 成功，信道 44 可随时钉住。补丁 0009 应像 0045 一样在驱动栈里
标记 superseded。

### 23.5.3 信道 44 上的 0046 实测：仍未看到 AWDL 帧

修复后在信道 44 上完整跑了一遍 `fw-promisc-probe.sh`（40 秒，
信道记录确认全程 44，17:49:38~17:50:14）：

```
baseline: awdl_rx_wmi=0 mgmt_rx_wmi=91
gate=Y（dmesg: marionette_fw_promisc_set enable=1 pdev=0 两次调用都在）
t=30s mgmt_rx_wmi=131   t=35s mgmt_rx_wmi=263
verdict: awdl_rx_wmi 0 -> 0，mgmt_rx_wmi 91 -> 263
```

**+172 是扫描伪影**：dmesg 显示 `scan id 40963 COMPLETED ... scan found 43 bss`
就在 17:50:10.7，与计数器跳变（t=30/35s）精确重合；上一轮 60 秒探针的
+219 同样重合于一次全带宽扫描（55 bss）。所以两轮的真实结论一致：
**过滤器已下发、基站确实待在 AWDL 社交信道上，但 40~60 秒内没有收到任何
AWDL action 帧**。

这两轮都不能判定"固件专门丢 AWDL"，因为**没有任何证据表明期间有 Apple
对端在发射 AWDL**（iPhone/Mac 只是连着 Wi-Fi，AirDrop 分享界面没开）。
在拿到"对端确实在发射"这个输入之前，NO_EFFECT 与 FILTER_OPEN_BUT_NO_AWDL
都只是"没有对端"这一种解释。

**第三轮（用户确认 AirDrop 已打开后，19:31:33~19:32:39，全程信道 44）**：
这次用 `cmd wifi enable-scanning disabled` 把基站钉在 44 上跑满 60 秒，
得到一个**真正的零对照**——`mgmt_rx_wmi` 424 → 424 **一动不动**。
这反过来证明了前两轮的 +219/+172 确实全是扫描伪影：扫描一停，
WMI 管理帧计数就完全静止（连关联 AP 的 beacon 都不走
`wma_mgmt_rx_process`，本来就不该计入）。所以第三轮的结论是干净的：
**过滤器已下发、基站确在 AWDL 社交信道上 60 秒、固件一帧管理帧都没往上交**。
但仍不能归因给固件——用户打开 AirDrop 距测量已过约 1.5 小时，iOS 的 AWDL
在共享面板关闭后不久就会下线，对端很可能早已静默；且 iPhone/Mac 与本机的
直线距离未知（AWDL 是设备直连，不经过 AP）。
想独立验证"有没有对端"也用不了监视 vdev：`awdl-peer-check.sh 44 25`
在基站 down 之后依然 `FAIL: monitor vdev`（接口建不出来）。

### 23.5.4 下一步（按依赖顺序）

1. **让对端发射**：在 iPhone 上打开 AirDrop（或共享面板），在 Mac 上打开
   AirDrop 接收。这是唯一还缺的输入，只有用户能给。
2. 对端活跃后重跑 `fw-promisc-probe.sh 60`：
   - `awdl_rx_wmi` 动了 → **FIRMWARE_OBEYED**，STA vdev 常驻接收成立，
     监视 vdev 可以正式退役；
   - 仍不动 → 固件确实专门丢 AWDL action 帧，需要换杠杆
      （例如 0008 的 ROC 窗口：关联着 STA 的同时在社交信道上开
      remain-on-channel，AWDL 可用窗口只有 ~112 ms/500 ms，ROC 正好覆盖）。
3. 把补丁 0009 标记 superseded（或把 `wlan_mlme_is_sta_mon_conc_supported()`
   里的无条件 return true 去掉），重新出 candidate-0047。
4. §23.4.4-2 的"监视 vdev 在 candidate-0046 上 0 帧"回归仍未解释，
   但在 0046 路线成立后它只是旁支。

## 24. 2026-10-22 用户态广播与测量方法审计

继续测试前发现数个独立于固件的确定性问题，现已修复，详见 `docs/AIRDROP.md` 当日检查点。

最关键的是 TLV 4 value[1..3] 原写入了 AW 序号，实际应是到下一个 EAW 的 TU 倒计时；真正序号在 value[29..31]。模板构造器还没有从当前状态更新信道表、选举 metrics/height/sync parent。直接修驱动无法消除此类错误广播。广播现在共享 `SyncState` 的时钟，并从当前状态填写两处信道表和选举数据。新回归测试要求真实生成的广播能被接收端同步，并在一个 EAW 内找到发送窗口。

第二项为 Netlink 合并数据报：一次 recv 可能同时带 cookie reply 和 ACK，旧代码只检查第一条会漏掉 ACK。现遍历所有对齐 nlmsghdr，序号匹配后才确认提交成功；仍不把提交成功描述为空口到达。

当前 257 项 filin Android ARM64 真机测试通过。未替换/刷写新的驱动，也未证明端到端文件互传。现场同时有另一份 awdl-slot-sweep.sh 运行、驱动被独立更换；进一步无线实验需要先隔离，避免结论混淆。

## 25. 2026-10-23：AWDL 接收首次成立，且证明不需要跳台

这一节把 §23.4/§23.5 悬了一整天的问题关掉了：**固件没有丢弃 AWDL，接收路径一直是通的，
之前所有"0 帧"都是测量配置错误。**

### 25.1 测量条件

- 驱动 `candidate-0045`（带捕获时隙与 `sta_monitor`），参数
  `mgmt_rx=1 roc_rx=1 sta_monitor=1 monitor_raw_rx=1`
- 基站 `70:3a:73:a2:26:68` 信道 52 / 5260 MHz / −42 dBm
- `iw phy phy0 interface add awdlmon type monitor`，**未设信道，跟随基站**
- 对端：Mac，隔空投送窗口前台常驻

### 25.2 结果

| 运行 | 时长 | pcap 总包 | pcap 内 AWDL 帧 | Δ`mon_post_awdl` |
|---|---|---|---|---|
| run1 | 20 s | 56 314 | 32 | +32 |
| run2 | 25 s | 6 625 | 9 | +9 |

帧特征：`addr1` 广播、`addr3` = `00:25:00:ff:94:73`、前缀 `7f 00 17 f2 08 10`；
run1 含 PSF 3 帧 + MIF 29 帧，帧长 328–1079 B。**pcap 帧数与驱动计数逐次相等**，
证明 `marionette_mon_post_awdl` 统计的确实是空口真实 AWDL 帧。

证据：`build/airdrop/driver/awdl-rx-20261023/`（含 41 帧完整字节与分析脚本
`tools/pcap-awdl-analyze.py`）。

### 25.3 两个必须记住的推论

1. **不需要跳台到信道 44。** Mac 的 AWDL 同时使用信道 6（2437）和它与手机共享的
   AP 信道 52（5260）。手机与 Mac 同 AP 时，监视端跟随基站信道即可收到 AWDL。
   §23.5 那些"信道 44 上 0 帧"的结论，根因不是固件丢帧，而是**对端不在 44 上**。
2. **`awdl_rx_wmi` 恒为 0 不是故障。** 它在 STA/mgmt 路径；AWDL 广播走监视 vdev 的
   direct-buffer 路径，由 `mon_post_awdl` / `mon_desc_total` 统计。此前把
   `awdl_rx_wmi` 当唯一判据，是这条弯路的主因。

### 25.4 尚未解决

- **共存不稳定**：监视 vdev 建立后约 20–40 s 被驱动拆除，同时基站掉线；删掉 vdev 后
  基站 1 s 内恢复。补丁 0009 的 monitor-while-associated 生命周期仍需修，这是
  "常驻"的当前第一障碍。
- `iw dev awdlmon set channel` 仍 `-16`；补丁 0018 时隙写入返回 `rc=1`（原因未查清）。
  当前测量不需要跳台，未阻塞。
- 只证明**接收**。发送、双向发现、IPv6 数据通道、文件互传均未验证。

### 25.5 下一步（按依赖）

1. 修监视 vdev 生命周期：让 vdev 常驻且不拆基站（替换 0009 的实验性做法）
2. 在常驻 vdev 上跑 `filin-rs` 的发现状态机，验证能识别出对端并进入握手
3. 发送侧：在已证明可收的信道上做 AWDL action 发送，观察对端是否回应
4. 双向发现成立后再做 IPv6 数据通道与文件互传
5. 最后才是 UI 联调与功耗验收

---

## 26. 2026-10-23：host-only sink 打通，常驻接收的死结解开

### 26.1 死结是什么

§25 之后，"常驻 AirDrop 接收"卡在一个二选一上：

- monitor RX ring **只在 monitor vdev 存在时**工作，否则 `mon_desc_total` 恒为 0；
- monitor vdev 存在约 10 s 后固件必定 `RDDM(2)`，STA 被踢，驱动 shutdown。

补丁 0046（固件 promiscuous）原本想绕过 vdev，实测不成立；补丁 0045 的
`capture_hold` 只能把滤镜从"移动信道"里解耦，解不开 vdev 的生命周期。

### 26.2 0047 的做法

补丁 0047 在 `marionette_monitor_post_frame()` 里加了一条主机侧投递路径：
把已识别的 AWDL 帧套一个 12 字节 radiotap（present=CHANNEL，freq 可配，默认 5260）
后 `netif_rx()` 到一个自建的 `awdlsink` netdev
（`ARPHRD_IEEE80211_RADIOTAP`，`IFF_NOARP`，MTU 2304，`ndo_start_xmit` 直接丢包）。

要点：**`awdlsink` 是纯主机侧对象**，只在驱动 shutdown 时由
`marionette_awdl_sink_exit()` 销毁，与 monitor vdev 的生命周期无关。
它还是**懒创建**的——首个 AWDL 帧到达时才 `alloc_netdev()` + `register_netdevice()`，
所以那一帧会被计入 `sink_drop`，之后全部正常投递。

### 26.3 端到端结果

5 轮 × 2 s 窗口（每轮建 vdev → `ip link up` → `hold=1` → 抓 2 s → `hold=0` → 删 vdev）：

| 轮次 | mon_desc_total | mon_post_awdl | sink_rx | sink_drop | STA |
|------|---------------:|--------------:|--------:|----------:|-----|
| base | 0 | 0 | 0 | 0 | 在线 |
| c1 | 7560 | 6 | 4 | 2 | 在线 |
| c2 | 15435 | 14 | 12 | 2 | 在线 |
| c3 | 23664 | 23 | 21 | 2 | 在线 |
| c4 | 30703 | 32 | 30 | 2 | 在线 |
| c5 | 37783 | 42 | 40 | 2 | 在线 |

`RDDM` 计数不变，uptime 连续。随后 `tcpdump -i awdlsink` 3 轮窗口抓到
**20 个包，全部 5260 MHz，全部 AWDL action 帧**，`a3=00:25:00:ff:94:73`，
body 前缀 `7f 00 17 f2 08 10 03 00`（subtype 3 = MIF）。

证据：`build/airdrop/driver/sink-20261023/`（pcap + 分析 + 两个测量脚本）。

### 26.4 两个容易踩的坑

1. **写 `capture_hold` 之前必须先 `ip link set awdlmon up`。**
   `marionette_capture_hold_set()` 要求
   `test_bit(DEVICE_IFACE_OPENED, &adapter->event_flags)`，该位在 link up 后才置位。
   漏了这一步会得到 `rc=1`、dmesg 里连 `MARIONETTE_CAPTURE_HOLD` 都不打印，
   表象与"补丁没编进去"完全一样。`radio.sh` 第 85 行本来就有这一步。
2. **不要用 `statistics/rx_packets` 判断用户态是否可读。**
   没有 AF_PACKET 套接字时 skb 在协议分发层被计成 `rx_dropped`，
   而 packet tap 拿到包也不增加 `dev->stats.rx_packets`。
   判据是 pcap 有没有内容。

### 26.5 常驻架构（由此确定）

```
radio.sh 启动
  ├─ 预热窗口：建 awdlmon → up → hold=1 → 等 1 s → hold=0 → 删 vdev
  │            目的只是逼出 awdlsink
  ├─ filin 绑定 awdlsink（常驻，不再重绑）
  └─ 监督循环：每 T 秒开一个 2 s 窗口（建 vdev → up → hold=1 → 2 s → hold=0 → 删）
               awdlsink 在窗口之间继续存在，用户态 socket 不受影响
```

窗口长度上限来自 §26.1 的 RDDM 实测（9.9 s 崩溃），2 s 有 5 倍余量。
§25.4 里"监视 vdev 生命周期需修"这一条因此**降级**：不再需要 vdev 常驻。

### 26.6 下一步

1. 把 `radio.sh` 改成 §26.5 的形态，`-i awdlsink` 替代 `-i awdlmon`
2. 确认 filin 在 `awdlsink` 上不因 `-c <channel>` 失败而退出（awdlsink 无 wiphy）
3. 窗口占空比调参：窗口 2 s / 间隔 2 s 起步，观察发现延迟与功耗
4. 发送侧不变（NL80211_CMD_FRAME on wlan0），验证对端回应
5. 补丁 0009 的 `wlan_mlme_is_sta_mon_conc_supported()` 无条件 `return true`
   应标记为 superseded：STA+MON 并发固件不支持，只会换来 RDDM

### 26.7 运行时验证（radio.sh 改造后）

`radio.sh` 已改成 §26.5 的形态（预热窗口逼出 `awdlsink` → filin 绑定 `awdlsink`
→ 监督循环按 2 s 窗口 / 4 s 周期占空比）。真机 24 s 短跑与 54 s 长跑结果：

- `state` 全程 `listening`，`sink_rx` 单调增长（24 s 内 +36，54 s 内 +79）
- `awdlmon` 在 `vdev=1` / `vdev=0` 之间稳定交替，即窗口确实在开合
- STA 每个采样点都在线，`Driver recovery` 计数全程不变
- 停止后 `vdev_left=0`、`tap_left=0`，`awdl0` TAP 也被清理
- filin 日志出现真实解码结果：
  `chanseq TLV decoded ... decoded=[52, 0, 149, ...]`，
  并为 3 个对端 MAC（`3e:e2:96:3b:67:1c`、`52:33:4b:eb:5f:19`、`92:eb:8f:fc:29:0e`）
  安装了邻居表项

即**常驻接收 + 对端发现**这一段已经端到端成立。

### 26.8 一个打包缺陷：内置后端是旧构建

改造后第一次运行 `radio.sh` 直接失败，`filin.log` 只有：

```
INFO filin: filin starting
Error: invalid CLI arguments: Invalid
```

二分后确认与 `awdlsink` 无关，是 `-c 52` 被拒。对已安装的
`libmarionette_awdl.so` 逐信道测试：

| `-c` | 6 | 44 | 149 | 52 | 36 | 100 |
|------|---|----|-----|----|----|-----|
| 被接受 | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |

只接受三个 social channel，说明设备上跑的是**早于
`accepts_a_dfs_station_channel_as_anchor` 修复的构建**。仓库里的
`app/src/main/jniLibs/arm64-v8a/libmarionette_awdl.so`（1 953 032 B，10-22 22:10）
已经包含修复：同一测试 44/52/36 全部通过。设备上那份是 1 946 656 B 的旧文件，
来自更早的 APK。

影响：只要基站落在 DFS 信道（本项目实际网络就是 52），常驻服务**永远起不来**，
而错误信息只有一句 "invalid CLI arguments"，与驱动、补丁都无关。这解释了此前
多次出现的 `等待兼容驱动 · Wi-Fi 保持正常，暂不能互传`。

修复：重新打包 APK，把仓库里的 `.so` 与新版 `radio.sh` 一起装进去。

### 26.9 120 秒常驻长跑（验收数据）

重启后重载驱动栈复跑，上一轮第 54 秒的意外重启未复现：

```
elapsed_uptime=129
awdl_delivered=167
new_recoveries=0
sta_loss_samples=0
peer_neighbors=92
filin_errors=0
vdev_left=0
tap_left=0
```

120 秒交付 167 帧（约 2.8 帧/2 s 窗口），`Driver recovery` 为 0，41 个采样点
STA 全部在线，filin 安装 92 条邻居表项且零报错，退出后无残留接口。

---

## 27. 2026-10-23：AWDL 数据帧打通，但 vdev 反复建删会打崩驱动

### 27.1 数据帧为什么之前一帧都收不到

`marionette_monitor_post_frame()` 的第一句是

```c
if (frame[0] != 0xd0)
	return;
```

`0xd0` 是管理帧里的 Action 子类型。也就是说**非 action 帧在函数入口就被丢掉**。
在只挂计数器的时候这看不出来，但对 sink 是致命的：AirDrop 的 mDNS 服务通告、
以及后续的文件载荷，走的都是 802.11 **QoS 数据帧**，不是 action 帧。

2026-10-23 实测（0047 已能正确投递 action 帧）：

```
mon_desc_data        144821   ← 数据帧一直都在监视环里
mon_post_awdl           452   ← 只有 action 帧被分类
mdns_rx_other             0   ← filin 从未看到对端的 mDNS
```

所以环里一直有数据，是这行判断把它扔了。

### 27.2 补丁 0048

放行数据帧的最小改动：把类型判断从"必须 0xd0"改成"非 action 就走数据分支"，
数据帧同样用 addr3 == `00:25:00:ff:94:73` 判定（AWDL 数据帧不带
`7f 00 17 f2 08 10` 前缀，所以原有谓词无法复用），并新增
`marionette_mon_post_data` 计数把两类帧区分开。不碰固件、不建 vdev、不动信道。

真机验证：`marionette_mon_post_data` 参数存在，窗口内出现 `ddata=1` 且同时
`dawdl=0`，即该帧只被新分支接住 —— 数据帧路径成立。

### 27.3 但是：vdev 反复建删会触发 QDF ASSERT

同一个测量里出现了驱动告警，并且 STA 掉线：

```
target_if_vdev_mgr_rsp_timer_start: PSOC_0 VDEV_3: DELETE requested, waiting for STOP response
QDF ASSERT in target_if_vdev_mgr_assert_mgmt Line 103
WARNING: CPU: 1 PID: 13258 at .../target_if_vdev_mgr_tx_ops.h:103 target_if_vdev_mgr_rsp_timer_start
...
hdd_stop_adapter_ext: Release wakelock for STA + monitor mode!
```

含义：上一次 VDEV DELETE 还在等固件的 STOP 响应，下一次建/删就已经发起了，
驱动的 vdev manager 断言。后果是 STA 被一起停掉，链路掉线。

同一天还发生了**两次整机重启**（`ro.boot.bootreason=reboot`，pstore 为空），
两次都在占空比常驻运行期间：一次是 90 s 长跑的第 54 秒，一次是常驻服务跑了
约十分钟后。120 s 那次没有复现，说明它不是每轮必现，而是**随累积次数上升的
概率事件** —— 这正是断言反复触发后的必然走向。

所以 §26.9 的 120 s 数据仍然有效（证明"能持续拿到帧"），但**不能当作常驻稳定性
的结论**。当前真正的第一障碍从"固件 RAMdump"变成了"vdev manager 断言"。

### 27.4 下一步：不要再建删 vdev

断言来自 vdev 的创建/删除，不来自滤镜。所以下一个实验应该把 vdev 变成**常驻**，
只切换 `marionette_awdl_capture_hold`：

```
awdlmon 常驻 up（只建一次，永不删）
  每周期：hold=1 → 等 2 s → hold=0 → 等 T
```

需要验证的两件事：

1. 常驻 vdev + 滤镜关闭时，固件还会不会 RDDM（此前 9.9 s 崩溃是在滤镜持有状态下测的，
   两者从未分开过）；
2. 常驻 vdev 会不会重新引入早期观察到的 beacon loss。

如果 1 成立（滤镜关闭时不崩），这条路径就能彻底绕开 vdev churn，常驻才真正站得住。

### 27.5 另一个已知缺口：TSFT

sink 的 radiotap 只有 CHANNEL 字段，没有 TSFT，所以 filin 的
`tsf_offset_us` 恒为 0、`synced=false`、`aw_alignment_pct=0`。
对端发现能成立（92 条邻居表项），但可用性窗口无法对齐。
需要把描述符里的时间戳一路传到 `marionette_monitor_post_frame()`，是独立改动。

---

## §28 常驻监视 vdev 成立：断言归零（2026-10-23）

§27.4 提出的两条验证都做完了，结论是：**常驻 vdev 可行，前提是补丁 0049 + 0050**。

### 28.1 0049：`hold=0` 真的释放

`marionette_monitor_refresh_filters()` 原来无条件安装监视滤镜：

```c
	dp_mon_filter_setup_mon_mode_1_0(pdev);
	return dp_mon_filter_update_1_0(pdev);
```

所以写 `capture_hold=0` 等于把刚该撤掉的滤镜又装了一遍。补丁 0049 把相位
显式传进来，释放走 detach 路径用的 `dp_mon_filter_reset_mon_mode_1_0()`：

```c
	if (!hold)
		dp_mon_filter_reset_mon_mode_1_0(pdev);
	else
		dp_mon_filter_setup_mon_mode_1_0(pdev);
```

实测释放生效：dmesg 出现 `MARIONETTE_STA_NORMAL_RX_RESTORED status=0 monitor=0`，
且释放后基站 RX 继续增长。**但链路仍然会断。**

### 28.2 真正的原因：beacon miss 指示是锁存的

三种形状，全部常驻 vdev、全程不删：

| 形状 | 结果 |
|---|---|
| 只 hold 2 s 一次，之后静默 60 s | rxbytes 105748 → 119214 涨了 13 s，t=15 s 断开，3 次 bmiss |
| 2 s hold / 3 s 释放循环 | t=15 s 断开，`assert_delta=0` |
| 200 ms hold / 800 ms 释放循环 | t=12 s 断开，`assert_delta=0` |

**200 ms 那行是关键**。如果漏包是按 beacon 计数、收到好 beacon 就清零，200 ms
的窗口永远攒不到阈值。它和 2 s 一样死在 ~12–15 s，说明固件在**第一次装滤镜时
就把指示锁存了**，之后按自己的节奏触发，中间收多少 beacon 都不影响。

链路是：

```
wmi event → wma_beacon_miss_handler()                    core/wma/src/wma_mgmt.c
          → WMA_MISSED_BEACON_IND
          → lim_ps_offload_handle_missed_beacon_ind()    "Received Heart Beat Failure"
          → lim_send_heart_beat_timeout_ind()
          → AP probing → QCA_DISCONNECT_REASON_BEACON_MISS_FAILURE
```

第一个箭头之后全是主机侧策略，所以补丁 0050 在 `wma_beacon_miss_handler()`
入口丢弃该指示 —— 在停队列和 PE 探测之前，数据继续流动。

静音是**可写模块参数**而不是无条件行为，因为它确实会掩盖真实掉线：

```
/sys/module/kiwi_v2/parameters/marionette_awdl_bmiss_mute
```

radio.sh 在常驻会话开始时置 1，结束时置 0，并自己用 `iw dev wlan0 link`
连续两次采样判断链路是否真的没了（真没了就拆 vdev 让框架重连）。

### 28.3 实测：120 s 与 300 s

| 时长 | 基站 | AWDL action | AWDL data | 断言 | recovery | 拆除 |
|---|---|---|---|---|---|---|
| 120 s | 全程 sta=1 | 36 → 197（+161） | 1 | 0 | 0 | 干净，拆后仍连接 |
| 300 s | 全程 sta=1 | → 747 | 7 | 0 | 0 | 干净 |

`muted_events` 在 120 s 那轮是 1（固件只报了一次，被丢掉后不再报），
300 s 那轮是 0。也就是说这个抑制的代价极小：一次 dmesg 行。

**这是本项目第一个"常驻 + 零断言 + 基站不掉"的接收形态。**

### 28.4 radio.sh 改为常驻

`app/src/main/assets/airdrop/radio.sh` 不再每周期建删 vdev：

- `mon_open()` 只建一次，`ip link set up`，先写 `capture_hold=0`，**再写 mute=1**
  （顺序是 load-bearing：mute 必须在第一次 hold 之前到位）；
- 循环只切 `hold_on` / `hold_off`，vdev 常驻；
- 新增能力门禁：`marionette_awdl_bmiss_mute` 不可写就按名字拒绝启动
  （缺 0050 时服务会"看起来在跑"但悄悄把 Wi-Fi 弄断）；
- 链路判断改为连续两次 `iw dev wlan0 link` 失败才算丢，丢则拆 vdev 等重连。

### 28.5 仍然缺的

- **TSFT**（§27.5）：sink 的 radiotap 只有 CHANNEL，`aw_alignment_pct=0`，
  可用性窗口无法对齐，文件传输仍被这一条卡住。
- `data` 计数已经能涨（300 s 内 7 帧），但 `sink_rx` 仍为 0：QoS data 帧走的是
  0048 新增分支，落地到 sink 的路径还没验证过。

---

## §29 TSFT 打通同步：`synced=true`、对齐率 81%（2026-10-23）

### 29.1 sink 一直是空的（补丁 0052）

排查 TSFT 时先发现一个更基础的问题：

```
marionette_mon_post_awdl   = 288
marionette_awdl_sink_rx    = 0
marionette_awdl_sink_drop  = 288
dmesg: MARIONETTE_AWDL_SINK_UP_FAILED ret=-17   （反复）
ip -d link show awdlsink -> ifindex 34, UP, mtu 2304
```

`awdlsink` 是**上一个模块实例残留**的网卡。`marionette_awdl_sink_exit()`
只挂在 `hdd_wlan_shutdown()` 末尾，rmmod 并不总走那条路，于是新实例
`register_netdevice()` 恒 `-EEXIST`，`marionette_sink_dev` 永远是 NULL，
**每一帧都进 sink_drop**。

这个故障的表现和"附近没有对端"完全一样：tcpdump 能绑上残留网卡、抓不到包，
filin 的 `mdns_rx_other` 一直是 0。以后判断接收是否正常，必须把
`sink_rx` 和 `post_awdl` 一起看。

修复过程中还踩了一个坑：先用 `dev_get_by_name()` + `unregister_netdevice()`
回收，结果 `unregister_netdevice()` 要等引用计数归零，而查找引用在它之后才释放
→ **workqueue 卡在 D 状态**（`kworker/6:61 D`），sink 从此再也不重建。而且这个
内核根本不导出 `dev_change_name` / `dev_get_by_name`（modpost:
`"dev_change_name" undefined`）。最终用的是内核自己的惯用法：rtnl 下
`for_each_netdev_safe` + `unregister_netdevice_queue` / `unregister_netdevice_many`
（这两个是导出的），并且在 `hdd_module_exit()` 里也调用一次 sink 销毁，
从根上不再产生残留。

### 29.2 补丁 0051：把描述符 TSF 带进 radiotap

sink 原来只有 CHANNEL 字段（`present=0x08`, `it_len=12`）。filin 的
`parse_action_frame()` 只在有 TSFT 时才走 `bridge.timestamp_rx()`，否则退回主机
时间，于是 `/status` 长期是：

```
tsf_offset_us=0  synced=false  aw_alignment_pct=0
```

`dp_rx_mon_deliver()` 里 `mon_pdev->ppdu_info.rx_status.tsft` 就在手边（CBF 路径
几十行前刚用过同一个字段），把它经
`marionette_monitor_post_frame(frame, len, tsft)` 传进 sink。有 TSFT 时 radiotap
变成 24 字节：

```
offset 0  version 0, pad
offset 2  it_len = 24
offset 4  present = 0x09  (TSFT | CHANNEL)
offset 8  TSFT, 8 字节 LE
offset 16 channel freq + flags
offset 20 填充，保证 802.11 头 8 字节对齐
```

没有时间戳时保持旧的 12 字节形状，行为不变。

实测 `tsf_offset_us` 立刻从 0 变成 ~778 578 000（约 778 s 的硬件时间偏移），
说明时间戳真的进来了。

### 29.3 关键发现：`-f` 不是 force-master，而 force-master 默认是开的

`filin --help` 写得很清楚：

- `-f, --no-rssi-filter` —— 关闭 RSSI 过滤（radio.sh 一直在用它）
- `-M, --force-master` —— **默认开启**，`--no-force-master` 才能退出

force-master 意味着"永不采纳对端主节点"，所以 `synced` 永远是 false、
`master_anchor` 永远是 0、对齐率永远是 0。§23 里"force-master 是必要的"这个
结论其实是被缺 TSFT 掩盖的：**没有同步就没有可用性窗口，`can_send_in` 自然
永远不开**，于是看起来"必须自封主节点才能发包"。

### 29.4 `--no-force-master` 实测（120 s）

| t | synced | anchor | alignment | peers | master |
|---|---|---|---|---|---|
| 12 s | true | 6 | 0% | 2 | f2:34:87:6a:86:f9 |
| 36 s | true | 6 | 50% | 3 | f2:34:87… |
| 48 s | true | 6 | 66% | 3 | f2:34:87… |
| 72 s | true | 6 | 70% | 3 | da:46:13:78:51:9d |
| 96 s | true | 6 | 76% | 3 | f2:34:87… |
| 120 s | true | 6 | **81%** | 2 | ea:84:31:24:29:0b |

同时日志显示它真的采纳了对端的信道序列：

```
adopted master channel sequence master=[226,171,89,132,44,131]
  decoded=[52,0,149,...] no_ir_replaced=[52] injected=[44]
  final=[6,44,149,6,149,6,6,6,6,44,149,6,149,6,6,6]
```

`master_changes` 在 28 次左右，说明它在一个真实的多对端集群里反复选举，
而不是自说自话。

**这是本项目第一次出现"和对端共享可用性窗口"的状态**，也是文件传输的前置条件。

### 29.5 radio.sh 已改

`FILIN_TX_IFACE=... "$BACKEND" -i "$SINK" -N -f -c "$CHANNEL" --park
--no-force-master`

注释里写明了为什么必须带这个参数，以及 `-f` 到底是什么。

### 29.6 下一步

1. 服务态验证 `synced=true`（radio.sh 改完要重装 APK）；
2. 同步成立后，把捕获窗口收窄到对端的可用性窗口（省电、少抢 beacon）；
3. 等对端打开 AirDrop 面板，看 `mdns_rx_other` 是否稳定增长 —— 那是"能被发现"
   的判据；
4. 之后才是 IPv6 数据通道与文件传输。

### 29.7 应用侧：孤儿接收端会拖出重启循环

服务态验证时发现断言在 120 s 内涨了 5 条，但 radio.sh 已经是常驻形态、不删
vdev 了。日志显示：

```
I/MarionetteAirDrop: spawn[RECEIVE]: ... libmarionette_airdrop.so -i awdl0 receive ...
I/MarionetteAirDrop: exit[RECEIVE]: 1        （16 ms 后）
```

每 22 s 一次。手动跑接收端，真正的错误是：

```
Error: Address already in use (os error 98)
```

`ss -lntup` 显示一个**孤儿** `libmarionette_airdrop`（pid 31468）还占着
`*:5353`。应用被强杀（`am force-stop`、装新包、系统回收）时，接收端是应用 uid
的子进程，不会跟着死；下次启动新接收端绑不上端口 → 立刻退出 → `runSession`
判定"后端退出" → 等 `RETRY_SECONDS`(20 s) → **重启整条链路** → radio.sh 建删一次
监视 vdev → 一条断言。

杀掉孤儿后断言立刻停止增长（25 → 25 / 120 s），链路进入稳态。

修复（`AirDropService.kt`）：`startRadio()` 在真正起网卡之前先
`reapStaleReceiver()`，用 `su -c "pkill -9 -f libmarionette_airdrop.so"` 收掉
孤儿。重装后复测：断言 26 → 26 / 120 s，`synced=true`、`align=92%`、
`peers=4`、`mdns_rx_other=4`。

**教训**：接收端绑定失败这类"应用层"故障，最终会以驱动断言的形式出现，
因为重启路径会重建 vdev。看断言不能只看驱动。

## §30 附近设备发现落地：进程内 browse，并更正 §29.7 的根因（2026-10-23）

用户需求里"支持实时扫描附近 airdrop 设备"一直是空的：`find --watch` 存在、UI 也在
调，但列表永远为空。这一节把它做成真的，同时推翻了 §29.7 的一个结论。

### 30.1 更正：孤儿卡住的是 8771，不是 5353

先做了个能一次问清"到底哪个端口冲突"的实验：先起一个带 `--browse` 的接收端，
再分别试第二个接收端和 `find -d 3`。

```
=== second receiver (same HTTPS port 8771) ===
exit=1
Error: Address already in use (os error 98)

=== find -d 3 while receiver is announcing/browsing ===
exit=0
{"duration":3,"event":"browsing"}
{"address":"fe80::eaf7:91ff:fe1f:f8fe","event":"peer","name":"luftlift-7044._airdrop._tcp.local.","port":8771}

ss -lunp | grep 5353
UNCONN 0 0 *:5353 *:* users:(("ltb.so",pid=7044,fd=9))
ss -ltnp | grep 8771
LISTEN 0 128 *:8771 *:* users:(("ltb.so",pid=7044,fd=3))
```

结论很干脆：mdns-sd 的 `new_socket()` 设了 `SO_REUSEADDR` + `SO_REUSEPORT`，
**两个进程可以同时持有 5353**，`find` 与接收端并存完全正常；真正 `EADDRINUSE`
的是接收端的 HTTPS 监听 **8771**（`server::bind_listener` 没有 reuse）。

所以 §29.7 里"孤儿接收端还占着 `*:5353`"应更正为"还占着 `*:8771`"。修复
（`reapStaleReceiver()` 在起网卡前 `pkill -9 -f libmarionette_airdrop.so`）不变，
"断言 26 → 26 / 120 s"的实测也不变 —— 只是解释错了一个端口。**教训**：报错文本
只有一句 `Address already in use`，不要凭"最像的那个服务"下结论，把每个候选端口
都单独试一遍。

### 30.2 为什么仍然把 browse 做进接收端

既然 5353 可以共享，为什么不用现成的 `find --watch`？三个理由，第一条被实测直接
打中：

1. **外部进程分不清哪个宣告是本机**。上面 `find` 的输出就是证据：它把本机
   `luftlift-7044._airdrop._tcp.local.` 当成了一个"附近设备"。UI 会把自己列在
   自己的设备列表里，点一下还会朝自己发文件。
2. 两个进程各收一份包、各建一份缓存，宣告与发现的接口作用域也可能不一致。
3. 应用要合并两条 stdout 事件流、管两个进程的生命周期，而它们必须同生共死
   （`awdl0` 地址一变，两者都要重建）。

### 30.3 实现：`receive --browse`

在**宣告用的同一个 `ServiceDaemon`** 上再开一个 browse（mdns-sd 原生支持
register + browse 并存），后台线程消费事件：

- **去重**：mdns-sd 每次缓存刷新都会重新 resolve，对端每 10 s 还会重播一次宣告。
  按全名记已见表，只有端口或地址变了才重发 `peer`，否则 UI 每 10 s 抖一次。
  （`peer_is_new_or_changed()`，纯函数，单测覆盖。）
- **自过滤**：实例名形如 `<base>-<pid>`，前缀匹配后直接丢弃，不发 `peer`/`lost`。
- **生命周期**：browse 句柄挂在宣告的 teardown 上，地址变化时与宣告一起停掉重建；
  `BrowseHandle` 的 `Drop` 也会停线程，避免每次地址变化漏一个线程。
- **地址选择**：`peer.address` 优先取链路本地 IPv6（AWDL 上唯一可达的地址），
  可以直接拼成 `send --address`。

### 30.4 上机实测（40 s，RAW TX 未涉及）

```
--- OUT ---
{"event":"listening","name":"TestPhone","port":8771}
{"event":"ready","name":"TestPhone","port":8771}
{"duration":0,"event":"browsing"}
--- COUNTS ---
peer=0 lost=0 browsing=1
```

`peer=0` 是**正确**结果，不是失败：日志里明确有

```
DEBUG luftlift_rs::mdns: AirDrop peer resolved
      name=luftlift-30840._airdrop._tcp.local. addresses=[fe80::eaf7:91ff:fe1f:f8fe] port=8771
```

也就是 browse 真的收到了自己的宣告（说明 awdl0 上的 mDNS 收包通路成立），
只是被自过滤吞掉了。要证明"真对端会出 `peer`"，需要一个会宣告的对端 ——
苹果设备只在分享面板打开时宣告，不可控。

因此加了 `native/airdrop/crates/luftlift-rs/examples/fake_peer.rs`：在同一个
`awdl0` 上用**不同实例名**宣告一个 `_airdrop._tcp`（不提供任何服务），作为可
复现的合成对端。它靠的正是 30.1 验证过的 SO_REUSEPORT。

```
cargo build --release --target aarch64-linux-android --example fake_peer
adb push target/aarch64-linux-android/release/examples/fake_peer /data/local/tmp/
# 设备上：接收端 --browse 在跑，另开一个
/data/local/tmp/fake_peer awdl0 fakemac 120
```

预期事件序列：`browsing` → `peer`(fakemac-*) → （fake_peer 退出时）`lost`。

**已先在主机侧跑通**（`-i en0`，同一台 Mac 上两个进程，真实组播）：

```
$ luftlift -i en0 receive --browse        # 接收端
$ fake_peer en0 fakemac 10                # 合成对端

{"event":"listening","name":"HostTest","port":8771}
{"event":"ready","name":"HostTest","port":8771}
{"duration":0,"event":"browsing"}
{"address":"fe80::106e:4bc9:4e3e:4ea5","event":"peer","name":"fakemac-18095._airdrop._tcp.local.","port":8771}
{"event":"lost","name":"fakemac-18095._airdrop._tcp.local."}
```

同一份日志证明自过滤也生效：两个实例都被解析到了，只有外部的那个发事件。

```
AirDrop peer resolved name=luftlift-18059._airdrop._tcp.local.  ...   <- 自己的，被前缀过滤
AirDrop peer resolved name=fakemac-18095._airdrop._tcp.local.  ...    <- 出了 peer 事件
AirDrop peer went away   name=fakemac-18095._airdrop._tcp.local.      <- 出了 lost 事件
```

这段已经固化成集成测试 `crates/luftlift-rs/tests/browse_discovery.rs`，默认跳过，
带接口名才跑（会真的发组播，所以要显式同意）：

```
LUFTLIFT_TEST_IFACE=en0 cargo test --test browse_discovery -- --nocapture
# 覆盖：Found(对端) + Found(自己) + 重播不重复 + goodbye 触发 Lost
```

顺手补了 `netutil::get_ipv6_for_interface()` 的 macOS 分支（原来只有 Android 走
`getifaddrs`，其他平台读 `/proc/net/if_inet6`，在 macOS 上恒为 None，于是主机侧
`receive` 静默不宣告、不发 `browsing`，看起来和"发现坏了"一模一样）。Android 走
的还是同一段 `getifaddrs` 代码，行为不变。

**手机上这一轮没跑完**：推送测试脚本时手机从 USB 掉线（`/dev/cu.usbmodem*` 全部
消失，不是重启），装包和机上复测待设备接回。

### 30.5 顺带修掉的两个发送侧坑

- **`send` 不再自己 browse**：地址已经在 `peer` 事件里，直接
  `--address [fe80::…]:8771` 直连。少一次 mDNS、少几秒等待，也不会和常驻接收端
  抢同一个组播 socket。IPv6 必须加方括号，否则后端 `rsplit_once(':')` 会从地址
  中间切开。
- **`SenderConfig` 的 SenderID 会撞**：`random_sender_id()` 原来只用时间做种子，
  同一 tick 里构造两次得到同一个 ID（单测 `sender_config_new_generates_non_empty_sender_id`
  因此偶发失败）。加了一个进程内原子计数器，顺带让多文件发送的每个进程都有独立 ID。

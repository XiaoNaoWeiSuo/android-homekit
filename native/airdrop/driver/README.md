# 小米 13 内置 WLAN 驱动实验

**2026-10-27 续测**：4轮有界20秒UDP，新增4次正常STOP/自动原厂恢复，末次boot_count78、网关3/3。首次双向往返为手机5/40、Mac3/39；另一轮无共同52信道又0/40，尚未稳定。用户态已修首次选主采用时钟的顺序及统计，未解决固件line#1413；短测无故障不能当长时稳定证明。详见HANDOFF最新条目和 `build/airdrop/ip-probe/continued-20261027.json`。

**2026-10-27 新运行期故障（优先于历史安全结论）**：0056独立UDP测试约48秒发生固件RDDM/SSR，SFR `0x1366428:0x1c34a98, line#1413`。无整机重启，自动恢复原厂和网关3/3，boot_count78。该签名旧mld-owner日志也有，根因未定；未重复试跑。抓包确认Mac宣告无52信道，一帧待发送单播堵住整个TAP；已实现有界分对端队列并离线验证，未无线验证。pending_rx真机释放2包，但探针未绑定导致端口不可达，现探针增加双端ready闸门。详见HANDOFF顶部和 `build/airdrop/ip-probe/lease-20261027/`。继续基础驱动/帧/IP层，稳定验收仍0/4。

**基础验证更新（2026-10-26）**：用户允许逐层真机测试。0056正常monitor STOP累计6次成功，包含同一次加载下连续重建3次，旧STOP/DELETE告警未再出现；固件恢复/消息队列失败仍未验收。加载器已处理scan-only接口和wificond death延迟触发框架禁用的顺序，退出恢复用户扫描设置。独立UDP仅有零星双向请求到达、无成功echo往返，故底层通信仍不稳定，继续停留在帧/IP层。最新数据见HANDOFF顶部与 `build/airdrop/ip-probe/progress.json`。

**最新：0055/0056 离线生命周期候选（2026-10-26）**。用户要求先完善底层，暂停无线试跑。STOP前置到SME逻辑删除之前、补monitor响应/失败/超时继续删除链，DP timer起停/重挂用锁同步；完整编译、512导入CRC、实际C回调离线故障测试通过，尚未部署。可复现合并包见 [stack-0056/README.md](stack-0056/README.md)，含固定基线和44文件校验。完整SSR/调度队列关闭竞态以及通信稳定性仍未完成，不能作为发布版。

**2026-10-26 更新**：0054已从局域网取回并做两轮150秒有界无线测试；STOP/DELETE仍告警，无重启，最终原厂+联网3/3恢复。另有一轮因前置联网失败中止。mDNS hop-limit修复已由真机确认；删除模板旧配对服务宣告的修复已装APK但未完成无线对照。详见 `docs/HANDOFF.md` 最新条目，不能据下方历史检查点判定已可用。

**2026-10-24 当前检查点（优先于下方历史记录）**：构建机通过 `ssh lin@100.121.129.99` 可达，目录 `/home/lin/workspace/xiaomi13-airdrop`。0054 已在 0053 树完成全模块编译、512/512 导入 CRC 核对和真实源码边界回归；候选 `80680eae…` 尚未部署。STOP/DELETE 问题仍未修复：monitor 发 STOP 后立即推进 DOWN_COMPLETE，且缺 STOP 响应回调。真实 Apple 无线 TLS/双向文件、无 AP、长期共存仍待完成。最新证据和限制见 `docs/HANDOFF.md`、`docs/AIRDROP_RESEARCH.md` 及 `build/airdrop/driver/candidate-0054/`。

这是驱动研究材料和可重复的编译入口。前三个实验 `.ko` 启用普通 Wi-Fi 时重启，均已隔离。实时 panic 堆栈与原厂模块反汇编确认了 CNSS SOC-info 结构体越界。0005 补齐该边界后，修复版于 2026-10-16 首次通过短时普通 Wi-Fi 加载、关联和网关收发测试，并自动恢复原厂。**没有可发布的驱动安装包，也未验证无线互传或长期稳定性**。常驻服务仍只待机。

最新检查点（2026-10-16 19:13，0027已实测；0028已拒绝）：**保持普通Wi-Fi关联时，AF_PACKET用户空间首次收到15个真实AWDL Action帧，含本机Mac `96:12:4f:a1:71:e6`，覆盖6/149信道。** DMA描述符Action15、重组后Action/AWDL15、用户空间15；0027将共享mission RX环monitor解析前缀从400修为实测272，解决此前帧头错位。普通联网前3/3、窗口期间43/48、最高3072.773ms、之后3/3，说明仍有明显共存性能问题。删除monitor还触发STOP/DELETE竞态WARN，0028尝试修复引用释放顺序，但第二轮在删除超时后由 `dp_mon_vdev_timer+0x3c` 触发崩溃并重启，已拒绝且移入 `driver/rejected/`；不是可常驻发布版。整帧TLV检查已观测14/17完整，3帧有尾部截断，仍待判断。当前已恢复原厂三模块、无实验接口/锁/观察器，未重启。**RAW IPv6 TX、真实AirDrop互传及完整常驻共存仍未完成**。

2026-10-16 17:31 历史检查点：0018 上层未看到action/AWDL，故当时排查固件配置。这一结果现由0026的DMA证据补充，不能再用作“mission固件未收到AWDL”的结论。各轮原始结果仍保留在下文及 `build/airdrop/driver/`。

## 2026-10-19 接手检查点：0031 崩溃根因是关机顺序，不是并发竞态

上一轮把 0028–0031 当作递进修复，实际是回归。静态审计（`build/airdrop/driver/mission-rx-research/dbr-teardown-chain-audit.json`）给出 0031 Oops 的完整链路：

```text
cnss_recovery_work_handler -> ... -> hdd_wlan_shutdown -> hdd_wlan_stop_modules
  -> hdd_check_for_leaks -> wlan_objmgr_vdev_release_ref -> wlan_objmgr_vdev_obj_free
  -> wlan_objmgr_pdev_vdev_detach -> wlan_objmgr_pdev_release_ref
  -> target_if_direct_buf_rx_pdev_destroy_handler -> target_if_deinit_dbr_ring (+0x1f4)
```

`hdd_wlan_stop_modules()` 在 `wlan_hdd_main.c:14922` 先调用 `hdd_hif_close()`，它清空 HIF 上下文并释放 HAL SRNG 表；到 `:14956` 才调用 `hdd_check_for_leaks()` 强制释放遗留 vdev 引用。0028 把 monitor 的 WMA detach 延后，删除从 WARN 变成 20 秒超时，monitor vdev 因此遗留到关机阶段，才在 HAL 已销毁后走到 DBR 的 `hal_srng_cleanup`。**所以 0028–0032 不能作为累积基线，0027（`de9cebdb…`）仍是最好候选。**

新增 0033：HIF 上下文已为空时跳过 `hal_srng_cleanup`，仍释放主机侧环内存。它不修 STOP/DELETE 警告本身，只保证关机不再因遗留 monitor vdev 崩溃。该补丁已在固定源码上 `git apply --check` 通过，编译验证见下。

**0033 候选已构建并上机验证**：`build/airdrop/driver/candidate-0033/kiwi_v2.ko`，SHA-256 `8ec1e5eb4a76e99dc0bdcb26ff179580c3a9f4e7461da106617fbc87dd423352`，24 077 224 字节（从 522 MB 链接产物 strip 调试信息）。补丁顺序 0001/0003–0027 + 0033，全部干净应用；pinned clang r450784e，`LTO_CLANG_FULL`/`CFI_CLANG`/`DEBUG_INFO_BTF_MODULES`，全树 0 warning。provider symvers 7891 项导出，候选 506 项导入全部匹配、0 冲突；布局审计中 `struct module`、`net_device` 等选中类型字段位置一致，仅 `wiphy.android_backport_reserved1` 匿名联合体这一已知差异。**模块 BTF 因缺少 vmlinux 未生成**，这是本次构建的已知缺口；CFI 哈希未独立比对。反汇编确认 `target_if_deinit_dbr_ring` 在 HAL 清理前有 NULL 判断分支。

该候选的 `scmversion` 是 `g8355f91d4cf8-dirty`，与原环境的 `g939d76a86982` 不同——这是重建环境的 git 状态产物（本地 base 提交 + 补丁未提交），不影响功能。身份判定以模块 sha256 为准，`volatile-stack-test.sh` 的候选白名单已加入该哈希。

运行期契约同时确定：**monitor vdev 在驱动加载后创建一次，运行期不删除**；删除只发生在卸载/重启。这样正常路径不再经过有问题的删除序列。

## 2026-10-19 上机结果：monitor 生命周期安全，S1 关闭

两轮 volatile 实测都通过，设备未重启（uptime 从 251828 连续到 252102 秒），原厂三模块 sha256 完整恢复。

**第一轮（仅加载/卸载，RAW TX 关）**：`PREFLIGHT_OK` → 实验模块加载 → `con_mode=0` 下普通 Wi-Fi 关联 5260MHz/-45dBm/192.7MBit/s → 网关 ping 3/3 → 卸载 → `restored`。内核日志无 `BUG:`/`Oops`/`WARNING:`。

**第二轮（monitor 全生命周期）**：`volatile-stack-test.sh run-monitor`，EXIT=0。关键序列：

```text
PREFLIGHT_OK
EXPERIMENTAL_STACK_LOADED_BOTH_TX_GATES_DISABLED
WIFICOND_RESTARTED_AFTER_CFG80211_RELOAD
NORMAL_WIFI_GATEWAY_ROUNDTRIP_OBSERVED        (3/3, -40dBm)
MONITOR_CREATED_STA_ASSOCIATION_RETAINED
INVALID_CAPTURE_DURATIONS_REJECTED            (6 个畸形时长全部被拒)
CAPTURE_SWEEP_EXIT=0                          (9 个 direct capture slot，全 result=0)
WITNESS_SUMMARY packets=940 action_frames=12 awdl_actions=12
MONITOR_RECREATE_AND_SECOND_TEARDOWN_COMPLETED
STA_MONITOR_EXPERIMENT_FINISHED_DATA_TX_DISABLED
restored
```

这是本项目第一次在**建 monitor → 收包 → 删 → 重建 → 再删**的完整往返后正常卸载。上一轮 0028–0031 正是在这条路径上崩溃重启的。窗口内 ping 43/48（12% 丢包、最高 1836ms），窗口后 3/3，共存性能问题依旧，留给 S5。

**关键负面结果：0033 的 guard 没有被执行。** 日志中 `HAL already closed; skip srng cleanup` 出现 0 次。原因是两次 monitor 删除都按时完成，没有 vdev 泄漏到 `hdd_check_for_leaks()`，每次 PDEV destroy 都发生在 HIF 上下文仍然有效时。卸载时的 DBR 日志是：

```text
target_if_deinit_dbr_ring: module(1) srng(0) was not registered
target_if_deinit_dbr_ring: module(1) srng(1) was not registered
```

`DBR_MODULE_CFR = 1`（`target_if_direct_buf_rx_api.h:32`），即这两行来自 **CFR/频谱测量** 路径，与本项目的 monitor RX 无关；module 0（Spectral）的环是注册的，按正常顺序被 `hal_srng_cleanup` 清理，没有报错。核对补丁系列：只有 0032/0033 触及 `direct_buf_rx`/`dbr_`，0001–0027 从不注册 DBR 环。

**由此修正上一轮的判断**：0028–0032 追的是 CFR/DBR 的并发生命周期，而本项目的 monitor 数据面根本不走 DBR。0031 的崩溃是那一系列自己引入的路径（0032 的 PDEV 级 gate 也印证它针对 Spectral/CFR）。0033 的 guard 因此在 0027 基线上是**防御性死代码**——正确、无害，但当前配置进不去，只有在删除超时导致 vdev 泄漏时才会生效。

结论：S1 的目标（monitor 生命周期不崩、原厂可恢复、普通 Wi-Fi 不受影响）已经达成，**不需要再为 DBR 花时间**。真正卡住 AirDrop 的是 S2：monitor 的 RAW TX 从未在这颗芯片上跑通过。

设备基线（2026-10-19 15:46）：uptime 2.8 天、`con_mode=0`、原厂 `kiwi_v2`、Wi-Fi 关联 5260MHz、常驻 AirDrop 服务前台。未加载任何实验模块。测试结束后（17:56）状态一致：uptime 2.9 天未重启、`con_mode=0`、原厂模块 sha256 `75a937c1…`、`/sys/module/kiwi_v2/parameters/` 无任何 `marionette_*`、Wi-Fi 已启用、`wlan0` 保有 10.12.1.42。



构建环境第二次丢失（Docker 命名卷 `marionette-driver-workdata` 随旧 colima profile 消失）。已在 `ohos-arm` VM 内重建：pinned 源码用 codeload 取回，clang r450784e 经 Rosetta 运行，宿主工具用原生 gcc，`gki_defconfig + fuxi_GKI.config` 已合并，`CONFIG_LTO_CLANG_FULL/CFI_CLANG/DEBUG_INFO_BTF_MODULES` 与记录一致。GKI `vmlinux.symvers` 已重新下载，配合手机导出的原厂模块恢复出 7876 项 provider 导出。**注意 `hwid.ko` 只存在于 vendor_boot，未导出，链接时需另行处理。**

## 固定来源与环境

版本、模块哈希、Clang 下载地址见 `sources.json`。WLAN 和 vendor kernel 使用 MiCode `fuxi-t-oss`。公开 vendor kernel 版本为 5.15.41，设备正在运行的 GKI 为 5.15.74；原厂 WLAN 自身的 vermagic 也是 5.15.41。**不能仅比较 uname 或修改 vermagic 来认定可加载**；需要逐项核对导入符号 CRC、配置、CFI、依赖模块和 Android GKI/vendor ABI。

当前工作目录：Docker 容器 `marionette-driver-persistent`（`linux/amd64`，Colima），`/work/kernel`、`/work/kernel-out`、`/work/wlan`、`/work/clang`。`/work` 使用 Docker 命名卷 `marionette-driver-workdata`。2026-10-16 恢复工作时，旧 `marionette-driver-work` 容器绑定的 VM `/tmp` 目录已丢失，不能把上次尚未导出的编译视作成功；已从固定来源重新导入。使用 Linux 内的源码副本，避免 macOS 默认文件系统把内核中大小写不同的文件合并；从本机 git **archive** 导入，不能直接复制 macOS checkout。Docker 的 `/tmp` bind 指向 VM 的临时目录，不是本机同名目录。

MiCode 缺少 `drivers/misc/hwid`，已从固定 LineageOS 提交补充四个文件，见来源清单。其头文件缺少 WLAN 用到的 `HARDWARE_PROJECT_M2`。从固定哈希的原厂模块恢复值为 **1**：

```text
ucfg_policy_mgr_psoc_open:
0x31f32c  bl get_hw_version_platform  (CALL26 relocation)
0x31f330  mov w21, w0
...
0x31f4c4  cmp w21, #1
0x31f4d4  csel w9, w9, wzr, eq  (只有项目 1 保留 sbs_enable)
```

对应源码 `policy_mgr_init_cfg` 的 `hw_platform_ver != HARDWARE_PROJECT_M2` 分支。设备 `/sys/module/hwid/parameters/project=2`。构建入口用 `-DHARDWARE_PROJECT_M2=1` 补齐，不能将这个值推广到别的源码或设备。

准备内核输出（Linux，已经安装 build-essential、bc、bison、flex、libssl-dev、libelf-dev、python3、cpio、dwarves 等依赖）：

```sh
export PATH=/work/clang/bin:$PATH
cd /work/kernel
make LLVM=1 LLVM_IAS=1 ARCH=arm64 O=/work/kernel-out gki_defconfig
scripts/kconfig/merge_config.sh -m -O /work/kernel-out \
  /work/kernel-out/.config arch/arm64/configs/vendor/fuxi_GKI.config
make LLVM=1 LLVM_IAS=1 ARCH=arm64 O=/work/kernel-out olddefconfig modules_prepare -j4
bash /work/driver/build-baseline.sh /work/kernel /work/kernel-out /work/wlan /work/clang
```

`build-baseline.sh` 位于此目录，当前目录整体复制到容器 `/work/driver/`。保留 CFI、LTO 和警告检查；`-D__ANDROID_COMMON_KERNEL__` 选择 WLAN 自带的 Android 5.15 EHT/MLO API 兼容分支，不是关闭类型检查。

2026-10-14：完整原版 WLAN 构建成功，682 个编译单元，产物 `/work/wlan/qcacld-3.0/kiwi_v2.o`。此处 Kbuild 生成的是含 LLVM bitcode 的 thin archive（约 480 KiB 的引用目录，实际对象位于源码旁），不是最终 ELF `.ko`，不能单独拷出后视为可加载驱动。日志已导出到本机 `/tmp/xiaomi13-airdrop-research/wlan-baseline.log`。

随后在相同工作目录应用 0001 / 0003 并重新构建，输出日志 `/work/wlan-patched.log`。该重编会覆盖目录内的对象，**原版 thin archive 的引用不再是独立保存的基线产物**。原版通过记录应以导出的日志和固定源码复现为准，不能把混合了重编对象的 thin archive 用作 ABI 参照。

## 补丁顺序与验证边界

| 补丁 | 作用 | 当前验证 |
| --- | --- | --- |
| 0001 | 返回真实的 P2P 管理帧提交错误，修复原函数始终返回 0 的诊断问题 | 可应用；`wlan_hdd_p2p.o` 编译通过 |
| 0002 | cfg80211 默认关闭的 `CONFIG_CFG80211_MARIONETTE_AWDL`，启用构建仍需 root 打开默认 N 的 `marionette_awdl_mgmt_tx`；仅允许 AWDL BSSID、特定 OUI/版本和 PSF/MIF subtype 的 STA 管理帧避开“必须发给 AP”检查 | 模块链接通过；189 项导入、135 项自然生成导出 CRC 与原厂对应；运行待验证 |
| 0003 | monitor 的 `ndo_start_xmit` 原型；默认关闭且仅 root 可写的模块参数 `marionette_awdl_raw_tx`；只接受 filin 已知 radiotap 格式及 AWDL IPv6 数据帧，通过现有 DP exception 接口提交 RAW 帧 | 可应用；HDD main / tx_rx 编译通过；固件行为和 OTA 均未验证 |
| 0004 | 使用独立的 root 只读原子计数器区分 host 提交与拒绝；原型更新的 dev->stats 并非驱动 ndo_get_stats 使用的 DP 统计 | 编译与链接通过；组合候选的普通 Wi-Fi 运行测试失败，尚未启用 RAW TX |
| 0005 | 补齐固定原厂 CNSS 和 WLAN 之间缺少的 129 字节固件标识字段；同时检查 CNSS / PLD 结构体大小及字段偏移 | 编译元数据和反汇编对应；组合候选通过短时普通 Wi-Fi 关联与网关往返；RAW TX 仍关闭 |
| 0006 | 默认关闭、仅加载时启用的 STA AWDL 前缀注册，补齐固定 LIM 匹配列表，关闭时配对注销 | 真机注册请求入队；普通联网和管理 TX 仍通过；STA 扫频接收为 0 |
| 0007 | 启用 0006 时，使用 root 只读原子计数器观察 WMI / LIM / HDD 的全部管理帧与 AWDL 到达数 | 真机 WMI 普通管理帧到达、AWDL=0；LIM/HDD AWDL=0；普通联网通过 |
| 0008 | 默认关闭的 STA 社交信道短时 ROC 扫描接收过滤选项 | 真机请求日志确认；AWDL 到达 WMI 仍为 0，普通联网及管理 TX 通过 |
| 0009 | 默认关闭的保持 STA 关联的 monitor RX 实验；保留并发检查，限制 monitor 信道设置必须匹配 STA 频率/带宽 | 编译通过；首次加载在普通 Wi-Fi 的 PE 连续内存分配阶段失败，未到 monitor 创建；原因见 0010 |
| 0010 | CPU 专用 PE 会话数组改为已有 qdf_mem_valloc/vfree，补齐失败/正常两处释放 | 普通联网及卸载通过；最终机器码调用 vzalloc/vfree；monitor 创建随后被 STA 数量检查拒绝 |
| 0011 | 仅 0009 实验开启时，按完整 MLD 所属关系统计一个逻辑 STA；同一非空 MLD、有效单播 MLD 地址、完整成员数量才合并 | 修订版 monitor 创建/启用通过、STA 关联仍在；普通数据实际不通，不能视为共存通过 |
| 0012 | monitor 打开时通过已有驱动函数按 STA 当前频率/带宽完成信道初始化，不改 cfg80211 限制 | 初始化 ret=0；monitor 收到 27050 帧，但普通 Wi-Fi ping 仍 1/48，已恢复原厂 |
| 0013 | 仅实验开启时，将实际 monitor 1.0 的目的环/状态环配置为 other-BSS 管理/数据捕获，关闭普通 filter-pass 捕获 | 真机仍 1/48 网关往返，已恢复；分类调整不足以解决共享环格式问题 |
| 0014 | 在共享环 monitor 更新/复位后调用已有架构正常 RX 配置，恢复普通数据环的 TLV 格式与偏移，保留独立状态环 | monitor 启用期间网关 48/48，删除后 3/3；monitor 完整帧输出变为 0，尚非收发共存 |
| 0015 | 一次性 1..200ms 捕获时隙；root 写入后同步切换 monitor 过滤、等待并恢复正常 RX；vdev guard 覆盖操作，无后台任务 | 内部路径修订版六个 40/80/160ms 时隙收到 316 个 raw 帧，网关 47/48，随后 3/3，未发现 AWDL；原厂恢复 |
| 0016 | 仅实验开启的关联 STA，允许 6/44/149、<=200ms 的正常 ROC，保留其他策略与监管检查 | 9 次就绪事件各触发 80ms 捕获/恢复，网关 48/48；raw 42 帧、AWDL=0，继续核对 radiotap 实际频率 |
| 0017 | 仅捕获时隙内恢复完整 FP+MO 过滤分类，其他时间继续恢复普通 RX；不依赖“other-BSS”分类 | raw 858 帧、action/AWDL=0；网关 48/48，但最大 RTT 2116ms；现场原厂对照仍有真实 AWDL |
| 0018 | 一次性捕获支持受限的 `2437|5220|5745:1..200` 输入；通过已有 monitor 信道 API 切换，始终请求恢复 STA home/width 和正常 RX | 九次切换与恢复成功，raw 924 帧、AWDL=0；网关 42/48，结束 3/3；延迟/丢包未达目标，原厂恢复 |
| 0019 | 短窗口内额外订阅 HTT Monitor_Direct 分类的 Action/No-ACK Action，保留正常 RX 恢复 | MD mask=0x6000 日志确认；920 raw、0 action/AWDL；网关42/48，原厂恢复 |
| 0020 | 默认关闭的标准 `WMI_PDEV_SET_RX_FILTER_PROMISCUOUS_CMDID`，短窗口内配对启用/关闭，使用协议固定 TLV 和 pdev 映射 | 九组主机队列返回成功，未证明固件执行；685 raw、0 action/AWDL，网关41/48，原厂恢复 |
| 0021 | 默认关闭的 monitor vdev RAW RX 配置，通过现有 MLME/DP/WMI 标准接口，只作用于 monitor | vdev3/type0 请求返回成功；703 raw、0 action/AWDL，网关42/48；WMI promiscuous 关闭，原厂恢复 |
| 0022 | 默认关闭的 RAW 初始 firmware RX 资源，同时为所有 STA vdev 明确设置 Ethernet RX，保持 mission 模式 | 关联成功但数据计数0、网关前置检查失败，未创建monitor；已恢复并隔离此运行组合 |
| 0023 | monitor DMA帧类型及重组后Action/AWDL计数 | 0024后实测；0027 Action输入输出15/15，与用户空间一致 |
| 0024 | 仅DP_PDEV_TYPE主机上下文改为虚拟分配，匹配池归还/释放；早期失败路径注销VA minidump再走同一context free接口 | 普通联网/正常卸载已通过；首次0023运行在此处order5分配失败，修复后444个DMA描述符可观察 |
| 0025 | 比较有效DMA帧控制与HAL报头，扫描有界假定TLV区域内的精确AWDL签名，记录布局/标签 | 首次运行被HAL分配失败阻断；0026后实测找到10个真实AWDL、实际帧272/HAL指针288/假定monitor前缀400 |
| 0026 | 仅HAL主机上下文改为虚拟分配，匹配失败及正常两处释放，保留独立coherent DMA shadow分配 | 普通联网及卸载通过，未重启；完成0025报头观察，原厂恢复 |
| 0027 | 仅匹配本机实测mission/shared-ring布局时，将monitor接收解析前缀设为实际normal RX前缀，修正128字节误跳过 | 实测AF_PACKET收到15个AWDL，含本机Mac；普通Wi-Fi仍关联，有丢包/尾延迟；monitor删除WARN待0028 |

| 0028 | gated mission monitor延迟WMA detach至STOP回应，并对SME逻辑删除对象使用no-state回退 | **拒绝**：17个AWDL仍到达，但删除等待超时后 `dp_mon_vdev_timer` 访问失效对象导致重启；不再加载 |
| 0032 | PDEV 级 lifecycle gate：事件计数、destroy 门、逐模块 unregister 门，超时不释放 | **草稿，未编译未上机**。静态审计显示它针对的并发竞态不是 0031 的实际根因，且会阻塞 PDEV destroy；被 0033 取代，不再使用 |
| 0033 | HIF 上下文已关闭时跳过 `hal_srng_cleanup`，只释放主机侧环内存 | **已上机通过（两轮）**：加载/卸载与 monitor 全生命周期均 EXIT=0，无 Oops、未重启、原厂完整恢复。506/506 导入匹配。注意 guard 未被触发（无 vdev 泄漏），属防御性死代码；DBR 环在本配置下 module 1=CFR 未注册，与本项目 monitor RX 无关 |

0001、0003 至 0031 依次应用到 WLAN 根目录，0002 应用到 vendor kernel 根目录。完整 WLAN 补丁顺序按固定提交的新临时目录逐一 check/apply；最新修改需在加载前重查。各负面运行结果有独立记录；0002 的 cfg80211 模块现已进行 RAM 临时加载测试，结束后恢复原厂。

0002 不改变固件服务能力，不跳过监管检查，也不放开任意管理帧。0003 **不是正常联网共存方案**：只允许全局 monitor 模式，不支持管理帧、完整 radiotap 速率/重试语义、ACK 完成、分片或 QoS/A-MSDU。它是验证既有 DP RAW exception 提交能否工作的起点。计数只是 host 提交数，不能证明固件实际发射。不能直接把普通 STA `hdd_hard_start_xmit` 挂到 monitor ops。

## 已证实的共存障碍

1. `wlan_mon_drv_ops` 无发送回调；与原厂模块一致。
2. `monitor_mode_concurrency=1` 的含义是 **STA scan + monitor**。`wlan_hdd_add_monitor_check` 会主动断开已连接 STA；不是联网 + monitor。
3. `WLAN_FEATURE_PKT_CAPTURE` 存在绑定 STA 的监听路径，但当前默认配置仅对部分其他平台启用，不足以证明 kiwi_v2 固件支持全量第三方 AWDL 数据接收。
4. 原厂正常 Wi-Fi 上的 AWDL Action TX 在 `cfg80211_mlme_mgmt_tx` 被 `-ENOTCONN` 拒绝：类别 127 的帧要求 BSSID/目的地址匹配关联 AP，AWDL 使用自己的 BSSID。0002 处理这道门槛后，已在保持 STA 联网时取得一次 host 提交 cookie；仍需观察完成事件和外部接收。
5. 管理帧发送与数据帧收发是不同路径；即使 0002 可用，也还没有 IPv6 文件数据通道。当前路由器信道为 52（DFS），不能把 AWDL 固定信道 44 的发射测试当成同信道共存验证。
6. `dp_vdev_attach_wifi3` 的 monitor 分支在调用正常 `dp_tx_vdev_attach` 之前返回，删除路径也跳过普通 TX detach。0003 只接入发送入口，还没有补齐或证明这一 TX 生命周期及固件支持；在解决它之前保持 RAW TX 关闭，不能仅凭 exception 回调存在就开始发包。

## 下一步验收

短时普通联网、管理帧host提交/完成和STA关联下的monitor真实AWDL接收已有分阶段证据，仍必须保留电脑端实时日志。下一步先验证完整TLV、修复monitor STOP/DELETE竞态，再验证管理帧外部接收与原始IPv6 TX生命周期。随后接入双向发现/数据及低延迟信道调度，把常驻服务接到验证过的无线端口。最后检验锁屏、重启、休眠、双向文件、网络不断线及功耗。

STA关联下真实第三方AWDL RX已见0027。仍缺RAW IPv6 TX支持、双向互传、低延迟调度及长期运行稳定性证据。不能把编译或 CRC 比对通过视作驱动适配完成。

`abi-audit.py candidate.ko stock.ko [other-stock.ko ...]` 可比较实际模块中 `__versions` 的导入符号 CRC，未知/冲突/不匹配均报告失败。原厂 WLAN 库存包含 502 个导入版本记录；自身比对通过仅用于验证工具，**不是新驱动 ABI 验证通过**。工具有 5 个回归测试，包括缺少参考、真实提供方表、CRC 不匹配/冲突和截断文件。即使所有导入 CRC 相符，输出 `loadability_verified` 也保持 false，仍需额外检查结构布局、CFI 和签名。

## 模块链接与真实符号来源

已取得 [GKI 构建 9500309 的官方产物](https://ci.android.com/builds/submitted/9500309/kernel_aarch64/latest/)，manifest 的 kernel/common 提交 `8945ccdb22035c2c086018799e7c61c29319ad31` 与手机 uname 一致。`vmlinux.symvers` 覆盖原厂 WLAN 的 396 个导入，全部相符。其余来自手机实际 cfg80211、CNSS、hwid 等模块的绝对 `__crc_*` 导出；hwid 从当前 slot A 的 vendor_boot **只读备份**中提取，没有改写分区。完整来源及哈希见 `sources.json` 和本机 `/tmp/xiaomi13-airdrop-research/stock-provider-provenance.json`。

`stock-symvers.py` 只读取真实提供方的导出（含 GPL 类别与 namespace），不会把导入记录冒充导出。共恢复 7677 项提供方导出，原厂 WLAN 的 502 个导入全部对应；初版实验模块的 482 个导入也全部对应。它们是 modpost 的输入，**不能证明用于编译的头文件和运行内核完全相同**。

```sh
python3 stock-symvers.py --gki /path/to/gki/vmlinux.symvers \
  --output /path/to/stock-provider.symvers --provenance /path/to/provenance.json \
  /path/to/cfg80211-stock.ko /path/to/stock-deps/*.ko
DRIVER_TARGET=modules DRIVER_PROVIDER_SYMVERS=/work/stock-provider.symvers \
  bash /work/driver/build-baseline.sh /work/kernel /work/kernel-out /work/wlan /work/clang
python3 abi-audit.py /path/to/candidate.ko --provider-symvers /path/to/stock-provider.symvers
```

链接不使用强制版本参数、关闭 CFI 或猜测 CRC。构建根目录的 Module.symvers 为空会产生提示，但实际完整提供方表通过 KBUILD_EXTRA_SYMBOLS 传入；初版 modpost 没有未解析导入。产物 `.ko` 保留 modversions / CFI；去掉调试信息后约 23 MiB。

## 类型布局补充验证

`layout-audit.py` 对比候选对象的 DWARF 和从手机 `/sys/kernel/btf/vmlinux` 读取的 BTF。初版发现：虽然 module_layout CRC 及 `sizeof(struct module)` 相同，模块结构体的部分字段仍差了 16 字节，因为未安装 pahole 时内核准备没有启用 `CONFIG_DEBUG_INFO_BTF_MODULES`。安装 dwarves 并重新运行 olddefconfig / modules_prepare 后，该差异消失。构建脚本现已拒绝缺少该选项的模块链接。

抽查的九个结构体（net_device、net_device_ops、sk_buff、wireless_dev、wiphy、device、timer_list、module、work_struct）的**直接同名编译字段**位置与运行内核对应。wiphy 的 android_backport_reserved1 位于相同偏移的匿名联合体中；工具单独报告该差异，并核对匿名类型中的原字段位置。仍未完整验证所有嵌套类型、匿名编译字段、其他结构和所有函数类型，因此 loadability_verified 保持 false。位域解析同时处理 DWARF4 的高位起算格式和 DWARF5 的绝对位偏移，不能把字节偏移直接当作位域偏移。

```sh
python3 layout-audit.py --dwarfdump /work/clang/bin/llvm-dwarfdump \
  --btf /work/running-vmlinux.btf --object /work/wlan/qcacld-3.0/kiwi_v2.lto.o \
  --output /work/layout-report.json
```

## 临时加载测试的恢复路径

`volatile-test.sh check ...` 核对精确设备、当前内核、原厂模块哈希、候选哈希、USB、空闲模块引用和原厂 Wi-Fi 网关往返；`run` 才执行一次短时卸载/加载。测试保持 RAW TX 参数关闭，观察普通 Wi-Fi 关联、IPv4 路由恢复及绑定 wlan0 的网关 ping，防止蜂窝网络或关联状态制造成功假象。独立 setsid 的 `volatile-rollback.sh` 在结束、父进程退出或 90 秒期限到达后恢复原厂模块及原 Wi-Fi 开关。没有修改 vendor 模块文件、boot/vendor_boot、init 或启动挂载。

这仍是实际内核代码测试，自动回退不能处理所有内核崩溃情形。原厂驱动的卸载/重载对照已通过，完成联网及自动恢复；实验模块则未通过。状态和备份放在手机 `/data/local/tmp/marionette-driver-test-*`，只能在确认没有未完成恢复后开展下一次实验。

## 首次运行失败及后续诊断（2026-10-14）

- 已隔离的候选 SHA-256：`3f9155a7df922b0bc7e31c59fc4f1e940b74d8552a0ae746fa7c109996e2714f`。482 个导入 CRC 对应真实提供方，CFI 保留，但**运行失败**。内存加载后 RAW TX=N、con_mode=0；请求启用普通 Wi-Fi 后 USB 断开，随后确认设备 uptime 重新计时。没有修改启动分区或 vendor 模块文件。重启后 scmversion 恢复 `g939d76a86982`、Wi-Fi 重新关联，HomeKit 与 AirDrop 待机前台服务均自动恢复。
- pstore 为空，DropBox 没有 SYSTEM_LAST_KMSG，不能声称已找到重启堆栈。该哈希记录在 `rejected-candidates.json`，测试脚本拒绝再次加载；电脑端产物名已改为 `build/airdrop/driver/quarantined-3f9155a7.ko`。
- 已通过原厂驱动热重载对照，记录于 `build/airdrop/driver/stock-control-capture/`。结果包括 STOCK_MODULE_RELOADED、NORMAL_WIFI_ASSOCIATION_OBSERVED、restored。原厂恢复确认后清理了上次重启留下的测试锁。该结果将排查范围缩小到实验模块/构建条件，不能证明具体失败原因。
- 扩大检查到 task_struct、cred、net、sock、socket、page、file、inode、napi_struct 等 17 个额外结构的直接字段，LLVM 调试元数据与运行 BTF 一致；独立 DWARF 抽查 20 类中 18 类对应，rcu_head/workqueue_struct 没有完整可比定义。仍不是完整 ABI 证明。
- 已发现明确遗漏：原厂 ELF 有 `wlan_hdd_state_ctrl_param_write`，设备有 `/dev/wlan` 和 `/sys/class/wlan/wlan`；首版候选没有编译此回调。Android.mk 会按 WIFI_DRIVER_STATE_CTRL_PARAM 传入 `WLAN_CTRL_NAME`，独立构建最初遗漏了这个参数。构建入口现加入 `WLAN_CTRL_NAME=wlan`、`DEVNAME=kiwi_v2`，并在测试中增加“控制设备必须存在才启用 Wi-Fi”的检查。与重启的因果关系仍待新版实测。
- 另一个限制：公开 fuxi 源码版本为 **5.2.1.37D**，已安装模块为 **5.2.1.43X**，不能把公开分支名相同当成源码精确匹配。两者目前的 PLD 名称均为 pld_pcie_kiwi_v2，INI/mac 路径均带 kiwi_v2 前缀；没有无依据修改 DYNAMIC_SINGLE_CHIP。

`capture-runtime.py --serial 510af65c --output <新目录> --duration 180 --kernel-reader /data/local/tmp/marionette-kmsg-reader` 将内核与 system/crash 日志直接写到电脑，设备重启后自动续接新段。原生 reader 直接读取 `/dev/kmsg`，避免设备端 stdio 缓冲丢失最后一段输出。脚本为只读，支持 SIGINT 或在输出目录创建 stop 文件结束。做下一次驱动实验前必须确认两个日志流及 reader ready 标记已出现，不能仅依赖重启后 pstore。

## CNSS 边界诊断（2026-10-14 至 2026-10-16）

补齐 `/dev/wlan` 的第二个候选仍重启。第三个候选又补齐了 `CONFIG_BUS_AUTO_SUSPEND=y`，使 `pld_pcie_ops` 和 `runtime_pm_ops` 的回调表与原厂对应，但仍重启，不能将这些构建遗漏当作已证实的崩溃原因。三个失败哈希均记录在 `rejected-candidates.json`，普通测试入口拒绝加载。

独立诊断模块 `die-logger/` 已完成不触发异常的加载/卸载与日志自检，使用正常 die notifier 和受内核支持的 panic-entry kprobe，最多延迟 500ms 让电脑端日志排出，仍沿正常异常处理路径执行。在一次受控的第三候选诊断复现中取得了 HIF 初始化到 panic 的堆栈，见 `build/airdrop/driver/runtime-pm/diagnostic-repro-capture/`。

固定哈希原厂 `cnss2.ko` 的 `cnss_get_soc_info` 在 `0x1484c` 将目的指针移到输出 `+0x98`，`0x14854` 设置长度 `0x81`，`0x1488c` 调用 memcpy。公开 `struct cnss_soc_info` 却只有 `0x98` 字节，失败候选的局部缓冲区紧接栈 canary，因而越界。原厂 WLAN 的 `pld_pcie_get_soc_info` 同样从 CNSS `+0x98` 复制 `0x81` 字节到 PLD `+0x98`。0005 为两端补齐 `fw_build_id[129]`，以 static_assert 固定尾字段偏移 `0x98`、总大小 `0x120`，并恢复 PLD 复制。

这一发现也说明真实提供方导入 CRC 相符不能代替头文件边界验证。补丁只针对当前固定版本，不代表通用 CNSS ABI。修复版已通过以下短时普通 Wi-Fi 检查，AWDL 收发仍待验证。

## 修复版普通 Wi-Fi 首次通过（2026-10-16 11:28，北京时间）

候选 SHA-256 `29d912bd9f3dddb7faf8a7e17bfb6114a6dd9eac8e07c3c80231eaa0660134aa`，包含 0001 / 0003 / 0004 / 0005、状态控制入口和 runtime PM。503 项导入 CRC 对应真实提供方，PCIe / runtime PM 两个回调表对应原厂。编译调试信息确认两个 SOC-info 均为 288 字节，尾字段偏移 152、长度 129；最终机器码在调用 CNSS 前清零 288 字节局部缓冲，栈 canary 位于该缓冲之后。

USB 实时日志和异常观察器已就绪后，临时加载成功，RAW TX=N，`/dev/wlan` 存在；Wi-Fi 重新关联原路由器（信道 52），IPv4 路由恢复，绑定 wlan0 的网关 ping 3/3 成功，设备未重启。独立 guardian 随后恢复原厂，scmversion=`g939d76a86982`，Wi-Fi 再次关联；诊断模块已卸载，测试锁已释放。证据位于 `build/airdrop/driver/cnss-boundary/`，包括 runtime-test.json、runtime-test.log、rollback.log 和 capture/。

这是短时普通联网通过，不能外推为长期稳定性、AWDL 发射、第三方 RX 或 AirDrop 共存通过。该哈希使用 0001 较早的 QDF 错误转换版本（成功返回不受影响）；后续检查发现 callee 已经返回 Linux errno，0001 已改为 int 原样返回，正在增量构建并记录新候选。

## cfg80211 管理帧临时实验（2026-10-16）

0002 新模块 SHA-256 `8b7740aa50814a251ea5a151c02ce66103ada6233e9b6d6cdcf6b6fc06fd20a6`。189 项导入 CRC 对应真实提供方；从实际 ELF 提取的 135 项导出 CRC、GPL 类别和 namespace 与原厂对应，没有将原厂 CRC 写入候选的导出。两个 WLAN 的 cfg80211 ops 表仅在 add_intf_link / del_intf_link 两个可选 MLO 回调上不同（候选为 NULL）；其他回调位置对应。运行 BTF 没有 cfg80211_ops 可比定义，不能声称已逐字段核对它。

模块使用相同的内核输出独立构建。0002 在编译端默认关闭，本次显式给单模块加实验宏；即使编入，root 参数 `marionette_awdl_mgmt_tx` 默认 N。

```sh
awk '$3 != "cfg80211"' /work/stock-provider.symvers > /work/provider-without-cfg80211.symvers
PATH=/work/clang/bin:$PATH make -C /work/kernel O=/work/kernel-out \
  ARCH=arm64 LLVM=1 LLVM_IAS=1 M=/work/kernel/net/wireless \
  KBUILD_EXTRA_SYMBOLS=/work/provider-without-cfg80211.symvers \
  KCFLAGS=-DCONFIG_CFG80211_MARIONETTE_AWDL -j8 modules
```

`volatile-stack-test.sh` / `volatile-stack-rollback.sh` 仅针对固定哈希的 cfg80211 + WLAN 组合，先核对原厂三模块、引用计数和 USB，再启动独立 90 秒 guardian。顺序卸载 WLAN / mac80211 / cfg80211，临时加载实验 cfg80211 / WLAN，保留 RAW TX=N。结束时恢复原厂三模块，未改写 vendor 或启动分区。

首次整组热加载未恢复 Android 联网：wificond 保留旧 nl80211 family/socket，查询 wiphy 返回 ENOENT，恢复原厂模块后仍失败。重启 wificond 后原厂关联和网关往返恢复；脚本已在换入、回退后分别重启该服务。探针随后直接记录了 family ID 从 45 到 46、回退后 47 的变化。

补齐此步骤的测试通过：STA 关联 5220 MHz，开关 N 时 MIF 提交返回 -107，Y 时返回 0 和 cookie=256，测试前后网关往返通过，设备未重启，原厂三模块和联网恢复。该探针尚未订阅 MLME 多播组，因此 tx_status=0 不能作为固件不发送的证据。现已加入动态 MLME 组解析/订阅和提前到达的状态缓存，手机端回归通过。

随后以修正 errno 的 WLAN `2b5ba1e…` 复测，STA 关联 5260 MHz，5220 / 2437 MHz 各收到对应 cookie 的 `NL80211_CMD_FRAME_TX_STATUS`（ack=true），期间网关 ping 24/24，最大 RTT 150.880ms。**广播帧的 ack=true 表示驱动报告完成结果，不是苹果设备的 ACK。** 尚无外部空口接收证明。电脑端进程被中断后，手机独立 guardian 完成原厂恢复；完整证据从手机回收在 `build/airdrop/driver/mgmt-completion/device-evidence/`。该流程没有实际验证 RAW 数据发送。

证据 `build/airdrop/driver/cfg80211-awdl/`，保留首次失败日志与修正后的独立 capture。仅 host 接受请求，不等于空口发射或 iPhone 发现；数据通道尚未实现。

## STA 管理帧接收与现场对照（2026-10-16 下午）

公开 WLAN 没有动态 mgmt registration 回调；nl80211 的 REGISTER_FRAME 只登记 cfg80211 兴趣。固定 `wlan_hdd_cfg80211_register_frames` 列表没有 AWDL，LIM 的注册帧匹配可在普通 action 处理前转交匹配帧。0006 仅在 root 加载参数 `marionette_awdl_mgmt_rx=1` 时，为 STA 的 vdev 注册 `7f:00:17:f2:08:10` 前缀并配对注销；不修改固件、BSSID 或监管过滤。

WLAN `511358c3…` + 实验 cfg80211 的两次短时运行均保留联网，RX 注册入队；6 / 44 / 149 三轮各 1000ms 驻留取得 9 次 ready 和 9 次 expired，但用户空间 AWDL RX=0。管理帧发送状态回调仍正常，连续网关 ping 24/24。第二次在本机 Mac 临时启用 AWDL Bonjour 服务时复测，结果相同。两次均恢复原厂，诊断模块卸载。证据 `build/airdrop/driver/mgmt-rx/`。

为排除附近没有广播，新增纯接收的 `monitor-witness.sh` 与独立 45 秒 guardian，使用原厂 global monitor 做短时现场对照，结束恢复 con_mode=0 和原 Wi-Fi 开关；**这不是共存实现**。`awdl-witness.c` 直接 AF_PACKET 读取，只打印匹配的 AWDL 头和计数，不发送任何帧。首轮仅频道 44 无 AWDL；覆盖三个社交信道后，在信道 149 的 5 秒内收到 157 帧、信道 6 收到 375 帧，且有本机 `ifconfig awdl0` 对应的 Mac 地址。所有包为内核 PACKET_HOST（非 outgoing），radiotap 及 AWDL BSSID/OUI/版本/subtype 均匹配。信道 44 当时为 0。证据 `build/airdrop/driver/coexist-rx/stock-native-witness-social.log`。

此结果将排查重点转向 STA 接收路径，但驻留事件和相邻时段的 monitor 对照仍不能证明同一帧已经送至固件管理队列。0007 分别观察 WMI 原始管理事件、LIM 注册匹配入口、HDD 用户空间指示入口的全部管理帧及 AWDL 数，用实际阶段计数决定下一层改动。不要将零收包直接判为硬件不支持。

0007 候选 `b04dd7e9…` 已实测：扫频前 WMI 管理帧 115、LIM 25；扫频后 WMI 550、LIM 86；AWDL 在 WMI / LIM / HDD 全为 0，普通联网及管理 TX 完成回调仍通过。随后 0008 候选 `5af72d72…` 使用已有 `scan_f_promisc_mode`（WMI 正常映射到 `WMI_SCAN_FILTER_PROMISCOUS`），仅对 STA、6/44/149、<=1000ms 的 ROC 请求开启。日志确认 9 次请求，扫频后 WMI 普通事件 710、AWDL=0，最终 729、AWDL=0。没有绕过监管或修改固件。两轮均恢复原厂。证据分别在 `build/airdrop/driver/rx-stages/`、`roc-filter/`。

下一条路径是已有 DP monitor 接收通道。0009 以独立 root 加载开关覆盖实验中的 STA+MON 功能选择，在已有 STA 关联时跳过源码里的主动断连，其余并发检查不变。monitor SET_CHANNEL 必须与关联 STA 的频率和带宽一致；后续异信道仍走正常 ROC，不由 monitor 改频。RAW TX 始终关闭。这是待验证的实验，不能把源码层允许创建当成固件支持。

0009 首个候选 `e0c27d4b…` 未进入 monitor 创建阶段：`pe_open+0xb8` 的 `__qdf_mem_malloc` 失败，内核记录 `page allocation failure: order:5`。当时普通空闲内存约 323MiB，但 buddyinfo 无 128KiB 或更大块，PE 会话表请求物理连续内存而失败。设备未重启。guardian 在 CNSS 的 80000ms 启动等待返回后最终恢复原厂，约耗时 85 秒；脚本先报告 restore_pending，之后已回收 restored 证据。重启 Wi-Fi HAL / wificond 后原厂联网恢复。此失败不能用来判定并发硬件能力；该哈希已隔离。

0010 将仅 CPU 使用、未被 DMA 映射的 gpSession 数组改为已有零初始化的 qdf_mem_valloc，并将 pe_open 错误路径、pe_close 正常路径共两处释放改为 qdf_mem_vfree。没有修改 DMA 内存、回收参数或重试策略。最终机器码调用 vzalloc/vfree；`15dda053…` 候选普通 Wi-Fi 关联和网关 ping 通过，卸载恢复也通过。随后创建 monitor 被 policy_mgr 的“3 STA 会话”检查拒绝。证据 `build/airdrop/driver/pe-valloc/`。测试退出等待改为最多 120 秒；90 秒 guardian 是触发恢复的期限，不能保证中断内核中的不可中断等待。后续必须检查实际 restored、模块身份和 Wi-Fi 网关，不能把 restore_pending 当完成。

0011 首版 `d2fefe51…` 在创建阶段记录 opened=3、live STA objects=2、roots=2、links=0、same_mld=1，仍按原检查拒绝，没有创建 monitor 或重启。源码 `mlo_mld_clear_mlo_cap` 会为旧 AP 连接清除整个 MLD 的 MLO/link 标志，但 `mlo_dev_ctx_init` 设置的同一 MLD 所属关系保留。因此修订版用共享非空 MLD context、有效单播 MLD 地址以及 MLD 完整成员数校验，只将同一完整 MLD 计为一个逻辑 STA；角色位仅作诊断，独立 STA / 不完整或未知成员仍拒绝。

0011 修订版 `074414f9…` 成功创建并打开 awdlmon，STA 仍显示关联。第一次脚本的 iw 20MHz 参数格式错误，修正后显式 SET_CHANNEL 返回 EBUSY；从 kernel `chan.c` 确认是 `cfg80211_has_monitors_only` 在驱动之前拒绝。下一轮不请求改频，monitor 无包，普通网关只收到 1/48；HDD 的显式 STA+MON 检查也拒绝全部 9 次 ROC。关联状态没有下降，但数据接收已失败，**绝不是共存成功**。证据 `build/airdrop/driver/mld-owner/`，保留三轮独立日志。每轮均删除实验 monitor 并恢复原厂，实际网关重新通过。

0012 在 monitor open 中使用驱动已有 `wlan_hdd_set_mon_chan`，仅传入 STA 当前频率/带宽并保留其监管检查；不修改 cfg80211 的 EBUSY 检查。候选 `30fa8817…` 初始化 ret=0，15 秒捕获 27050 个 radiotap 帧、21 个 action、0 AWDL（STA 当时为 5260MHz，现场 AWDL 主要在 5745/2437），但网关仍 1/48，删除 monitor 后短时 ping 也未恢复，最终 guardian 恢复原厂后正常。未重启。证据 `build/airdrop/driver/mon-home/`。

0013 调整 monitor 抓取分类：默认监控包含 filter-pass 和 other-BSS，改为仅 other-BSS 管理/数据，普通关联流量不被订阅到 monitor。实际 ELF 符号和编译对象证实本 kiwi_v2 使用 monitor **1.0**，所以补丁修改 1.0 的目的环和状态环；不能按芯片名猜测使用仓库中的 2.0 文件。候选 `601d38f7…` 在 5220MHz 收到 1739 个 monitor 帧，但网关仍 1/48，删除后短时 ping 失败，恢复原厂后通过。证据 `build/airdrop/driver/mon-other/`。

进一步核对 `dp_soc_cfg_attach/init`：KIWI 明确设 rxdma1_enable=0，monitor 1.0 因而配置的是正常 RXDMA_BUF；普通数据需要架构函数 `dp_rxdma_ring_sel_cfg` 的 TLV 订阅与固定偏移，不能把 monitor 格式留在这个共享环。0014 在实验开启且共享环时，于 monitor 配置/复位后重新调用已有的架构配置。`e5b1e5a7…` 真机恢复正常数据：monitor open 期间 48/48，最大 RTT 75.660ms，删除后 3/3；关联保持，未重启并恢复原厂。但 monitor 原始输出为 0，不能将此当作完整共存。证据 `build/airdrop/driver/rx-layout/`。

0015 引入一次性同步捕获实验，每次 root 写入 1..200ms，通过监控格式获取短时原始帧，返回前恢复普通数据格式。要求 mission 模式、已有 STA 关联、driver-owned awdlmon 已打开、0009 实验加载开关为真；持 netdev 引用和 vdev operation guard，不缓存 SoC、不排队后台任务。0/201ms 必须拒绝。首版 `2567bea6…` 通过非法值拒绝，但首次 40ms 返回 capture=4/restore=4；反汇编证明高级 monitor CDP 回调是固定 E_INVAL 的 inline stub，本 profile 没启用 QCA_ADVANCE_MON_FILTER_SUPPORT，不能用非空指针断言它有功能。修订版直接调用已实际编译的 monitor 1.0 setup/update 路径，`bb9f8f36…` 真机六个时隙 capture/restore 均为 0，40/80/160ms 各两次，15 秒 monitor 得到 316 个真实 radiotap 包，网关 47/48（最大 RTT 107.681ms），之后 3/3，关联保持并恢复原厂。只是短时同信道原始帧与联网交替通过，仍有少量丢包，不是完整 AirDrop 共存。证据 `build/airdrop/driver/capture-internal/`。

0016 候选 `2e845aad…` 保留普通 ROC 调度，只放行实验中关联 STA 的 6/44/149、<=200ms 驻留。探针 `--capture-sweep` 每次请求 200ms，仅在返回 cookie、接口和频率对应的 ROC_READY 后写一个 80ms 捕获时隙。首轮 9 次就绪/结束、9 次 capture/restore 均完成，monitor raw 42 帧但无 AWDL；网关 48/48（最大 RTT 276.110ms），之后 3/3，原厂恢复且未重启。探针的 cookie/频率匹配回归已在手机通过。`awdl-witness` 随后补充 radiotap CHANNEL 解析及扩展 present bitmap/对齐/截断测试，正在复核 monitor 实际接收频率。证据 `build/airdrop/driver/slot-roc/`。RAW TX 保持关闭，未实现 IPv6 数据通道或后台自动调度。

频率复测已确认收到的 54 帧分别为 2437MHz 33、5220MHz 16、5745MHz 5，action/AWDL=0；网关 46/48，最大 RTT 206.542ms。所以不能将第一轮零丢包外推为稳定。0017 候选 `1886b7c6…` 将完整分类仅用于短窗口（探针改为 120ms），收到 858 帧，实际频率覆盖 6/44/149 以及恢复后的 home，但仍无 action/AWDL。网关 48/48，平均 RTT 151.674ms、最大 2116.037ms，结束后 3/3。紧接着原厂接收对照在 149 见 140 个 AWDL、6 见 273 个，包含本机 Mac，结束恢复正常 radio。证据 `build/airdrop/driver/slot-full/`。现场广播仍在，不能只增加驻留时长或把缺包归为环境。

0018 候选 `c0da9487affb28f8d7ac4c1067c63e4b462eb374a7c2645ece1dc57fb7406581` 为避免 P2P 扫描路径的差异，通过现有 monitor vdev 信道 API 进行同步短切，限制社交信道 20MHz，并在任何结果下尝试 home 信道/宽度恢复和正常 RX 恢复。`--direct-capture-sweep` 九次 120ms 请求均成功，切换/驻留/恢复的用户空间实际耗时为 406、346、350、289、341、343、392、343、335ms。捕获 924 帧、0 action/AWDL，radiotap 分布 home 5260MHz 295、2437MHz 143、5220MHz 231、5745MHz 255。关联保持但网关 42/48，结束后 3/3，guardian 恢复原厂，未重启；非法 0、201、2412:120、5745:201、5745:0、5745:120:1 输入均被拒绝。证据 `build/airdrop/driver/direct-slot/`，包含最终源码补丁哈希清单和手机回收日志。

当前下一步不是发布驱动：核对 mission 模式固件管理帧 RX 的标准参数/事件与 global monitor 的差异，优先找有具体参数语义和可回退路径的证据。现有头文件虽有 PDEV RX_FILTER / PROMISC 名称，但仓库没有其调用示例或完整位值语义，尚未按猜测写值或修改固件。接收打通后还要补齐 IPv6 数据 TX/RX、同步时隙和 AP 缓冲策略、真实 Mac/iPhone 文件传输、常驻生命周期、锁屏/重启/休眠与功耗验收。AirDropResidentService 目前仍是应用前台待机 + KernelSU 看护，并非 Android system_server 内置实现。

## 进一步区分管理接收路径（2026-10-16 傍晚）

0019 检查了此前遗漏的第三种 HTT 管理分类 MD。`fw-api/fw/htt.h` 明确描述 FP/MD/MO 各管理子类型独立位，`dp_htt.c` 有 MD Action 编码，故仅在短窗口启用 MD mask 0x6000，没有按未知 PDEV 位图猜值。候选 `aafae257…` 的九个窗口中 raw920、action/AWDL=0；网关42/48，最大RTT1822.399ms，之后3/3，原厂恢复。证据 `build/airdrop/driver/md-action/`。

随后找到了与旧 PDEV 参数不同的标准固定参数命令：`wmi_unified.h` 的 `wmi_pdev_set_rx_filter_promiscuous_cmd_fixed_param` 明确包含 pdev_id 和启用/关闭字段，`wmi_tlv_defs.h` 有对应TLV表。0020 按这一定义构造12字节命令、使用已有host-to-target pdev转换，另加默认关闭的root加载参数。在窗口内启用后，无论后续步骤是否失败均请求关闭，再恢复普通RX。候选 `4bef545d…` 取得九组开启/关闭的主机队列成功，但没有固件执行回执或实际效果证明；raw685、action/AWDL=0，网关41/48，最大RTT1942.019ms，之后3/3，原厂恢复。证据 `build/airdrop/driver/wmi-promisc/`。后续实验保持该参数N。

0021 比较了另一个明确源码差异：`wma_set_default_tgt_config` 的global monitor使用RAW资源接收格式0，mission默认Ethernet2；标准per-vdev RX_DECAP_TYPE路径会更新DP并发送WMI参数。仅给monitor vdev显式设置RAW，请求vdev3/type0返回成功，但 `3fb77657…` 九窗口仍raw703、action/AWDL=0，网关42/48，最大RTT1796.599ms，之后3/3。证据 `build/airdrop/driver/monitor-raw-rx/`。这没有证明固件已按请求切换。

0022 进一步比较初始化资源：保持mission模式，在加载参数启用时使用原有RAW资源值，并在STA创建后、关联前通过同一标准API明确配置Ethernet，覆盖内部STA vdev0/1。候选 `ef8ce233…` 日志确认资源RAW0、STA请求Ethernet2，但关联后数据计数为0、网关前置失败；未创建monitor或启动窗口，guardian恢复原厂，未重启。此组合没有验证混合格式支持，哈希已写入拒绝记录，后续诊断保持该加载参数N。证据 `build/airdrop/driver/raw-resources/`。

下一步0023观察此前没有直接核对的DP边界：在monitor 1.0已有的两种MPDU重组路径读取HAL提供的frame-control有效位和字段，计数mgmt/action/data/control，再于重组后、pktlog/radiotap前观察Action/AWDL。缺失HAL操作或无效字段单独报告，不把不可观测当成无包；读取重组帧时只使用线性head长度。没有改动所有权、过滤或包命运。当前重编 `/work/wlan-module-monitor-descriptors.log`，手机恢复原厂、无实验接口/锁/诊断模块。原始标准接口证据及外部检索失败情况在 `build/airdrop/driver/mission-rx-research/findings.json`；Linux ath10k 是不同代固件，不能作为KIWI ABI模板。

## DMA到用户空间的布局边界（0023–0027）

0023首个候选`92c099a1…`在monitor创建之前失败：`dp_prealloc_init+0x7c`申请84592字节、order5，空闲约577MiB但无可用128KiB块。分配表第0项实际是DP_PDEV_TYPE（不是SoC），主机上下文与DMA环/描述符池分开。0024仅对此上下文用qdf_mem_valloc/vfree，正常pool get/put保持地址与清零语义，attach早期失败分支改为与正常detach相同的minidump注销和dp_context_free_mem。DMA/其他上下文分配器不变。

0024候选`89f34188…`普通联网和正常卸载通过。该轮444个描述符，frame-control有效444；类型为management100、Action20、data73、control271。重组后仍444个，总数未少，但post Action=0、post AWDL=0。因此不能说20个Action“被丢包”，也不能在尚未读取签名前称其为AWDL；更不能直接归因固件。证据 `build/airdrop/driver/pdev-valloc/runtime-test.json`。网关44/48，之后3/3，原厂恢复。

0025增加HAL header指针比较、FC计数及有界描述符区域AWDL签名查找。首次候选`ab4ca7e7…`未进入收包：`hal_attach+0x3c`的HAL上下文order5申请失败。编译元数据明确PDEV=84592、HAL=71648、DP SoC=32752字节；0026仅对HAL主机对象用qdf_mem_valloc/vfree，匹配attach失败和detach释放，DMA shadow指针仍由独立coherent分配器管理。当前QDF使用VA minidump，正常注销保留。没有修改DMA或内核回收参数。失败证据 `build/airdrop/driver/monitor-layout/`，编译布局及所有权审查在 `mission-rx-research/context-allocation-audit.json`。

0026候选`0d4d334c…`通过普通联网、monitor观察及卸载。631个有效描述符含10个Action，重组后Action1、AWDL0；HAL header与metadata FC匹配数为0。但有界区域中找到**10个精确AWDL签名**，均起于272；HAL header指针为288，读出header FC=0x2500，即AWDL BSSID的00:25；其前16字节处所谓TLV tag读成0xffffffff000000d0，实际为raw frame的FC/duration和广播DA。normal prefix=272、assumed monitor prefix=400，额外128字节packet-header TLV在这条共享mission环上不存在，误当作TLV的区域实际上已是802.11帧。网关43/48，之后3/3，未重启、原厂恢复。证据 `build/airdrop/driver/hal-valloc/`，尤其kernel日志中的MARIONETTE_MON_TLV_AWDL/MARIONETTE_MON_ACTION_LAYOUT及runtime-test.json。

0027据此在SoC配置中，仅当STA-monitor实验启用、目标KIWI、mission模式、共享RXDMA、RAW资源实验关闭、HAL报告恰为normal272/monitor400时，令monitor解析前缀采用272。该字段用于monitor缓冲弹出和重组，正常STA的RX前缀不改。没有改固件或硬件地址、没有RAW TX。不同模式/布局保留原值，不能推广成通用HAL补丁。当前 `/work/wlan-module-mission-prefix.log`，待验证用户空间能否收到完整AWDL；仅在DMA找到签名不等于最终互通。

补充只读固件记录：ueventd明确请求kiwi/amss20.bin，已从手机提取，SHA `1d05cd1862b04267a7f1414a44607860a5bd1fed2079169a885e09794c3a7837`；报告版本0x1003003a，WLAN.HMT.1.0.c2-00058.3-QCAHMTSWPL_V1.0_V2.0_SILICONZ-1.22499.27。随附Data20.msc含未关联管理帧丢弃及上报主机的debug字典，但尚未证明字典与构建严格匹配或这些分支在测试中执行。不能用字符串代替实际日志。没有写入/修改手机固件。参见 `mission-rx-research/firmware/provenance.json`。


0027运行证据：`build/airdrop/driver/mission-prefix/runtime-test.json`、`runtime-test.log`、`capture/kernel-001.log`。候选SHA `de9cebdb64ad58b821f89286828e0443cb77055838d3b624faa8154df8df9b3d`，506项导入匹配，PCI/runtime-PM选定表匹配。HAL旧header指针288超出修正后的272字节前缀，布局观察器按边界报告hdr_unavailable，不表示真实帧缺失。用户空间见15个AWDL，但尚未做完整TLV长度验证。monitor退出时既有WMA立即释放引用引发DELETE与STOP重叠；内核记录WARN而未重启，之后成功回退。0028曾保留引用等STOP正常/超时回调，但实际STOP没有在HDD删除等待内完成；延迟DP detach使monitor定时器继续运行，最终在 `dp_mon_vdev_timer+0x3c` 崩溃。候选已隔离，恢复原厂。下一步先研究monitor timer stop/deinit与vdev对象删除的既有顺序，再提出更窄修复。


0028失败记录：候选 `920f555cb45bd84441575f47cf5a743acbcf456c8de3536dfe7017024f464e3c` 在普通Wi-Fi前置条件与收包阶段有效（304帧、17个AWDL、14个完整TLV），但删除monitor后HDD等待删除约20秒，随后 `dp_mon_vdev_timer+0x3c` 访问失效对象，异常观察器记录内核异常并触发重启。开机后原厂 `g939d76a86982`、Wi-Fi 3/3及两个常驻服务恢复。此候选禁止重复加载。证据 `build/airdrop/driver/monitor-lifecycle/` 与 `rejected-candidates.json`。


0029–0031均已拒绝，详见rejected-candidates.json。

0031失败记录：候选 `4c6793de90cdd5741d6473b8f00255a5d70cd315b91259ad982ca1119e965f95` 在用户空间收到6个AWDL（3个完整TLV、3个尾部截断）后，monitor删除仍超时；CNSS recovery期间 `target_if_deinit_dbr_ring+0x1f4` 发生 `Oops 96000047` 无效写入并重启。开机后原厂模块、Wi-Fi和服务恢复。0029/0030/0031全部禁止重复加载。后续先对direct-buffer ring的pdev组件引用、recovery并发和deinit所有权做静态审计，不再增加真机补丁。

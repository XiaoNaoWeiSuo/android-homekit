# 进度事件协议（stdout · JSON Lines）

Marionette 的 AirDrop 后端 `luftlift` 除了人类可读日志（走 **stderr**）之外，还可以
在 **stdout** 上输出机器可读的进度事件：**每行一个 JSON 对象**，除此之外 stdout 上
没有任何其他内容。

Android 端（悬浮窗、分享面板、附近设备列表）只解析这条流，不去抓日志。

## 怎么打开

```
luftlift --progress <subcommand>          # 显式开关
MARIONETTE_PROGRESS=1 luftlift <subcommand>   # 环境变量（Android 服务用这个）
```

两者任一为真即开启。关闭时所有事件调用都是空操作（无分配、不写管道），所以库调用方
不受影响。

> 开启后人类可读的提示（如 `Send complete.`、`No AirDrop senders found.`）会自动改走
> stderr，保证 stdout 永远是纯 JSON Lines。

## 事件表

`event` 字段是判别键。字段名一旦发布就不要改，UI 是按字段名取值的。

### 接收侧

| event | 字段 | 含义 |
|---|---|---|
| `listening` | `name` `port` | HTTPS 监听已建立，**但还没被宣告**。此时能收数据，苹果设备却看不到我们。 |
| `ready` | `name` `port` | 已在本地 `awdl0` 上注册 mDNS 宣告；不证明帧已发到空口或苹果设备已发现我们。**每次地址变化/重新宣告都会再发一次**。对端发现应由真实 `/Discover` 或对端解析证据确认。 |
| `discover` | `sender` | 有苹果设备 POST 了 `/Discover`（对方已打开分享面板）。解析失败时为 `"unknown"`。 |
| `request` | `token` `sender` `total` `files[{name,size}]` | 弹出接收确认。此时 `/Ask` 被阻塞，等 UI 回答（最长 45s，超时按拒绝处理）。 |
| `decision` | `token` `accepted` | 确认结果。超时也会发，`accepted:false`，UI 必须收起卡片。 |
| `receiving` | `total` | `/Upload` 开始。`total:0` 表示**总长未知**（苹果固定用 chunked），UI 应显示不确定进度条。 |
| `progress` | `received` `total` | 字节计数，已节流（见下）。`total:0` 时只能显示已接收字节数。 |
| `received` | `dir` `total` `files[{name,size,path}]` | 文件已落盘。`path` 是**实际写入路径**（重名会带 `N-` 前缀），可直接打开。 |
| `error` | `message` | 致命错误，**每个进程最多一条**。 |

### 设备发现（`receive --browse` / `find --watch` / `send`）

| event | 字段 | 含义 |
|---|---|---|
| `browsing` | `duration` | 开始 mDNS 搜索。`0` 表示持续搜索。**接收端每条流开始时也会发一次**，含义是"设备列表从头开始"（首次启动、或 `awdl0` 地址变化后重建宣告）。 |
| `peer` | `name` `address` `port` | 发现/选中一个对端。`send` 里是最终选中的那个；`receive --browse` / `find --watch` 里是每一个。同一个对端重复解析**不会重复发**（按全名去重，端口或地址变了才发）。`address` 优先取链路本地 IPv6 —— AWDL 上唯一可达的地址，可直接拼成 `send --address`。 |
| `lost` | `name` | 对端消失（TTL 到期或发了 goodbye），UI 删行。只对**发过 `peer` 的名字**发。 |

> 为什么"附近设备"不是独立进程：`find --watch` 在 socket 层确实能和 `receive` 并存
> （mdns-sd 设了 `SO_REUSEADDR`/`SO_REUSEPORT`，实测 `find -d 3` 与接收端同时跑没问题），
> 但两个进程各收一份包、各建一份缓存，而且外部进程**分不清哪个宣告是本机**，会把手机
> 自己列成"附近设备"（实测确实如此）。所以接收端用同一个 `ServiceDaemon` 宣告 + 搜索
> （`receive --browse`），并按实例名前缀过滤掉自己。

### 发送侧

| event | 字段 | 含义 |
|---|---|---|
| `stage` | `stage` | 当前协议阶段：`discover` → `ask` → `upload`。 |
| `progress` | `sent` `total` | 上传字节进度（以交给 TLS 的字节计，受 TCP 背压约束，足以驱动进度条）。 |
| `sent` | `bytes` `peer` | 传输完成。`bytes` 是**原始文件字节数**，不是 dvzip 后的大小。 |

`/Ask` 被拒（对方锁屏、"仅联系人"等）时不会有 `upload` / `sent`，只有 `error`。

## 节流规则

`progress` 不是每 64 KiB 都发，否则一条 5 GB 的传输会刷出 8 万条事件：

- 整百分比变化 → 立即发；
- 否则距上次 ≥ **250 ms** 才发（链路卡住时也要证明还活着）；
- `total` 未知时百分比无意义，只走时间规则；
- **最后一个字节强制发**，否则进度条会永远停在 99%。

`stage` / `receiving` 会重置节流状态，因为每个阶段都是自己的一条 0→100% 曲线。

## 示例

接收：

```
{"event":"listening","name":"Marionette 小米 13","port":8771}
{"event":"ready","name":"Marionette 小米 13","port":8771}
{"event":"discover","sender":"Andrew's MacBook Pro"}
{"event":"request","token":"1761097200123-0","sender":"Andrew's MacBook Pro","total":1048576,"files":[{"name":"photo.jpg","size":1048576}]}
{"event":"decision","token":"1761097200123-0","accepted":true}
{"event":"receiving","total":0}
{"event":"progress","received":65536,"total":0}
{"event":"progress","received":1048576,"total":0}
{"event":"received","dir":"/storage/emulated/0/Android/data/dev.local.mihotspot/files/Download/AirDrop","total":1048576,"files":[{"name":"photo.jpg","size":1048576,"path":"/storage/.../AirDrop/photo.jpg"}]}
```

发送：

```
{"event":"browsing","duration":5}
{"event":"peer","name":"Andrew's MacBook Pro._airdrop._tcp.local.","address":"fe80::1c2d:3e4f:5a6b:7c8d","port":8771}
{"event":"stage","stage":"discover"}
{"event":"stage","stage":"ask"}
{"event":"stage","stage":"upload"}
{"event":"progress","sent":262144,"total":1049000}
{"event":"progress","sent":1049000,"total":1049000}
{"event":"sent","bytes":1048576,"peer":"Andrew's MacBook Pro"}
```

## 读取端要点（Kotlin）

- 用 `BufferedReader.readLine()` 逐行读，`JSONObject(line)` 解析；**不要**假设一行会被
  拆包，`readLine` 已经处理了。
- 事件可能乱序交错（多个苹果设备同时发起），用 `token` 区分不同的确认请求。
- 进程退出 = 流结束，UI 必须收敛到终态，不能留一个转圈。
- `error` 之后进程通常退出；收到 `error` 就把 UI 标红并停止等待。

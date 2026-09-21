# miwear-ctl —— 不依赖小米运动健康，直连小米手表（REDMI Watch 5）

一套「手机 App + Termux CLI」，把小米手表（miwear 协议，实测 REDMI Watch 5 / `miwear.watch.o65`）
当成可编程设备：推通知/来电、装快应用（rpk）、查电量、找手表、给手表联网……
**完全绕过官方 App（com.mi.health）**，协议全部逆向自官方 SDK。

> 手表连接走经典蓝牙 SPP（RFCOMM）→ L1/L2 帧 → 认证握手 → 加密会话。
> 逆向来源：`com.xiaomi.wearable.*`（传输/认证）、`com.xiaomi.device.binder.*`（本地绑定）。

---

## 目录

- [它能做什么](#它能做什么)
- [快速开始](#快速开始)
- [为什么必须有一个 App](#为什么必须有一个-app)
- [CLI 命令详解](#cli-命令详解)
- [App 界面](#app-界面)
- [auth key 从哪来（三种来源）](#auth-key-从哪来三种来源)
- [本地绑定 / 重绑协议](#本地绑定--重绑协议)
- [通知 / 来电字段表](#通知--来电字段表)
- [手表联网原理](#手表联网原理)
- [构建](#构建)
- [常见问题 / 已知限制](#常见问题--已知限制)
- [目录结构](#目录结构)

---

## 它能做什么

| 能力 | 协议（module/sub） | CLI | App 界面 | 状态 |
|---|---|---|---|---|
| 连接 + 认证 | apiCode 26/27 | 自动 | 连接 / 认证 | ✅ |
| 推普通通知（短震动） | `7/0` | `notify` | 推送 | ✅ |
| 模拟来电（长震动/来电界面） | `7/0` + `f8/f16` | `call` | — | ✅ |
| 列表/查询/安装/卸载/启动应用 | `20/0,21,1,3,4`、`21/3`、`22/0` | `apps` `app` `install` `uninstall` `launch` | — | ✅ |
| rpk 传输 | `22/0` + MASS ch2 | `install` | — | ✅ |
| 手表状态（电量） | `8/29` | `info` | — | ✅ |
| 找手表（震动/响铃） | `2/18` | `find` | — | ✅ |
| 同步手机 App 安装状态 | `20/7` | `sync` | — | ✅ |
| 手机状态回传（锁屏/亮屏） | `23/0→23/1` | 自动应答 | — | ✅ |
| 手表联网（手机做 NAT 网关） | `18/1` + ch7 | `net` | 联网 | ✅ |
| **本地绑定 / 重绑**（自造 auth key） | `1/17,18,19,25` | `bind` `reset` `rebind` | 查绑定信息 / 本地绑定 / 解绑 / 一键重绑 | ✅ |
| 给快应用发消息（interconnect） | `20/8` | `msg` | — | ⚠️ 未打通 |
| 常驻服务 / 开机自启 | 本地 socket | `serve` | — | ✅ |

---

## 快速开始

```bash
# 0) 前提：手机已 root（su）、已和手表蓝牙配对过
cd ~/miwear-ctl

# 1) 构建并安装 App（走 GitHub Actions 云构建，本地不需要 Android SDK）
./tools/miwear build apk --install

# 2) 体检
./tools/miwear status

# 3) 开常驻服务（后台静默，认证只做一次，之后命令 0.3~0.6s）
./tools/miwear serve start

# 4) 用起来
./tools/miwear info                      # 电量
./tools/miwear notify "标题" "内容"       # 通知（短震）
./tools/miwear find                      # 让手表震动
./tools/miwear install dist/com.miwear.demo.release.1.4.0.rpk
```

> 配置在 `~/.config/miwear/config`（`MAC=` / `KEY=` / `REPO=` / `PORT=`）。
> MAC 会自动从已配对设备里猜；KEY 默认从官方 App 数据库读（见下文），也可以手动填。

---

## 为什么必须有一个 App

结论：**Android 的蓝牙 HCI 是用户态实现的**，内核里根本没有 `hci_dev`。

- `/vendor/bin/hw/android.hardware.bluetooth@1.1-service-qti` 独占 `/dev/ttyHS0`；
- `/sys/class/bluetooth/` 为空、`/proc/net/rfcomm` 没有 socket；
- 所以从 Termux 里 `connect()` 内核 RFCOMM 会在 `hci_get_route() == NULL` 处直接
  `EHOSTUNREACH`（用 ctypes 构造 `sockaddr_rc` 也一样）。

也就是说，**只有经 Android 框架的 `BluetoothSocket` 才能连手表**，而这个 API 只能在 App 里用。
于是我们把 App 做成「蓝牙后端」，Termux 的 `miwear` 命令通过本地 socket 指挥它：

```
Termux CLI ──(127.0.0.1:38787, 一行一个 JSON)──> com.miwear.ctl (CmdServer) ──SPP/RFCOMM──> 手表
```

这样既是 CLI 体验（脚本友好、实时日志流），又不用抢 ttyHS0（代价是手机蓝牙全废）。
不想开常驻服务也能用：每条命令 `am start` 拉起 App 一次（慢，约 5s）。

---

## CLI 命令详解

`tools/miwear` 是唯一入口。通用约定：

- 需要 root 的命令会自动 `su -c`；
- 会**自动 `am force-stop com.mi.health`**（否则手表 SPP 通道被官方 App 占用）；
- 常驻服务在跑 → 走本地 socket；否则回退 `am start` + 读日志。

### 基础

| 命令 | 说明 |
|---|---|
| `miwear status` | 体检：蓝牙/锁屏/手表配对/本机 App/auth key/官方 App 是否在跑/常驻服务 |
| `miwear help` | 命令列表 |

### 常驻服务

```bash
miwear serve start            # 后台静默启动（界面不出现；认证只做一次）
miwear serve start --quiet    # 连常驻通知都不要（可能被系统回收）
miwear serve stop             # 停（同时关掉开机自启）
miwear serve status           # 看状态
miwear serve autostart on|off # 用广播控制开机自启（无需 root）
miwear log -f                 # 实时跟随日志（服务模式下是流式）
miwear rpc '<json>'           # 直接向服务发一条 JSON 命令（调试/脚本）
```

开机自启：用过一次 `serve start` 后会记住，之后开机/重启/覆盖安装由 `BootReceiver` 静默拉起
（HyperOS 需在「应用信息 → 自启动」放行）。

### 设备操作

```bash
miwear notify "标题" "内容" [包名]     # 普通通知（短震动）
miwear call 10086 中国移动 [1|2|3]     # 来电/去电/未接（长震动、来电界面）
miwear info                           # 电量等（module 8/29）
miwear find                           # 让手表震动/响铃（module 2/18）
miwear apps                           # 列出手表已安装快应用（含指纹）
miwear app <包名>                     # 查某个快应用状态
miwear install <a.rpk>                # 装 rpk（自动停官方 App、等结果）
miwear install --build                # 先云构建 quickapp 再装
miwear uninstall <包名> [指纹hex]      # 卸载（设备不回执，看手表）
miwear launch <包名> [uri]            # 启动快应用（设备不回执）
miwear sync <包名> [1|2|3]            # 同步手机侧 App 安装状态
miwear msg <包名> <文本>              # 给快应用发消息（interconnect，未打通）
miwear net [--launch <包名> [uri]]    # 启动手表联网网关（手机侧 NAT）
miwear raw <hex>                      # 发任意 oyt（加密）并打印应答，调试用
miwear log [-n 行数] [-f]             # 看日志
```

### auth key / 绑定（重点，见下节）

```bash
miwear key [--save]                   # 从官方 App 数据库读 auth key
miwear key --set <32位hex>            # 手动指定 auth key
miwear bind --probe                   # 查手表是否允许本地绑定（安全，不改东西）
miwear bind --yes [--userid N] [--phoneid X]   # 本地 ECDH 绑定，生成全新 key
miwear reset --yes                    # 解绑 = 恢复出厂（清空手表）
miwear rebind --yes                   # 一键：解绑 → 等重启 → 本地重绑
```

### 构建

```bash
miwear build apk [--install]   # 云构建 App（GitHub Actions）并可选安装
miwear build rpk [--install]   # 云构建 quickapp（rpk）
```

> 云构建需要 `~/.git-credentials` 里有 GitHub token（`https://user:token@github.com`）。
> 仓库地址用 `REPO=` 配置（默认 `linuxwff789/miwear-ctl`）。

---

## App 界面

`com.miwear.ctl` 的主界面（`MainActivity`）现在把这些能力都做进去了：

| 区域 | 控件 | 说明 |
|---|---|---|
| 连接参数 | 手表 MAC / auth key | 会持久化到本机 `SharedPreferences`（重启不丢） |
| | **读官方 key** | 优先读 Termux 侧导出的 `<files>/official_key.txt`（或 `device_db.official`）；读不到再尝试自己 `su` |
| | 保存配置 | 把 MAC/KEY/userId/phoneId 存到本机 |
| | 连接 / 认证 / 断开 | 基本连接 |
| 手动发帧 | apiCode / field3 hex / 发送 / 联网 | 调试用（发送任意 apiCode） |
| 通知 | 标题 / 内容 / 包名 / 推送 | 普通通知 |
| **绑定 / 重绑** | userId / phoneId（可留空） | 重绑时生成 `appDeviceId` 用；留空则用默认 |
| | **查绑定信息** | 连手表发 apiCode 17；手表仍绑着官方 App 时会直接断开（固件拒绝重绑） |
| | **本地绑定** | 本地 ECDH 生成全新 auth key，成功后自动填回 KEY 并保存 |
| | **解绑(恢复出厂)** | 二次确认；官方 `DeviceBindManager` 里就是 `ERASE_ALL` |
| | **一键重绑** | 解绑 → 轮询等手表重启 → 本地绑定 → 保存新 key |
| 日志 | 滚动文本 | 与 `miwear log` 看到的是同一份（`files/log.txt`） |

界面上的「读官方 key / 解绑 / 重绑」需要 root（`su` 会弹一次授权）。按钮做了二次确认，
不会误触清空手表。

> ⚠️ **为什么「读官方 key」要先在 Termux 里跑一次 `miwear key`**：
> 很多 root 方案（如 KernelSU-Next）只在部分 mount namespace 里暴露 `su`，App 进程里
> `/system/bin/su` 根本不存在（`exec` 直接 `ENOENT`），所以 App 自己没法读
> `/data/data/com.mi.health`。因此 CLI 每次读 key 时都会顺手把结果导出到
> `/data/data/com.miwear.ctl/files/official_key.txt`（还有原件 `device_db.official`，chmod 666），
> App 直接读它即可。若你的设备 App 里能看到 `su`，则 App 也能自己拷。

---

## auth key 从哪来（三种来源）

**auth key 是什么**：16 字节随机值。它在**绑定（pairing）时**由手机↔手表做 ECDH 派生：

```
shared = ECDH(phonePriv, devicePub)                      # secp256r1
okm    = HKDF(ikm=shared, salt=appRandom‖deviceRandom,
              info="miwear-bind", len=64)
auth key = okm[40:56]        # okm[16:32] 是后续 bind-data 的 AES-CCM key
```

两边各存一份，**无法从公开信息反推**。日常连接用它派生会话密钥：

```
okm2 = HKDF(ikm=authKey, salt=randomApp‖randomDevice, info="miwear-auth", len=64)
DeviceKey = okm2[0:16]  AppKey = okm2[16:32]  DeviceIV = okm2[32:36]  AppIV = okm2[36:40]
会话加密 = AES-CTR，IV = key 本身
```

### 方式 A：从官方 App 数据库读（默认）

```bash
miwear key            # 只看
miwear key --save     # 并写入 ~/.config/miwear/config
```

原理：把 `/data/data/com.mi.health/databases/device_db` 拷出来，查 `device` 表里
`model like 'miwear%'` 那行的 **`detail`** 字段 JSON：

```json
{"beaconkey":"…","encrypt_key":"af99…","irq_key":"…","mac":"D4:A3:65:C0:BE:EA",
 "phone_id":"a7649e53-…","sn":"59685/…","token":"af99…"}
```

`encrypt_key`（同值也在 `token`）就是要的 key；`phone_id` 重绑时要用。

> 首次执行需要认证的命令时会自动走这条并写配置。

### 方式 B：手动输入

```bash
miwear key --set af994c1833f9329d0e4b083d56547e13
```

或直接编辑 `~/.config/miwear/config`：

```sh
MAC=D4:A3:65:C0:BE:EA
KEY=af994c1833f9329d0e4b083d56547e13
```

App 界面里也有 auth key 输入框（会持久化）。

### 方式 C：本地绑定（`miwear bind`，完全不需要官方 App）

我们自己复刻了官方 `LocalWearBinderV2` 的**纯本地** ECDH 流程，让手表给我们发一个**全新的 key**：

```bash
miwear rebind --yes     # 一步到位（会清空手表！）
# 或分步：
miwear reset --yes      # 解绑 = 恢复出厂
miwear bind --probe     # 看是否进入可绑定状态（verifyMode=2）
miwear bind --yes       # 真绑 → 生成新 key 并保存
```

⚠️ **前提与后果**

- 手表**必须处于未绑定状态**。已绑定的手表收到 apiCode 17 会**直接掐断 SPP 连接**
  （固件不接受重绑；不是回 `error=1`），所以要先解绑；
- `reset` 官方实现就是 `unbind reset ERASE_ALL`，**手表数据会被清空**，并与官方 App 解绑；
- 绑定成功后官方 App 用的是旧 key，**连不上手表了**；要回官方 App 就重新配对；
- 解绑本身需要现有 key —— 也就是最后一次从官方 App 库读到的那个（或你手填的）。

---

## 本地绑定 / 重绑协议

逆向自 `com.xiaomi.device.binder.LocalWearBinderV2`，全程明文、**不经小米服务器**
（官方 App 里的 `bind/hello`、`bind/ecdh`、`bind/bind` 云接口只是把绑定记录同步到账号）。

连接后先做 L1 握手（我们的 `HELLO`，`CMD_L1START_REQ`），否则手表直接断开。

| apiCode | 方向 | 请求 | 应答 |
|---|---|---|---|
| **17** getBindInfo | 手机→手表 | `oyt{1:1, 2:17, 3: ua0{11: xc0{1:checkDynamicCode, 2:MD5(userId)}}}` | `ua0{12: mb0{1:verifyMode, 3:mac, 4:model, 5:oobMode, 7:did}}`；或 `ua0{3:errorCode}`（1=已绑定） |
| **18** verifyDevice | | `ua0{17: sb0{1:appDeviceId, 2:手机公钥}}`（公钥 64B，去掉 `0x04` 前缀） | `ua0{18: yb0{1:设备公钥, 2:设备签名, 3:设备随机数}}` |
| **19** confirmOOB | | `ua0{19: pb0{1:appRandom, 2:HMAC(shared, appRandom)}}` | `ua0{20: vb0{1:bool}}` |
| **25** sendBindResult | | `ua0{29: uc0{1: AES-CCM(key=okm[16:32], iv={16..27}, aad="bind-data", protobuf(bc0{1:userId,2:id0}))}}` | 无实质回执 |

- `appDeviceId = MD5(phoneId + MAC.toUpperCase() + (userId ?: "null"))`；
- 设备签名校验：`HMAC(shared, deviceRandom) == yb0.f2`；
- `verifyMode == 2` 才是本地 ECDH 绑定（`1` = 只支持服务器 PSK 绑定）；
- **解绑**：认证通道发 `oyt{1:2 (module), 2:0 (sub), 4: shr{1:1}}`，官方日志 `unbind reset ERASE_ALL`，设备不回执。

代码位置：`WearLink.getBindInfo() / localBind() / unbindReset()`。

---

## 通知 / 来电字段表

两种都走 `module 7 sub 0`，用 `lli` 字段区分：

| 字段 | 普通通知 | 来电 |
|---|---|---|
| f1 包名 | 真实包名 | **`"phone"`** |
| f3 / f5 | 标题 / 内容 | 姓名 / 号码 |
| f7 | 通知 id | **0** |
| **f8** | 不填 | **callType**（1=来电 2=去电 3=未接） |
| f11 | 不填 | `true` |
| **f16** | **绝不能填** | **`true`** ← 手表就是靠它进「来电模式」 |

时间字段 f6 用官方格式 `yyyyMMdd'T'HHmmss`。

> ⚠️ 坑：`f16` 只在「微信来电」时为 true。之前一直设 true，导致**普通通知也长震**。

---

## 手表联网原理

手表自己没有联网栈，官方做法是手机当网关：

1. 手机侧加载官方 `libnetproxy.so`（`NetProxy`/`NetProxySender`，已随仓库附带）；
2. 认证后应答手表的 `module 18/0`（能力询问），手表开始在 **L2 ch7** 发原始 IP 包；
3. 手机把 ch7 的 IP 包交给 `libnetproxy.so` 做 NAT 走手机网络，回包再写回 ch7。

```bash
miwear net --launch com.miwear.demo      # 启动网关并拉起快应用
miwear log -f                            # 看 ch7 流量
```

> 三个关键坑（都已修）：
> ① 除 ch7/ch10 外所有 DATA 帧必须立刻 ACK，否则手表发送窗口塞满；
> ② 认证 appInfo 的 `f2` 必须是 **fixed32 float(34.0)**；
> ③ App 被系统 freezer 冻结会让手表报 error 300 → 需要前台服务 + deviceidle 白名单。

---

## 构建

### 云构建（推荐，Termux 里不需要 Android SDK）

```bash
miwear build apk --install     # 触发 GitHub Actions → 下载 artifact → pm install
miwear build rpk --install     # 构建 quickapp 并直接装到手表
```

工作流：`.github/workflows/build.yml`（App）、`.github/workflows/quickapp.yml`（rpk）。
`push` 到 `main` 也会自动构建。

### 本地构建（可选）

需要 JDK 17 + Android SDK：

```bash
gradle assembleRelease        # 产物 app/build/outputs/apk/release/app-release.apk
```

---

## 常见问题 / 已知限制

**Q：为什么必须装 App？Termux 里不能直接连蓝牙吗？**
见[上面](#为什么必须有一个-app)：Android 蓝牙 HCI 在用户态，内核没有 hci_dev。

**Q：`miwear apps` 有时候没响应？**
`module 20` 的 `list_apps`(sub 0) / `app`(sub 21) 在真机上应答不稳定（加密/帧/protobuf 都对照过官方代码，
可能是固件侧额外门控）。偶发回包时格式也与官方预期不同。

**Q：`uninstall` / `launch` 为什么没有回执？**
官方实现就是 `needResponse=false`，**没有回执是正常的**，看手表界面确认。

**Q：`msg`（给快应用发消息）打不通？**
官方 App 的 `interconnect` 用 **module 23**，而 23 的 sub 只有手机状态/注册/trace/跌倒检测，
**没有转发第三方快应用消息**。快应用的 `@system.interconnect` 需要手机侧对应 App 自己实现。

**Q：安装 rpk 后版本号没变 / 静默失败？**
曾经的无条件应答手表 `module 18/0` 会让手表空等 DHCP 卡死，导致后续 rpk 安装静默失败。
现在只有联网网关在跑时才应答。

**Q：`bind --probe` 直接把连接弄断了？**
正常。已绑定的手表收到 apiCode 17 会掐断 SPP（固件拒绝重绑）。先 `miwear reset --yes` 解绑。

**Q：手表 SPP 连不上？**
先 `am force-stop com.mi.health`（CLI 已自动做），或设 `MIWEAR_BTRESET=1` 重启蓝牙清残留连接。

**Q：App 里点「读官方 key」提示读不到 / `su: No such file or directory`？**
很多 root 方案（KernelSU-Next 等）只在部分 mount namespace 里暴露 `su`，App 进程里
`/system/bin/su` 不存在，`Runtime.exec("su")` 会 `ENOENT`。解决办法：先在 Termux 里跑一次
`miwear key`（会自动把 key 导出到 App 的 files 目录），再回 App 点一次；或者直接把 32 位 hex
粘进 auth key 输入框。

**已知限制**

- 手表是单连接：同一时间只能有一个客户端（官方 App 或我们）。
- `reset`/`rebind` 会清空手表数据，且让官方 App 失效（可重新配对恢复）。
- 部分命令设备不回执，只能看手表界面确认。
- HyperOS 需要放行「自启动」才能开机自启常驻服务。

---

## 目录结构

```
miwear-ctl/
├── app/src/main/java/com/miwear/ctl/
│   ├── MainActivity.java   界面：连接/通知/联网/读官方 key/绑定/解绑/重绑
│   ├── WearLink.java       核心：SPP 连接、L1/L2 帧、认证、命令、本地绑定、解绑
│   ├── CmdServer.java      常驻本地 socket 服务（127.0.0.1:38787）
│   ├── GatewayService.java 前台服务（联网网关 / 常驻服务）
│   ├── BootReceiver.java   开机/重启/覆盖安装自启
│   ├── Crypto.java         HKDF / HMAC / AES-CTR / AES-CCM / 密钥派生
│   ├── Framing.java        L1 帧 + CRC-16/ARC + L2 头
│   ├── PB.java             极简 protobuf 读写
│   └── NetProxyBridge.java 手表联网网关桥接
├── app/src/main/java/com/xiaomi/fitness/netproxy/core/  官方 libnetproxy 的 Java 侧
├── app/src/main/jniLibs/arm64-v8a/libnetproxy.so        官方网络库
├── quickapp/               自制快应用（MiWearDemo，含联网 demo）
├── dist/*.rpk              构建好的 rpk
├── tools/
│   ├── miwear              一站式 CLI（bash）
│   ├── miwear_rpc.py       RPC 客户端（把 am 风格 extra 映射成 JSON）
│   ├── rpk_sign.py         RPK 签名器（复刻 aiot-toolkit SignUtil）
│   └── README.md           CLI 细节补充
└── .github/workflows/      云构建
```

---

## 免责声明

本项目仅用于自己设备的协议研究与互操作，所有协议细节均来自对已安装应用的分析。
请勿用于未授权设备。

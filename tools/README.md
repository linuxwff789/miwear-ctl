# miwear 工具集

> 完整文档（原理 / 协议 / 常见问题）在仓库根目录的 [`../README.md`](../README.md)。
> 本文件只列 CLI 速查。

## `miwear` —— 直连手表的一站式命令（Termux + root）

```bash
tools/miwear status                       # 体检：root/蓝牙/锁屏/官方App/手表配对
tools/miwear serve start                  # 启动常驻服务（后台静默；认证只做一次，后续命令 ~0.3s）
tools/miwear serve start --quiet          # 同上但不要常驻通知
tools/miwear key [--save]                 # 读出手表 auth key（来自官方 App 数据库）
tools/miwear key --set <32位hex>          # 手动指定 auth key

tools/miwear bind --probe                 # 查手表是否允许「本地绑定」（不依赖官方 App）
tools/miwear bind --yes                   # 本地 ECDH 绑定 → 生成全新 auth key 并保存
tools/miwear reset --yes                  # 解绑/恢复出厂（ERASE_ALL，清空手表）
tools/miwear rebind --yes                 # 一键：解绑 → 等重启 → 本地重绑生成新 key
tools/miwear install demo.rpk             # 装 rpk 到手表（自动停官方 App、等结果）
tools/miwear apps                         # 列出手表上已安装的快应用（含指纹）
tools/miwear app com.miwear.demo          # 查某个应用在手表上的状态
tools/miwear uninstall com.miwear.demo    # 卸载（自动查指纹；设备不回执）
tools/miwear launch com.miwear.demo       # 在手表上启动
tools/miwear notify "标题" "内容"          # 推普通通知（短震动）
tools/miwear call 10086 中国移动            # 模拟来电（长震动/来电界面）
tools/miwear info                         # 手表状态（电量，module 8/29）
tools/miwear find                         # 找手表：让手表震动/响铃（module 2/18）
tools/miwear sync com.miwear.demo 1       # 同步手机 App 安装状态（module 20/7）
tools/miwear msg com.miwear.demo 'hi'     # 给手表快应用发消息（module 20/8）
tools/miwear raw 08141000                 # 发任意 oyt（加密）并打印应答，调试用
tools/miwear net [--launch <包名> [uri]]  # 启动手表联网网关（手机侧 libnetproxy.so 做 NAT）
tools/miwear log -f                       # 跟随 App 日志
tools/miwear build rpk --install          # 云构建 quickapp 并直接装到手表
tools/miwear build apk --install          # 云构建 miwear-ctl APK 并安装
```

### auth key 从哪来

**方式 A（默认，需要官方 App 装过）**：

- **MAC**：从已配对设备里找名字含 `watch/手环/手表` 的
- **KEY**：把 `/data/data/com.mi.health/databases/device_db` 拷出来，
  查 `device` 表里 `model like 'miwear%'` 那行的 **`detail`** 字段 JSON 的 `encrypt_key`
  （同一个值也在 `token` 里；`extraValues` 里没有）
- 首次执行需要认证的命令时自动推导并写入 `~/.config/miwear/config`；
  也可手动 `tools/miwear key --save`

**方式 B（手动指定）**：

```bash
miwear key --set af994c1833f9329d0e4b083d56547e13
```

或直接编辑 `~/.config/miwear/config`（`MAC=` / `KEY=`）。App 界面里也有 auth key 输入框。

**方式 C（`miwear rebind`，不需要官方 App）**：

auth key 是**绑定（pairing）时**手机与手表做 ECDH、再 HKDF 出来的 16 字节随机值。
我们复刻了官方 `com.xiaomi.device.binder.LocalWearBinderV2` 的纯本地流程
（apiCode 17 getBindInfo → 18 verifyDevice → ECDH → 19 confirmOOB → 25 sendBindResult），
全程不经小米服务器，也不读官方 App：

```bash
miwear rebind --yes        # 解绑(恢复出厂) + 等手表重启 + 本地重绑，一步到位
# 或者分步：
miwear reset --yes         # 解绑（官方 DeviceBindManager 里的 ERASE_ALL）
miwear bind --probe        # 看手表是否已进入可绑定状态（verifyMode=2）
miwear bind --yes          # 真绑：给手表写入一个全新 auth key，并存进 ~/.config/miwear/config
```

⚠️ 前提/后果：
- **手表必须未绑定**。已绑定的手表收到 apiCode 17 会直接掐断 SPP 连接（固件不接受重绑），
  所以要先 `miwear reset`（=恢复出厂，手表数据会清空）或在手表设置里恢复出厂；
- `reset` 需要现有 key（也就是最后一次从官方 App 库读到的）；
- 绑定成功后官方 App（小米运动健康）用的是旧 key，**连不上手表了**；要回到官方 App 就
  重新配对（手表端会重新绑）。

## App 界面（com.miwear.ctl）

App 主界面把这些能力都做进去了（按钮均有二次确认，不会误触清空手表）：

- **连接参数**：MAC / auth key（持久化到本机）+「读官方 key」（走 `su` 读 `device_db`）
- **手动发帧**：apiCode / field3 hex → 发送；联网开关
- **通知**：标题 / 内容 / 包名 → 推送
- **绑定 / 重绑**：userId / phoneId 输入 + 「查绑定信息」「本地绑定」「解绑(恢复出厂)」「一键重绑」
- **日志**：滚动查看（与 `miwear log` 同一份）

> 界面上的读 key / 解绑 / 重绑需要 root（`su` 会弹授权）。

配置放 `~/.config/miwear/config`（可选）：

```sh
MAC=D4:A3:65:C0:BE:EA                    # 留空则自动探测
KEY=af994c1833f9329d0e4b083d56547e13     # 留空则自动从小米运动健康数据库读
REPO=linuxwff789/miwear-ctl
```

环境变量：`MIWEAR_BTRESET=1` 连接失败时重启蓝牙清残留连接。

原理：`am start` 带 `--es install_rpk/--es list_apps/...` 唤起 `com.miwear.ctl`，
App 内部完成 SPP 连接 → 认证 → 执行动作，日志写在
`/data/data/com.miwear.ctl/files/log.txt`，脚本负责等待与展示。

## 通知 / 来电（module 7 sub 0）

两种都走同一通道，用 `lli` 字段区分：

| 字段 | 普通通知 | 来电 |
|---|---|---|
| f1 包名 | 真实包名 | **"phone"**（`Constant.INCOMING_CALL_PACKAGE_NAME`）|
| f3 / f5 | 标题 / 内容 | 姓名 / 号码 |
| f7 | 通知 id | **0**（`INCOMING_CALL_UID`）|
| **f8** | 不填 | **callType**（1=来电 2=去电 3=未接）|
| f11 | 不填 | true |
| **f16** | **绝不能填** | **true** ← 实测手表就是靠它进「来电模式」|

⚠️ **坑**：之前把 `f16` 一直设 true，导致**普通通知也按来电长震动**。官方源码里
`f16` 只在 `isWeChatIncomingCall` 时置 true（`BaseNotifySyncService` 的 `lliVar.p = true`）。

时间字段 f6 官方格式是 `yyyyMMdd'T'HHmmss`。

### 常驻 CLI 服务（`miwear serve`）—— 为什么 App 去不掉

**结论：手表只能通过 Android App 里的 `BluetoothSocket` 连，命令行直连内核 RFCOMM 做不到。**

原因（已逐层验证）：Android 的蓝牙 HCI 是**用户态**实现的 ——
`/vendor/bin/hw/android.hardware.bluetooth@1.1-service-qti` 独占 `/dev/ttyHS0`，
内核根本没有 hci_dev（`/sys/class/bluetooth/` 为空）。所以从 Termux 里 connect 内核 RFCOMM 会
在 `hci_get_route() == NULL` 处直接返回 `EHOSTUNREACH`。

于是把 App 做成**常驻后端**，CLI 通过本地 socket 下令（**后台静默，界面不会出现**）：

```bash
miwear serve start          # 启动（am start-foreground-service 直接起服务，不拉 Activity）
miwear serve start --quiet  # 连常驻通知都不要（用 am start-service；可能被系统回收）
miwear serve status         # 看状态（链路 / MAC / 端口）
miwear serve stop           # 停（顺手关掉开机自启）
miwear serve autostart on|off  # 用广播控制（am broadcast，无需 root 也能触发）
```

启动后：

- 命令从 `am start` + 重认证的 ~5s 降到 **~0.3~0.6s**（认证只做一次，连接一直复用）
- 日志是**实时流**（`miwear log -f`、`netproxy` 的 ch7 流量边跑边看）
- **开机自启**：只要用过一次 `serve start`，开机/重启/覆盖安装后会自动静默拉起
  （`BootReceiver` 收 `BOOT_COMPLETED`；`serve stop` 会关掉它）
  - 小米/HyperOS 需在「设置 → 应用 → miwear-ctl → **自启动**」里允许，否则系统会拦开机广播
  - 首次启动时会自动 `dumpsys deviceidle whitelist +` / `set-standby-bucket active` 防 app freezer
- 常驻通知是 `IMPORTANCE_MIN`（无声、不震动、不弹横幅）；`--quiet` 则完全不挂通知

协议是一行一个 JSON（`nc 127.0.0.1 38787` 可手测）：

```jsonc
// 客户端 →
{"cmd":"notify","title":"标题","text":"内容","pkg":"com.termux"}
{"cmd":"install","path":"/data/data/com.miwear.ctl/files/x.rpk"}
{"cmd":"raw","hex":"08141000"}
{"cmd":"subscribe"}          // 只订阅日志流
// 服务端 →
{"ev":"log","msg":"..."}   // 实时日志
{"ev":"done","ok":true}     // 本条命令结束
```

命令：`link / notify / call / install / apps / app / uninstall / launch / raw / info /
find / msg / sync / net / netstop / state / reconnect / subscribe / logfile`。
不开服务也能用 —— 会回退成「每条命令 `am start` 拉 App 一次」的老模式。

## 已实现的官方 App 能力对照

| 官方能力 | oyt | 我们的命令 | 状态 |
|---|---|---|---|
| 通知推送 | `7/0` | `notify`（短震）/ `call`（来电） | ✅ |
| 列表/查询/安装/卸载/启动应用 | `20/0,21,1,3,4` | `apps` `app` `install` `uninstall` `launch` | ✅ |
| rpk 传输 | `22/0` + MASS ch2 | `install` | ✅ |
| 手表联网（手机做网关） | `18/1` + ch7 | `net` | ✅ |
| 手表状态（电量） | `8/29` | `info` | ✅ |
| 找手表（震动/响铃） | `2/18` | `find` | ✅ |
| 同步手机 App 安装状态 | `20/7` | `sync` | ✅ |
| 手机状态回传（锁屏/亮屏） | `23/0→23/1` | 自动应答 | ✅ |
| 手表快应用 interconnect 消息 | ? | `msg`（发 20/8） | ⚠️ 未打通 |

**`msg` 为什么不通**：官方 App 里 `interconnect` 用的是 **module 23**，而 23 的 sub 只有
手机状态/手机使用注册/手机 trace/跌倒检测——**没有转发第三方快应用消息**。快应用的
`@system.interconnect` 需要手机侧**对应 App**（如百度地图）自己实现，不是通用通道。

## 已知限制

- **module 20 的 `list_apps`(sub 0) / `app`(sub 21) 在真机上应答不稳定** —— 加密、L1/L2、
  protobuf 字段都已对照官方代码确认无误（`oyt{f1=20,f2=0}`），但设备多数时候不回包；
  偶发回包时格式也与官方预期（`oyt.f22=yxr.f8=qxr`）不同。可能是固件侧对该模块的额外门控。
- `uninstall` / `launch` 官方实现就是 `needResponse=false`，**没有回执是正常的**，只能看手表界面确认。

## `rpk_sign.py` —— RPK 签名器

复刻 `@aiot-toolkit/aiotpack` 的 `SignUtil.js`（v2.0.5）。

```bash
python3 rpk_sign.py <未签名的.rpk> <输出.rpk>   # 签名
python3 parse_sig.py <任意.rpk>                  # 解析/校验签名块
```

密钥来自官方 npm 包
`@aiot-toolkit/aiotpack/lib/compiler/javascript/vela/utils/signature/pem/`。

**注意**：设备不校验证书链，自签名即可 —— 但**必须有签名块**，否则设备会崩溃重启。
（用 `aiot release` 构建时已自动签名，一般不需要手工再签。）

# miwear 工具集

## `miwear` —— 直连手表的一站式命令（Termux + root）

```bash
tools/miwear status                       # 体检：root/蓝牙/锁屏/官方App/手表配对
tools/miwear key [--save]                 # 自动读出手表 auth key（来自官方 App 数据库）
tools/miwear install demo.rpk             # 装 rpk 到手表（自动停官方 App、等结果）
tools/miwear apps                         # 列出手表上已安装的快应用（含指纹）
tools/miwear app com.miwear.demo          # 查某个应用在手表上的状态
tools/miwear uninstall com.miwear.demo    # 卸载（自动查指纹；设备不回执）
tools/miwear launch com.miwear.demo       # 在手表上启动
tools/miwear notify "标题" "内容"          # 推通知到手表
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

### auth key 不用手填

手表 MAC 和 `encrypt_key` 会自动获取：

- **MAC**：从已配对设备里找名字含 `watch/手环/手表` 的
- **KEY**：把 `/data/data/com.mi.health/databases/device_db` 拷出来，
  查 `device` 表里 `model like 'miwear%'` 那行的 **`detail`** 字段 JSON 的 `encrypt_key`
  （同一个值也在 `token` 里；`extraValues` 里没有）
- 首次执行需要认证的命令时自动推导并写入 `~/.config/miwear/config`；
  也可手动 `tools/miwear key --save`

> 前提：手表当初是用「小米运动健康」配对过的（密钥由绑定过程生成并存在它库里）。

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

## 已实现的官方 App 能力对照

| 官方能力 | oyt | 我们的命令 | 状态 |
|---|---|---|---|
| 通知推送 | `7/0` | `notify` | ✅ |
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

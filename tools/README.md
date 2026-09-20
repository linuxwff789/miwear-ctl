# miwear 工具集

## `miwear` —— 直连手表的一站式命令（Termux + root）

```bash
tools/miwear status                       # 体检：root/蓝牙/锁屏/官方App/手表配对
tools/miwear install demo.rpk             # 装 rpk 到手表（自动停官方 App、等结果）
tools/miwear apps                         # 列出手表上已安装的快应用（含指纹）
tools/miwear uninstall com.miwear.demo    # 卸载（自动查指纹）
tools/miwear launch com.miwear.demo       # 在手表上启动
tools/miwear notify "标题" "内容"          # 推通知到手表
tools/miwear log -f                       # 跟随 App 日志
tools/miwear build rpk --install          # 云构建 quickapp 并直接装到手表
tools/miwear build apk --install          # 云构建 miwear-ctl APK 并安装
```

配置放 `~/.config/miwear/config`（可选）：

```sh
MAC=D4:A3:65:C0:BE:EA
KEY=af994c1833f9329d0e4b083d56547e13   # 手表 encrypt_key
REPO=linuxwff789/miwear-ctl
```

环境变量：`MIWEAR_BTRESET=1` 连接失败时重启蓝牙清残留连接。

原理：`am start` 带 `--es install_rpk/--es list_apps/...` 唤起 `com.miwear.ctl`，
App 内部完成 SPP 连接 → 认证 → 执行动作，日志写在
`/data/data/com.miwear.ctl/files/log.txt`，脚本负责等待与展示。

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

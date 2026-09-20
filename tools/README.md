# RPK 签名工具

复刻 `@aiot-toolkit/aiotpack` 的 `SignUtil.js`（v2.0.5）。

## 用法
```bash
python3 rpk_sign.py <未签名的.rpk> <输出.rpk>   # 签名
python3 parse_sig.py <任意.rpk>                  # 解析/校验签名块
```

## 原理
见仓库根目录或 MEMORY 中的说明。密钥来自官方 npm 包
`@aiot-toolkit/aiotpack/lib/compiler/javascript/vela/utils/signature/pem/`。

**注意**：设备不校验证书链，自签名即可 —— 但**必须有签名块**，否则设备会崩溃重启。

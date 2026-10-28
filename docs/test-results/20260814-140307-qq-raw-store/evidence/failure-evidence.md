# QQ 对话原文安全存储失败证据

- 时间：2026-08-14 14:03（Asia/Shanghai）
- 环境：`yingge-cloud-intelligence-uat`，HTTP 18083
- 用户报错 traceId：`7f0d9904c4524cb5b5de7f09842bbe1f`
- 稳定错误码：`QQ_RAW_STORE_FAILED`
- 容器运行身份：`uid=100(app) gid=101(app)`
- 失败时目录：`/data/qq-imports`，数字属主 `0:0`，模式 `0755`
- 失败时可写性：`false`
- 脱敏写入探针：`Permission denied`，退出码 `1`

未记录 QQ 聊天原文、QQ 号、Bridge/QCE Token 或加密密钥。

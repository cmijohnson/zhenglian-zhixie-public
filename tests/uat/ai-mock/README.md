# 客户智能分析 UAT 契约模型

该服务只用于隔离 UAT。它返回固定结构、引用请求中真实 `sourceKey` 的 OpenAI-compatible 响应，用于验证队列、证据约束、版本化结果和页面闭环，不代表真实模型质量。

- 不连接互联网。
- 不记录请求正文或客户资料。
- 不读取或保存真实 AI 密钥。
- 正式部署不得加载 `docker-compose.intelligence-uat.yml`。

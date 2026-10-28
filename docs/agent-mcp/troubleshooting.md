# Agent MCP OAuth 与传输故障排查

## 先保留证据

记录发生时间、Agent 客户端名称、错误码和 `traceId`。不截图或复制 Token、客户完整资料、数据库密码、FRP Token 或 AI 密钥。

## 常见问题

| 现象 | 核查 | 处理 |
| --- | --- | --- |
| Agent 找不到授权服务器 | 检查两个 `/.well-known/` 端点是否 200，元数据中 issuer/resource 是否精确等于正式 HTTP 地址 | 修正 FRP 和 `MCP_ISSUER`/`MCP_RESOURCE_URI`；部分客户端会按自身安全策略拒绝远程 HTTP |
| `invalid_redirect_uri` | 对比动态注册和授权请求的完整回调 URI | 当前开放模式接受任意合法 HTTP/HTTPS URI；检查是否缺少主机、包含 userinfo/fragment 或使用了其他 scheme |
| Agent 声称“已配置”但没有授权页 | 查看 Agent MCP 服务器列表，再查网关是否出现 OAuth 元数据和动态注册请求 | 这是客户端未真正启动 MCP/OAuth，不是“我的 Agent”中缺少手工客户端；在 Agent MCP 设置中保存并启用服务后重新点 OAuth 连接 |
| `invalid_grant` | 授权码是否超过 5 分钟、是否重放，PKCE verifier 是否对应 S256 challenge | 重新开始授权；不重用授权码或旧 Refresh Token |
| `invalid_token` / 突然掉线 | 账号、成员关系、密码版本、授权和 Audience | 确认账号有效后重新授权；不手工粘贴旧 Token |
| `insufficient_scope` | 比较授权 Scope、当前角色和工具所需 Scope | 由员工重新同意合理 Scope；不能用 Scope 绕过角色权限 |
| 工具看得到但调用被拒绝 | 检查资源所属团队、主负责人/共享成员和账号状态 | 以服务端实时权限为准，不修改参数伪造组织 |
| 危险操作不执行 | 检查 `approvalId`、参数是否变更、是否超过 5 分钟或已使用 | 在确认页重新批准，使用原参数重试一次 |
| 重试产生冲突 | 检查同一逻辑操作是否使用同一 `idempotencyKey` | 网络重试复用 Key；新业务操作换新 Key |
| SSE 中断 | 检查网关 buffering/read timeout、MCP session ID 和容器重启 | 保持 `proxy_buffering off`；无法恢复时重新 initialize |
| HTTP 地址被客户端拒绝 | 客户端是否强制远程 MCP 使用 HTTPS | 换用明确支持 HTTP 的可信客户端，或恢复有效公网 HTTPS；不要绕过客户端安全校验 |
| TLS 证书报错 | 仅适用于后续恢复 HTTPS：检查 SAN、证书链、过期时间和系统时钟 | 重跑 ACME IP 验证并平滑重载；不使用自签证书冒充可信证书 |

## 服务端核对顺序

1. `/actuator/health/readiness` 是否 `UP`。
2. 网关是否将 `/mcp`、`/.well-known/`、`/oauth2/` 传给后端，并保留公开 `Host`；当前发布的协议是 `http`。
3. FRP 的正式与 UAT 代理是否使用不同远端端口；恢复 HTTPS 后再核对证书 SAN、链与续期定时器。
4. 审计中的 `traceId`、工具、客户端、员工、结果和耗时。
5. PostgreSQL 连接和 Flyway 版本，不直接修改 OAuth/MCP 表。

当前按负责人决定不限制回调主机；OAuth 同意页必须让员工核对客户端名称、回调地址和 Scope，发现陌生地址立即拒绝并通知管理员。

## 回滚

如果发布后出现高优先级故障，停止流量进入新镜像，恢复保留的旧镜像标签，验证健康和主要业务。MCP 新表是向后兼容表，正常回滚不删表、不回滚或删除 PostgreSQL 数据卷。

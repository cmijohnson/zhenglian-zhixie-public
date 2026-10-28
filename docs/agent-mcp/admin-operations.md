# 英格客户云 Agent MCP 管理员运维手册

## 1. 管理边界

`ORG_ADMIN` 除业务工具外，可以管理成员、团队、唯一团队长、注册码、审计和组织级 AI 非密钥配置。`TEAM_MANAGER`、`MEMBER`、`VIEWER` 无法从 `tools/list` 看到管理员工具，直接调用也会被服务端拒绝。

管理页面：

- “Agent 管理”：查看本组织已授权客户端、Scope、到期和最近使用，可强制撤销。
- “智能助手设置”：管理服务开关、Provider 地址、模型和 API 密钥。
- MCP 管理工具仅接受服务开关、Provider 地址、模型和版本号，Schema 中不存在 API 密钥字段。

## 2. 部署配置

服务凭据必须位于仓库外、权限 `600` 的环境文件中。必需的 MCP 环境变量：

```dotenv
MCP_ENABLED=true
MCP_ISSUER=http://101.43.22.42:38080
MCP_RESOURCE_URI=http://101.43.22.42:38080/mcp
MCP_ALLOWED_ORIGINS=http://101.43.22.42:38080
MCP_ALLOWED_HOSTS=101.43.22.42:38080
SESSION_COOKIE_SECURE=false
```

不要在镜像、Compose、Shell 历史、日志或报告中写入数据库密码、FRP Token、AI 密钥或 TLS 私钥。

`MCP_ISSUER` 和 `MCP_RESOURCE_URI` 是 OAuth Token Audience 的一部分，必须与公开地址完全一致。当前 HTTP 配置是负责人明确接受明文传输风险后的临时发布例外；后续启用有效 HTTPS 时必须同时更新这两个值并要求客户端重新授权。

按负责人 2026-07-22 的明确决定，动态客户端注册不设置回调主机白名单，接受任意格式合法的 HTTP/HTTPS URI。服务端仍拒绝非 HTTP(S) scheme、缺少主机、userinfo 和 fragment，授权时仍要求回调与该客户端注册值完全一致。此模式会扩大恶意客户端诱导授权与 HTTP 明文泄露风险；恢复 HTTPS 后应重新评估并收紧策略。

## 3. 发布顺序

1. 只读检查容器、端口、磁盘、PostgreSQL 和现有备份。
2. 执行正式 PostgreSQL `pg_dump`，生成 SHA-256，并恢复到临时数据库进行校验。
3. 以独立 Compose Project、PostgreSQL 目录、Session 表、Issuer 和端口启动 MCP-UAT；不连接正式库。
4. 运行零跳过后端测试、前端单测、TypeScript 检查、生产构建、OAuth/权限矩阵、幂等、危险确认和有限重连验证。
5. UAT 通过后使用同一镜像发布，保留旧镜像标签与数据库备份。
6. 验证公网端口、OAuth 元数据、四角色、跨组织拒绝、审计、撤销和容器重启；HTTPS 恢复后再验证 443 与证书链。

禁止使用 `docker compose down -v`，禁止删除 PostgreSQL 持久化目录。

## 4. 审计与应急撤销

每次 MCP 调用记录工具名、客户端、员工、组织、结果、耗时和 `traceId`，不记录原始工具参数、完整客户资料、Token 或 AI 密钥。

发现异常调用时：

1. 在“Agent 管理”强制撤销相关授权。
2. 停用账号或修改密码会使其全部 Agent Token 立即失效。
3. 按 `traceId`、工具名、员工和时间窗查询审计，不导出无关客户数据。
4. 如需平台级停用，设置 `MCP_ENABLED=false` 并平滑重启后端；不删除数据表。

## 5. 传输和健康检查

- 当前正式 MCP 通过 FRP TLS 传输隧道映射到公网，但员工到 FRP 服务端这一段仍是明文 HTTP；不得把它描述为端到端 HTTPS。
- 后续恢复证书时每 12 小时检查续期，余量低于 48 小时告警；续期后平滑重载网关并验证公网 IP SAN 与完整证书链。
- 应用就绪检查：`/actuator/health/readiness`。
- OAuth 健康检查：`/.well-known/oauth-protected-resource/mcp` 和 `/.well-known/oauth-authorization-server`。

故障恢复见 [OAuth 与 HTTPS 故障排查](./troubleshooting.md)。

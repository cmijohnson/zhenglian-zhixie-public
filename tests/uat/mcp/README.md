# MCP UAT

此目录只放可重复测试代码和脱敏结果。环境文件必须放在仓库外，权限为 `600`，测试完成后删除临时凭据。

启动隔离栈时固定使用：

```bash
COMPOSE_PROJECT_NAME=yingge-cloud-mcp-uat docker compose \
  --env-file /path/outside/repository/mcp-uat.env \
  -f docker-compose.yml \
  -f docker-compose.prebuilt.yml \
  -f deploy/docker-compose.mcp-uat.yml up -d --build
```

必须的隔离项：`MCP_UAT_POSTGRES_DATA_DIR`、`MCP_UAT_BACKUP_DIR`、`MCP_UAT_MIGRATION_DIR`、`MCP_UAT_POSTGRES_DB`、`MCP_UAT_ISSUER`、`MCP_UAT_RESOURCE_URI`、`MCP_UAT_ALLOWED_ORIGINS`、`MCP_UAT_ALLOWED_HOSTS`和 `MCP_UAT_LOCAL_PORT`。

禁止将任何一项指向正式 PostgreSQL 目录或正式数据库，禁止 `docker compose down -v`。

首次协议测试运行 `protocol_smoke.py`，它会在 Provider 可用时执行一次真实 AI 简报冒烟。发布同一镜像后的重复回归必须增加 `--skip-real-ai`，避免重复 Token 消耗；OAuth、MCP、业务写入、幂等、人工确认、会话上限和刷新令牌重放仍会完整执行。

正式发布后的验证使用 `--read-only --skip-real-ai`。该模式只执行登录授权、PKCE、MCP 初始化、工具/资源发现和只读健康检查，随后撤销 Access Token、清理测试授权，不创建或修改客户业务数据。

浏览器实测如需短暂使用人工已知密码，只能对隔离 UAT 执行 `browser_password_window.py install`。密码由终端安全输入且不会打印；测试结束后必须执行 `browser_password_window.py close`，随机轮换密码并原子更新仓库外权限为 `600` 的环境文件。

`create_browser_consent_fixture.py` 只创建一次性待处理 OAuth 请求，用于实际浏览器检查客户端名称、精确回调、Scope 和窄屏布局；验收后必须在页面拒绝或由过期清理任务处理，脚本不会产生 Token。

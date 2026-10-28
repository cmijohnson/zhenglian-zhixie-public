# 公网 MCP HTTPS 网关

本目录只保存无凭据模板。FRP Token、证书私钥和环境文件必须保存在仓库外，权限设为 `600`。

发布前必须满足：

1. `*example.com` 已解析到 FRP 公网服务器；为正式入口选定一个固定主机名（建议 `example.com`），并为该主机名签发公网可信证书。
2. FRP 客户端启用传输层 TLS；UAT 与正式各使用一个独立、未占用的远端端口。
3. 将模板占位符渲染到公网服务器，先执行 `nginx -t`，再平滑 reload。
4. UAT 高端口 HTTPS 通过 OAuth、MCP 和权限回归后，才允许让固定域名的 443 指向正式 FRP 端口。
5. 每 12 小时检查续期；证书剩余不足 48 小时时告警，续期成功后执行 `nginx -t` 并平滑 reload。

不得把 HTTP、自签证书或跳过证书校验作为正式降级方案。不得在未完成 PostgreSQL 备份恢复验证前迁移正式数据库。

当前模板使用以下占位符：

- `__APP_DOMAIN__`：正式域名，例如 `example.com`。
- `__UAT_DOMAIN__`：隔离测试域名，例如 `example.com`。
- `__DOMAIN_CERT_FULLCHAIN__` / `__DOMAIN_CERT_PRIVATE_KEY__`：仓库外证书路径。
- `__FRP_PROD_REMOTE_PORT__` / `__FRP_UAT_REMOTE_PORT__`：FRP 服务端本机端口。

DNS 解析成功并不代表入口已经可用。安装前必须确认域名不再返回默认 Nginx 页面，证书链可信，且反向代理实际到达对应 FRP 端口。飞书 OAuth 回调建议使用：

`https://example.com/api/v1/auth/feishu/callback`

## 当前正式域名切换文件

- 公网 FRP 服务器 Nginx 配置：`example.com.conf`。
- 宝塔 Nginx 的 ACME 临时配置：`example.com.http.conf`。
- 宝塔 Nginx 的最终 HTTPS 配置：`example.com.bt.conf`。
- 公共代理参数：`yingge-mcp-proxy.conf`。
- 正式机应用域名覆盖：`../docker-compose.public-domain.yml`。

切换顺序必须固定为：

1. 在公网服务器先确认 `127.0.0.1:38080` 可访问英格云健康检查。
2. 签发 `example.com` 的公网可信证书。
3. 安装 Nginx 配置及代理参数，执行 `nginx -t` 后平滑 reload。
4. 从公网验证 HTTPS 登录页、健康检查、WebSocket 与 MCP。
5. 最后把 `docker-compose.public-domain.yml` 作为 Compose 的最后一个覆盖文件滚动更新 backend。

在第 4 步通过前，不得提前启用 `SESSION_COOKIE_SECURE=true`，否则仍从内网 HTTP 访问的浏览器将无法保存登录 Cookie；也不得提前切换飞书 OAuth 回调。

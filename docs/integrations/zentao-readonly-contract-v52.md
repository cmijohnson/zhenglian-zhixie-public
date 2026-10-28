# 禅道 22.5 × 英格云 V52 只读集成契约建议

> 本文只定义契约和发布边界，不实施禅道改造，不包含任何凭据、客户明细或个人隐私数据。

## 1. API

保留现有 `CustomerService` 和 `CustomerSpecifications.visibleTo(CurrentActor)` 作为唯一数据可见性来源，不让禅道直连 PostgreSQL，也不让集成层绕过 Application Service。

建议新增专用只读路由：

- `GET /api/v1/integrations/zentao/v1/me`
- `GET /api/v1/integrations/zentao/v1/customers`
- `GET /api/v1/integrations/zentao/v1/customers/{customerId}`
- `GET /api/v1/integrations/zentao/v1/customers/{customerId}/timeline`
- `GET /api/v1/integrations/zentao/v1/capabilities`

列表支持 `cursor/limit<=100/updatedSince/includeArchived=false`，详情和时间线使用 `ETag`/`If-None-Match`。现有 `/api/v1/customers` 可复用服务层，但不应将其完整 `CustomerDtos.Response` 直接暴露给禅道。

## 2. 最小字段

列表 DTO：

- `customerId`、`displayName`、`school`、`major`、`grade`
- `classificationLevel`、`intentionLevel`、`currentStatus`、`dealState`
- `ownerDisplayName`、`collaboratorCount`
- `lastContactAt`、`nextFollowUpAt`、`archived`、`updatedAt`、`version`

详情可增加 `demandSummary`、标签、可见性和协作人显示名。时间线仅返回 `eventId/occurredAt/type/channel/summary/outcome/nextFollowUpAt/createdAt`。

默认不返回手机、微信、QQ、原始聊天、附件、AI 原文、绩效证据、内部备注和密钥。如未来确需联系方式，必须增加独立 Scope 和字段级权限，不与普通客户读取捆绑。

## 3. 账号绑定

绑定记录应保存 `yinggeUserId + organizationId + membershipId + zentaoUserId`：

- `userId` 是英格云账号的稳定主体标识。
- `organizationId` 是租户边界。
- `membershipId` 是当前组织角色、团队和数据权限的执行标识。
- `username` 只用于展示和人工核对，不作关联主键。

建议唯一约束为 `(zentaoUserId, organizationId)`。解绑立即撤销对应 OAuth Grant并清理该绑定的缓存。账号停用、成员关系失效或离开组织后立即 fail closed；同一 `userId` 加入新组织时必须显式重新绑定，不自动继承旧 `membershipId`。

## 4. 鉴权与 Scope

推荐复用现有 OAuth 2.1 Authorization Code + PKCE 基础设施，但为禅道增加独立 Resource Audience，不把 MCP Token 当成通用 REST Token。禅道使用预注册的服务端客户端，回调地址精确匹配。

最小 Scope：

- `yingge.customer.read`
- `yingge.timeline.read`（只在禅道需要时间线时申请）

Access Token 15 分钟，Refresh Token 30 天并强制轮换；Token 绑定 Audience、客户端、`userId/organizationId/membershipId`和 Scope。服务客户端只可获取健康/能力元数据，不允许用 Client Credentials 模拟某名员工读客户。

## 5. 数据隔离

- 组织必须从 Token 推导，请求不接受 `organizationId`。
- 私有客户仅对主负责人或有效 `CustomerAccessGrant` 协作者可见。
- 团队客户仅对对应团队或管理范围可见。
- `VIEWER` 不因“组织可见”自动获得客户，仍依赖所有权或明确授权。
- 归属公共池或二线招募期间仅可返回现有脱敏摘要，禁止通过普通客户详情绕过。
- 归档客户默认不返回；显式 `includeArchived=true` 时仍重新计算当前可见性。
- 跨组织、停用账号、无效成员或过期授权统一返回不可用，不泄露资源是否存在。

## 6. 缓存、限流、审计和发布

- 缓存键必须包含 `clientId + userId + organizationId + membershipId + scopes + normalizedQuery`。
- 建议列表 TTL 30–60 秒，详情/时间线 15–30 秒；用客户 `version/updatedAt` 生成 ETag。
- 解绑、撤销、成员停用和客户更新必须使相关缓存失效；不得跨用户共享客户响应缓存。
- 建议限流：60 请求/分钟/绑定成员，300 请求/分钟/客户端与组织；429 返回 `Retry-After`。
- 审计记录客户端、员工、组织、路由、结果、耗时、返回条数和 `traceId`；不记录 Token、查询结果或客户原文。
- 上线前需要 PostgreSQL 备份/恢复验证、新路由权限矩阵、跨组织/私有/归档契约测试、限流与撤销测试。首版只读，不提供 POST/PUT/PATCH/DELETE、万能 HTTP 代理或禅道直连英格云数据库。

## 7. V52 基线

- 正式产品版本：`2026.08.27.1`
- 正式后端：`yingge/cloud-backend:2026.08.27.1-20260828T0632-v52-amd64`
- 正式 Flyway：`V52`
- 后端 PostgreSQL 完整测试：151 / 151 PASS，0 跳过
- Android 测试：37 / 37 PASS
- 移动契约：Android 166，iOS 58
- 生产镜像隔离功能回归：149 / 149 PASS

禅道集成应在 V52 之上另起版本，不改动或回滚 V52 已发布的 Android 修复。

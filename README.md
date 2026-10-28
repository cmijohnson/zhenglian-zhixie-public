# 证链智协 — 可信 AI 客户协作系统

面向中小团队客户协作场景的可信 AI 运行支架：把散落在 QQ 群、飞书多维表格与自建业务系统里的客户资料，串成
「多通道证据 → 授权智能 → 受控行动 → 审计回链」的可核查链路。

作品为 2026 第八届全球校园人工智能算法精英大赛 · 算法主题赛（AI+开源）参赛作品，本仓库是其开放成果。

## 它解决什么问题

- 同一客户的事实分散在聊天、表格与业务系统里，近期记录不等于相关记录；
- 生成式模型给出的建议缺乏来源约束，无法判断是否越权或引用域外内容；
- 智能体可以调用工具、写入业务状态，但缺少可追溯的确认与回滚路径。

## 核心机制

1. **Evidence Harness（证据支架）**：上下文、证据、模型、输出、工具、行动、追踪七类控制面，逐项定义输入边界、拒绝条件与证据制品。
2. **QQ 成员容器**：按成员隔离的会话容器，加密预览后由本人确认导入，路由白名单与来源台账。
3. **飞书受控协同**：OAuth 绑定身份，字段分级、来源版本与快照哈希，草稿经人工确认后写入业务。
4. **授权域内证据约束 RAG**：先按组织/客户/ACL 圈定证据域，再做语义召回；输出逐条携带来源键，来源键合法性校验不通过则拒绝。
5. **MCP 受控能力面**：工具注册表 + Schema/Scope/审批/幂等四道门，仅覆盖运行时已登记工具。
6. **人工确认门与 Workflow 状态机**：持久化状态机，确认不可绕过，失败可恢复、重复触发幂等。

## 目录结构

```
backend/          Spring Boot 后端（身份、客户、证据、RAG、MCP、审计）
frontend/         Vue 3 前端（客户工作台、审批与审计视图）
contracts/        OpenAPI 契约与移动端接口
deploy/           反向代理与 Compose 部署片段
docs/             架构、数据字典、用户手册、测试记录与交接说明
scripts/          初始化、迁移与验证脚本
tests/            集成测试与合成数据生成
docker-compose.yml
```

## 快速开始

前置：Docker 与 Docker Compose。

```bash
export POSTGRES_PASSWORD='<set-a-local-password>'
docker compose up -d --build
```

启动后访问 `http://localhost:8080`，API 文档见 `/swagger-ui`。首次启动由 Flyway 执行数据库迁移（V1–V64）。

不使用 Docker 时，后端需要 Java 21 与 PostgreSQL 16+（含 pgvector 扩展），配置 `DB_URL`、`DB_USERNAME`、
`DB_PASSWORD` 后执行 `mvn -pl backend spring-boot:run`。

## 数据与复现

仓库只使用合成数据：`tests/competition/generate_synthetic_dataset.py` 可生成固定随机种子（20260927）的
客户、沟通、任务与证据样本；评测脚本与判定口径见 `docs/`。真实客户数据、生产配置、凭据与受限第三方二进制
不在本仓库范围内。

## 许可

本仓库以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整文本见 [LICENSE](LICENSE)。
第三方组件、模型、字体与外部服务的权利边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与
`docs/` 下的资源清单。复用、修改与再分发须遵守 GPL-3.0 的署名与开源义务。

## 维护与反馈

- 维护：智链细米团队，按版本迭代（见 `CHANGELOG.md`）；
- 反馈：通过仓库 Issue 提交问题与复现步骤；
- 贡献者：见 [CONTRIBUTORS.md](CONTRIBUTORS.md)（JulyOS、JinweiOS 等）；
- 安全问题：请勿公开提交敏感信息，先通过 Issue 说明影响范围。

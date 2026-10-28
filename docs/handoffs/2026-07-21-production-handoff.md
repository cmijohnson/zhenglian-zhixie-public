# 英格客户云生产交接（2026-07-21）

> 用途：供新 Codex 会话快速恢复项目上下文。  
> 本文不包含 SSH 密码、FRP Token、AI API Key、Session Cookie 或其他凭据。新会话若需要访问生产服务器，必须由用户在新会话内重新明确授权并单独提供凭据。

## 1. 项目边界

- 当前开发目录：`/Users/cmijohnson/Documents/英格营销团队app/cloud-java/`
- 只允许修改 `cloud-java/`。
- 不得修改 `native-mac/`、顶层 `src/`、`src-tauri/`、旧 SQLite 数据库或旧桌面打包脚本。
- 父 Git 仓库当前分支为 `main`，`git status --short -- cloud-java` 显示 `?? cloud-java/`。不要未经用户确认就全量 `git add`、提交或重写历史。
- 完整测试方案：`docs/formal-business-full-process-test-plan.md`
- 测试基线：`docs/testing.md`
- 使用手册：`docs/user-manual.md`

## 2. 最新产品需求与已实现内容

### 2.1 管理员客户纯表格视图

- `/customers` 中仅 `ORG_ADMIN` 可见“表格 / 卡片”切换。
- 管理员默认表格每页 50 条，卡片每页 30 条，选择结果按管理员账号保存在浏览器。
- `TEAM_MANAGER` / `MEMBER` / `VIEWER` 保持原卡片视图，不请求管理员团队、成员接口。
- 新增管理表格专用筛选：院校、团队、负责人，并保留搜索、状态、意向、年级、归档和排序。
- 后端 `GET /api/v1/customers` 新增 `school` 和 `ownerMembershipId` 参数；院校为忽略大小写的包含查询，负责人为精确 membership ID。
- 表格 14 列：客户、院校、专业/年级、来源、负责人、联系方式、团队/录入人、意向/线索审核、客户状态、跟进摘要、最近沟通、下次跟进、更新时间、操作。
- 使用真正 `<table>`、固定表头/姓名列、横向滚动、超长内容截断与 title 完整提示。

### 2.2 管理员任务矩阵

- `/tasks` 管理员默认显示“任务行 × 成员列”矩阵，可切换回原任务列表。
- 固定任务列包括序号、公共任务、截止时间、备注；后续为成员状态列和操作列。
- 状态显示：未完成、进行中、待验收、已完成、已退回，使用不同颜色。
- 保留现有验收、关闭和删除入口；矩阵单元格点击进入现有验收流程。
- 新增管理员接口 `GET /api/v1/tasks/matrix`，一次聚合任务和分配，避免前端 N+1 请求。
- 一般成员不得访问矩阵接口。

### 2.3 团队数据客户表格

- `/team-data` 客户 Tab 默认密集表格，可切换卡片。
- 表格 11 列：姓名、院校、专业/年级、创建时间、流量来源、负责人、QQ、微信/手机、报班意愿、跟进记录、操作。
- 院校、专业、来源、负责人使用彩色标签。
- 保留团队、成员、搜索、年级快捷筛选和统计卡。

### 2.4 相关先前功能

- 院校字典 V9 已存在，默认包含“示例大学”和“示例科技大学”，管理员可增加院校选项。
- 客户档案和团队数据已有 2024–2028 年级快捷筛选。
- 智能助手管理员服务开关、Provider API 和模型配置的数据库迁移为 V8；本次交接未读取、输出或更改 AI 密钥。

### 2.5 年级字段与客户标签拆分

- 客户编辑页的年级已从自由文本改为 2024–2028 年下拉，标准值保存为四位年份；`26级`、`2026级` 在界面加载时规范为 `2026`。
- “大二”等无法可靠推断入学年份的旧值以“历史值”保留，提示人工改选，不会静默覆盖。
- `2025`、`2026` 等年份型标签不再显示在客户表单、客户详情或沟通同步标签中。
- V10 迁移只停用旧年级标签，不删除标签行或 `customer_tags` 历史关联；生产迁移前后关联数均为 74。
- 新组织不再初始化年级标签，标签创建接口会拒绝“年级”分类及年份型标签名称。

### 2.6 客户表格联系方式、搜索与来源字典

- 管理员客户表格的联系方式按“手机 → 微信 → QQ”显示所有已填写渠道；不存在手机号时会继续显示已有微信或 QQ，不再用“手机未填”遮挡真实联系方式；全部为空时才显示“暂无联系方式”。
- 客户搜索框已改为输入框右侧的独立搜索按钮，回车与按钮点击使用相同查询逻辑。
- 客户来源默认项统一为：小红书、抖音、新生群、运营商、朋友推荐、其他。
- 新建/编辑客户页提供“＋ 添加来源”，有客户写权限的用户可将自定义来源加入本组织选项；同组织内启用来源名称忽略大小写唯一。
- V11 只停用旧 bootstrap 来源选项，不删除字典行或客户来源引用；历史来源仍可在编辑旧客户时显示。

### 2.7 团队指标单行与客户共享跟进

- `/team-data` 的 7 个统计指标固定在同一行；空间不足时横向滚动，不再换行形成不齐整的末行。
- 主负责人或 `ORG_ADMIN` 可在客户详情通过“共享跟进”选择协作者；同一客户仍只有一份档案和一条统一时间线，主负责人不变。
- 协作者可查看客户并新增沟通时间线，但不能编辑基础画像、归档客户或转移主负责人；主负责人和管理员负责增删协作者。
- 候选人只包含本组织内启用且具备写权限的 `ORG_ADMIN`、`TEAM_MANAGER`、`MEMBER`；`VIEWER`、停用成员、跨组织成员均不可共享。
- 客户详情显示主负责人、协作跟进成员和每条时间线的记录人；共享变更写入审计日志。
- 复用既有 `customer_access_grants` 表；V12 合并历史重复成员授权并建立客户/成员唯一部分索引，不复制客户档案或时间线。

## 3. 本次修改的主要文件

### 后端

- `backend/src/main/java/com/yingge/cloud/customer/api/CustomerController.java`
- `backend/src/main/java/com/yingge/cloud/customer/api/CustomerDtos.java`
- `backend/src/main/java/com/yingge/cloud/customer/application/CustomerService.java`
- `backend/src/main/java/com/yingge/cloud/customer/infrastructure/CustomerSpecifications.java`
- `backend/src/main/java/com/yingge/cloud/task/api/TaskDtos.java`
- `backend/src/main/java/com/yingge/cloud/task/api/TaskController.java`
- `backend/src/main/java/com/yingge/cloud/task/application/TaskService.java`
- `backend/src/main/java/com/yingge/cloud/task/infrastructure/WorkTaskAssignmentRepository.java`
- `backend/src/main/java/com/yingge/cloud/organization/application/BootstrapInitializer.java`
- `backend/src/main/java/com/yingge/cloud/tag/api/TagController.java`
- `backend/src/main/java/com/yingge/cloud/tag/infrastructure/DictionaryItemRepository.java`
- `backend/src/main/java/com/yingge/cloud/tag/infrastructure/TagRepository.java`
- `backend/src/main/java/com/yingge/cloud/interaction/api/InteractionDtos.java`
- `backend/src/main/java/com/yingge/cloud/interaction/application/InteractionService.java`
- `backend/src/main/java/com/yingge/cloud/interaction/infrastructure/InteractionEntity.java`
- `backend/src/main/java/com/yingge/cloud/importexport/application/ExportService.java`
- `backend/src/main/resources/db/migration/V10__retire_grade_tags.sql`
- `backend/src/main/resources/db/migration/V11__source_channel_defaults.sql`
- `backend/src/main/resources/db/migration/V12__customer_collaboration_grants.sql`

### 前端

- `frontend/src/views/CustomersView.vue`
- `frontend/src/views/TaskCenterView.vue`
- `frontend/src/views/TeamDataView.vue`
- `frontend/src/styles.css`
- `frontend/src/lib/customerProfile.ts`
- `frontend/src/views/CustomerFormView.vue`
- `frontend/src/views/CustomerDetailView.vue`

### 回归测试

- `backend/src/test/java/com/yingge/cloud/ComprehensiveMockDataIntegrationTest.java`
- `backend/src/test/java/com/yingge/cloud/database/FlywayPostgresIntegrationTest.java`
- `frontend/src/views/CustomersView.test.ts`
- `frontend/src/views/workflows.test.ts`
- `frontend/src/lib/customerProfile.test.ts`

## 4. 验证结果

- 前端完整单测：32/32 通过，0 跳过。
- TypeScript 检查：通过。
- 前端生产构建：通过。
- 后端完整测试：30/30 通过，0 失败，0 错误，0 跳过。
- 修复了一个测试基线缺陷：Flyway 已有 V9，旧断言仍期望 8 个迁移。现已期望 9，并实际验证 `uq_dictionary_school_label` 索引。
- 客户共享回归首次发现“新建沟通的立即响应缺少记录人显示名”；已保留失败证据，修复响应映射并增加回归断言，随后后端完整测试 30/30 通过。
- 浏览器实机：
  - 客户表格 14 列、50 条/页。
  - “示例大学”院校筛选实际返回 43 条，抽查均为示例大学。
  - “张云柯”负责人筛选实际返回 8 条，负责人均正确。
  - 任务矩阵实际显示 7 个任务，成员状态、矩阵/列表切换正常。
  - 团队客户表实际显示 75 条、11 列、375 个彩色标签，表格/卡片切换正常。
  - 390×844 窄屏下三页都保持表格，并产生正确横向滚动。
  - 浏览器控制台错误：0。
  - 年级编辑实机：单一 2024–2028 下拉，2025/2026 标签消失，2025↔2026 切换正常并恢复原值；未提交真实客户变更。
  - 管理员客户表格实际显示手机、微信、QQ 中已存在的联系方式；有微信无手机、有 QQ 无手机、多个联系方式和全部为空均由单测覆盖。
  - 客户搜索按钮位于输入框右侧，表格仍为 50 条/页，院校、团队、负责人筛选保留。
  - 新建客户页来源项实机为“小红书、抖音、新生群、运营商、朋友推荐、其他”，并显示“＋ 添加来源”；未向生产字典写入测试值。
  - 团队数据 7 个指标实测 `top` 坐标一致，保持一行；当前视口下正确产生横向滚动。
  - 客户详情实机显示“共享跟进”，抽屉返回 14 个合规候选；只打开后取消，未保存、未改变真实客户授权。
  - 新后端和网关重建后，原管理员 Session 继续有效。
- 部署后后端 `ERROR/Exception/OutOfMemory` 计数：0。
- 部署后 gateway 5xx 计数：0。
- 后端重启后原管理员 Session 仍有效，页面没有退回登录页。

## 5. 生产部署现状

- 生产服务器：`127.0.0.1`
- 内网访问：`http://127.0.0.1:8080/`
- 公网 FRP 访问：`http://101.43.22.42:38080/`
- 服务器部署目录：`/srv/yingge-cloud/app`
- 当前容器：
  - `app-backend-1`：healthy。
  - `app-gateway-1`：8080 端口正常。
  - `app-postgres-1`：healthy，本次没有重建、没有替换、没有删除数据卷。
- 部署后 readiness：内外网均为 `UP`。
- 当前前端资源：
  - `assets/index-DgmIhgZ-.js`
  - `assets/index-DLSm-dp_.css`
- 当前生产镜像 ID：
  - backend：`sha256:5e634de4893aff5621b4a085ed77d0c8b7b5a3c46c1241fba047567c3caaea6f`
  - gateway：`sha256:3462981d2dd4479c2b68bb827ce4361208a4cfbe2a0390e86ff50d647b6741ae`
- 当前数据库 Flyway 版本：V12；V11、V12 均一次迁移成功。
- 迁移后生产库有 92 个客户，其中 55 个客户使用来源字典，55 个引用全部可解析；启用来源无重名。
- `uq_dictionary_source_label` 与 `uq_customer_member_access_grant` 均已存在。

## 6. 备份与回滚点

- PostgreSQL 备份：`/srv/yingge-cloud/backups/daily/yingge-20260721T025412Z.dump`
- SHA-256 文件：`/srv/yingge-cloud/backups/daily/yingge-20260721T025412Z.dump.sha256`
- `sha256sum -c`：通过。
- `pg_restore --list`：通过，可读取完整 TOC。
- 发布前文件备份：`/srv/yingge-cloud/releases/20260721T0252Z-pre-management-sheets/app-files.tgz`
- 回滚镜像标签：
  - `yingge-cloud-backend:rollback-20260721T0252Z`
  - `yingge-cloud-gateway:rollback-20260721T0252Z`
- 年级修复发布前 PostgreSQL 备份：`/srv/yingge-cloud/backups/daily/yingge-20260721T072700Z-pre-grade-selector.dump`
- 对应 SHA 文件：`/srv/yingge-cloud/backups/daily/yingge-20260721T072700Z-pre-grade-selector.dump.sha256`
- 该备份 `sha256sum -c` 通过，`pg_restore --list` 成功读取 243 条 TOC。
- 年级修复发布前文件备份：`/srv/yingge-cloud/releases/20260721T0728Z-pre-grade-selector/app-files.tgz`
- 年级修复回滚镜像标签：
  - `yingge-cloud-backend:rollback-20260721T0728Z`
  - `yingge-cloud-gateway:rollback-20260721T0728Z`
- 本次来源/共享功能发布前 PostgreSQL 备份：`/srv/yingge-cloud/backups/daily/yingge-20260721T095413Z-pre-source-ui.dump`
- 对应 SHA 文件：`/srv/yingge-cloud/backups/daily/yingge-20260721T095413Z-pre-source-ui.dump.sha256`
- 该备份 `sha256sum -c` 通过，`pg_restore --list` 成功读取 243 条 TOC。
- 本次发布前文件备份：`/srv/yingge-cloud/releases/20260721T134719Z-pre-collaboration/app-files.tgz`
- 本次回滚镜像标签：
  - `yingge-cloud-backend:rollback-20260721T134719Z`
  - `yingge-cloud-gateway:rollback-20260721T134719Z`
- 回滚时不得使用 `docker compose down -v`，不得删除 PostgreSQL 持久化目录。

## 7. 已清理项

- Testcontainers 临时 PostgreSQL 和 Ryuk 容器已自动清理，检查无残留。
- 临时 Maven 缓存、上传压缩包和临时测试文件已删除。
- 本次上传源码包、服务端构建目录、健康检查临时文件和本机临时 Cookie 文件已删除。
- 仓库和交接文档中没有写入 SSH 密码、FRP Token、AI Key、Session Cookie 或其他凭据。
- 数据库备份、回滚镜像、发布前文件备份已保留。

## 8. 已知但不阻断的问题

1. 任务矩阵按 membership ID 保留历史分配。生产数据中“成员乙”有一个当前启用 membership 和一个已停用的历史 membership，因此矩阵会显示两个同名列。状态映射和历史数据正确，但建议下一次将历史列显示为“成员乙（历史）”或增加成员状态提示。
2. `backend/Dockerfile` 中的 `dependency:go-offline` 会预取与本项目无关的 Oracle JDBC、AWS CRT 等大量 Maven 插件依赖，冷构建非常慢。本次使用 Maven 容器构建 JAR，再通过 `docker-compose.prebuilt.yml` / `Dockerfile.prebuilt` 构建生产镜像。后续可单独优化 Dockerfile 缓存策略。

## 9. 重要范围说明

- 用户原先提出过 32 个 E2E、21 个页面、23 组 REST 和大数据负载的完整 UAT，但后来明确取消负载测试并将任务改为聚焦生产功能开发/部署。
- 因此，不得宣称原始大型 UAT 已全部完成，也不得伪造 32/21/23 全量场景报告。
- 本次完成的是“客户管理表格 + 任务矩阵 + 团队数据表格 + 来源字典 + 客户共享跟进”的完整开发、回归、生产部署和实机验收。
- 按用户最新指示，本次没有执行负载测试。

## 10. 建议的新会话启动流程

1. 完整读取本文。
2. 读取本次任务相关的源码和测试，不要从头重写已完成功能。
3. 先执行 `git status --short -- cloud-java`，保护现有用户变更。
4. 如果需要生产变更，先做只读健康检查、备份 PostgreSQL，再仅替换相关容器。
5. 不得读取或输出 AI 密钥，不得把 SSH/FRP/AI 凭据写入仓库、日志、报告或截图。
6. 如无新的明确授权，不得访问任何服务器。

## 11. 可直接粘贴到新会话的提示词

```text
这是英格客户云 cloud-java 的连续开发任务。请持续工作到实现、测试、回归和交付全部完成，不要只给建议。

项目目录：
/Users/cmijohnson/Documents/英格营销团队app/cloud-java/

开始前必须完整读取：
/Users/cmijohnson/Documents/英格营销团队app/cloud-java/docs/handoffs/2026-07-21-production-handoff.md

只能修改 cloud-java/，禁止修改 native-mac/、顶层 src/、src-tauri/、旧 SQLite 数据库和旧桌面打包脚本。先检查 Git 状态，不要覆盖现有变更，不要未经确认全量暂存或提交。

本次新任务：
<请在这里写新需求>

如本任务需要生产部署，我明确授权本新会话访问并部署 127.0.0.1，仅限本次 cloud-java 任务；禁止删除 PostgreSQL 数据卷，禁止执行 docker compose down -v。SSH 凭据我会在新会话中单独提供，不得将密码或任何 Token 写入仓库、脚本、报告、截图或日志。未经我另行明确授权，不要访问 127.0.0.1 或其他服务器。

生产变更前必须：只读检查容器/端口/磁盘/数据库，备份 PostgreSQL 并验证 SHA-256，保留旧镜像回滚标签。发现 Bug 时保留失败证据、修根因、加回归测试，重新部署后跑相关测试和完整回归。

按当前规模测试，不执行大规模负载测试，除非我在新会话内重新明确要求。
```

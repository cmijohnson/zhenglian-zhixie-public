# 英格客户云 MCP 工具目录

本目录是强类型允许列表。服务不提供 `method/path/body`、SQL、任意 URL 请求、身份模拟或绕过 Application Service 的入口。实际 `tools/list` 会根据 Scope 和角色动态裁剪。

| 能力组 | 工具 | 写操作/确认 |
| --- | --- | --- |
| 工作台 | `yingge_dashboard_get` | 只读 |
| 客户 | `yingge_customer_search`, `yingge_customer_get`, `yingge_customer_duplicate_check`, `yingge_customer_create`, `yingge_customer_update`, `yingge_customer_transfer`, `yingge_customer_archive`, `yingge_customer_lead_review` | 创建/更新/审核幂等；转接和归档需确认 |
| 客户协作 | `yingge_customer_collaboration_get`, `yingge_customer_collaboration_replace` | 主负责人管理共享成员，统一档案和时间线 |
| 沟通 | `yingge_interaction_list`, `yingge_interaction_create` | 写入幂等，继承客户可见性 |
| 提醒 | `yingge_reminder_list`, `yingge_reminder_create`, `yingge_reminder_update` | 写入幂等 |
| 工作任务 | `yingge_task_list`, `yingge_task_matrix`, `yingge_task_create`, `yingge_task_claim`, `yingge_task_submit`, `yingge_task_assignments`, `yingge_task_review`, `yingge_task_close`, `yingge_task_delete` | 创建/领取/提交/审核幂等；关闭和删除需确认 |
| 内容业务 | `yingge_content_overview`, `yingge_content_circle_search`, `yingge_content_senders`, `yingge_content_direction_create`, `yingge_content_direction_update`, `yingge_content_submit`, `yingge_content_metrics_update`, `yingge_content_review` | 写入幂等，创建方向/审核按角色开放 |
| 日报 | `yingge_daily_report_today`, `yingge_daily_report_history`, `yingge_daily_report_submit` | 提交幂等 |
| 公告 | `yingge_announcement_list`, `yingge_announcement_create`, `yingge_announcement_update`, `yingge_announcement_archive` | 发布/编辑幂等；归档需确认 |
| 团队数据 | `yingge_team_data_overview` | 只读，服务端权限范围统计 |
| 字典 | `yingge_tag_list`, `yingge_tag_create`, `yingge_dictionary_list`, `yingge_dictionary_create`, `yingge_school_create`, `yingge_source_create` | 新增项幂等，管理权限更严 |
| 智能助手 | `yingge_ai_availability`, `yingge_ai_customer_draft`, `yingge_ai_interaction_draft`, `yingge_ai_customer_summary`, `yingge_ai_next_actions`, `yingge_ai_daily_briefing`, `yingge_ai_data_quality` | 需 `yingge.ai`，不暴露 Provider 密钥 |
| 成员管理 | `yingge_admin_members`, `yingge_admin_member_create`, `yingge_admin_member_update`, `yingge_admin_member_delete` | 仅 `ORG_ADMIN`；停用/删除需确认 |
| 团队管理 | `yingge_admin_teams`, `yingge_admin_team_create`, `yingge_admin_team_assign`, `yingge_admin_team_leader`, `yingge_admin_team_delete` | 仅 `ORG_ADMIN`；删除需确认 |
| 注册码 | `yingge_admin_registration_codes`, `yingge_admin_registration_code_create`, `yingge_admin_registration_code_disable` | 仅 `ORG_ADMIN`；停用需确认 |
| 审计 | `yingge_admin_audit` | 仅 `ORG_ADMIN`，返回脱敏信息 |
| AI 管理 | `yingge_admin_ai_settings_get`, `yingge_admin_ai_settings_update` | 仅 `ORG_ADMIN`；更新需确认，禁止密钥参数 |

## Resources

| URI | 内容 |
| --- | --- |
| `yingge://health` | 服务健康与当前 MCP 状态 |
| `yingge://openapi` | 当前 OpenAPI 文档 |
| `yingge://capabilities` | API–MCP 能力登记表 |

CSV/XLSX 导入、导出和时间线文件传输标记为 `DEFERRED_FILES`；登录、注册、修改密码和 OAuth 本身属于 `CONTROL_PLANE`，不是 Agent 业务工具。

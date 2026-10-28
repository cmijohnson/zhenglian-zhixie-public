CREATE TABLE product_releases (
    id uuid PRIMARY KEY,
    version varchar(40) NOT NULL UNIQUE,
    title varchar(200) NOT NULL,
    summary varchar(1000) NOT NULL,
    sections jsonb NOT NULL DEFAULT '{}'::jsonb,
    released_at timestamptz NOT NULL,
    published boolean NOT NULL DEFAULT true,
    current boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT product_release_sections_object CHECK (jsonb_typeof(sections) = 'object')
);
CREATE UNIQUE INDEX product_releases_single_current_idx ON product_releases (current) WHERE current;
CREATE INDEX product_releases_published_order_idx ON product_releases (released_at DESC) WHERE published;

CREATE TABLE product_release_acknowledgements (
    release_id uuid NOT NULL REFERENCES product_releases(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    acknowledged_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, user_id)
);
CREATE INDEX product_release_ack_user_idx
    ON product_release_acknowledgements (user_id, acknowledged_at DESC);

INSERT INTO product_releases (id, version, title, summary, sections, released_at, current) VALUES
('20000000-0000-0000-0000-000000000001', '2026.07.20', '英格客户云正式上线',
 '营销团队核心业务迁移到统一云端工作台。',
 '{"新增":["组织、团队与成员权限管理","客户档案、沟通时间线与跟进提醒","任务、内容、日报、公告、导入导出和智能助手"],"安全":["组织隔离、角色权限、审计日志和持久化会话"]}'::jsonb,
 '2026-07-20T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000002', '2026.07.21.1', '管理视图与任务协同升级',
 '提升管理员批量查看客户、任务和团队执行情况的效率。',
 '{"新增":["管理员客户纯表格视图","任务完成矩阵","团队数据密集表格视图"],"改进":["表格筛选、分页和窄屏横向浏览"]}'::jsonb,
 '2026-07-21T08:00:00+08:00', false),
('20000000-0000-0000-0000-000000000003', '2026.07.21.2', '客户资料与共享跟进升级',
 '统一学校、年级和来源口径，并支持多人共同维护同一客户。',
 '{"新增":["学校与客户来源字典","客户共享跟进和统一时间线"],"改进":["年级选择与快速筛选","负责人、录入人和联系方式展示"]}'::jsonb,
 '2026-07-21T14:00:00+08:00', false),
('20000000-0000-0000-0000-000000000004', '2026.07.22', '我的 Agent 与全功能 MCP',
 '员工可以授权自己的智能体在本人权限范围内调用客户云能力。',
 '{"新增":["OAuth 授权、撤销和客户端管理","按角色裁剪的客户云 MCP 工具","危险操作人工确认与幂等保护"],"安全":["组织身份服务端推导、令牌轮换和调用审计"]}'::jsonb,
 '2026-07-22T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000005', '2026.07.24', '日报日历与客户冲突检测',
 '管理端可以按日查看汇报提交情况，建档时更早发现重复客户。',
 '{"新增":["日报月历、已提交和未提交人员明细","姓名加专业的客户冲突提醒"],"改进":["姓名、专业的全半角、大小写和空白标准化"]}'::jsonb,
 '2026-07-24T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000006', '2026.07.27', '团队数据统计口径修复',
 '统计卡片、客户明细和分页现在使用一致的筛选条件。',
 '{"改进":["团队、成员、年级和客户搜索联动","超过一百位客户时使用服务端真实总数"],"修复":["统计卡片与明细数量不一致","刷新时间和筛选状态不同步"]}'::jsonb,
 '2026-07-27T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000007', '2026.08.04', '可配置每日绩效中心',
 '建立可审核、可追溯的每日绩效申报与积分体系。',
 '{"新增":["绩效参与人员、规则版本和每日基线","交流申报、活动、后勤分、纠错和结算","个人趋势、团队排行和管理汇总"],"改进":["日报深度沟通与审核结果自动联动"]}'::jsonb,
 '2026-08-04T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000008', '2026.08.14.1', '重点客户协作与咨询交接',
 'A 类客户支持双人跟进，咨询从简单转发升级为带画像交接。',
 '{"新增":["A 类协作招募与并发领取","咨询交接卡、预约和四十八小时结果闭环","客户高中与生源地资料补全"],"改进":["三天未跟进提醒和咨询失败原因管理"]}'::jsonb,
 '2026-08-14T08:00:00+08:00', false),
('20000000-0000-0000-0000-000000000009', '2026.08.14.2', '领导大屏与客户智能分析',
 '管理数据展示和单客户分析能力全面升级。',
 '{"新增":["领导大屏风险字幕、学校对比和休眠场景","客户画像与时间线 RAG 智能分析","分析版本、证据引用和后台任务"],"改进":["前端视觉层级、布局稳定性和窄屏适配"]}'::jsonb,
 '2026-08-14T14:00:00+08:00', false),
('20000000-0000-0000-0000-000000000010', '2026.08.15', 'QQ 对话工作台',
 '销售可以在客户云内搜索并预览自己的 QQ 会话，再确认写入客户时间线。',
 '{"新增":["好友、群聊和临时会话搜索","聊天消息、媒体、任务和导出预览","AI 草稿、客户匹配和 CRM 入档"],"安全":["独立 QQ 实例、受控网关、原文加密和连接自愈"]}'::jsonb,
 '2026-08-15T00:00:00+08:00', false),
('20000000-0000-0000-0000-000000000011', '2026.08.18.1', '市场治理与重要提醒',
 '客户分级、归属流转、业务周期和重要通知形成完整闭环。',
 '{"新增":["结构化 A/B/C 分级、抽查和异常复核","客户公共池、临时认领、A 类二线和业务周期","站内通知、企业微信通知和产品更新中心"],"改进":["A 类客户三天未跟进时每次登录全屏提醒","报价锁定、交叉单筛选和不可覆盖的操作留痕"],"修复":["公共池来源解析和期限日期兼容","PostgreSQL 批量提醒参数类型问题"],"安全":["认领前客户信息脱敏、Webhook 地址加密保存"]}'::jsonb,
 '2026-08-18T00:00:00+08:00', true);

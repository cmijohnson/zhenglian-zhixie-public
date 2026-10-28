UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000023',
    '2026.08.27.1',
    'Android 0.2.1 业务可靠性修复',
    '修复客户分级、时间线、AI 洞察、成单申请、客户转接、每日小结和工作台问候等移动端阻断问题，并提供持续更新提醒。',
    '{"改进":["沟通表单默认使用服务器接收时间，支持明确选择历史发生时间并显示服务器校准时间","所有拥有客户编辑权的成员均可发起负责人转接，候选人覆盖组织内启用且可维护客户的成员","每日小结使用独立长期开放配置，不再受内容任务周期影响","工作台根据北京时间显示五个时段的主问候，并提供带固定回退的智能副句"],"修复":["修复轻微手机时钟偏差导致时间线被拒绝，120 秒内偏差由服务端安全归一化","修复移动端客户分级缺项静默禁用、成单申请失败原因不明确和成功状态未及时刷新","新增稳定的移动客户洞察 JSON 接口，避免上游流连接异常结束时丢弃已生成结果","修复无团队协作者无法看到转接候选人、日报周期过期后无法提交等问题"],"安全":["A/B/C 继续要求结构化事实依据，A 类信号和跨组织、停用、只读成员限制保持不变","成单继续采用无金额、无业务周期的极简申请与管理员审核流程，写操作保留幂等和审计","旧版保持兼容但持续提示更新，安装包继续执行 SHA-256 与应用签名校验"]}'::jsonb,
    '2026-08-27T00:20:00+08:00',
    true,
    true
);

INSERT INTO mobile_app_releases(
    id,
    platform,
    version_name,
    version_code,
    minimum_supported_version_code,
    mandatory,
    notes,
    apk_path,
    apk_size,
    apk_sha256,
    manifest_signature,
    published,
    released_at
) VALUES (
    'e9450b94-cf49-4751-b0e7-686b5cc09291',
    'ANDROID',
    '0.2.1',
    6,
    1,
    false,
    '修复 A/B/C 分级、时间线时钟偏差、AI 客户洞察、极简成单、协作者转接、每日小结和分时问候；旧版持续显示更新提醒。',
    'android/yingge-cloud-0.2.1.apk',
    5205995,
    'fa2035a7fe706b410f0a8a2a542e4ca8a8c8af60f8050f00b1523b40d7c56265',
    null,
    true,
    '2026-08-27T00:20:00+08:00'
)
ON CONFLICT (platform, version_code) DO UPDATE SET
    version_name = excluded.version_name,
    minimum_supported_version_code = excluded.minimum_supported_version_code,
    mandatory = excluded.mandatory,
    notes = excluded.notes,
    apk_path = excluded.apk_path,
    apk_size = excluded.apk_size,
    apk_sha256 = excluded.apk_sha256,
    manifest_signature = excluded.manifest_signature,
    published = excluded.published,
    released_at = excluded.released_at;

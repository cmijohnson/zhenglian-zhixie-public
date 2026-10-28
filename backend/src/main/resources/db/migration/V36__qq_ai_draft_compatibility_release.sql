UPDATE product_releases SET current = false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000013',
    '2026.08.18.3',
    'QQ 智能总结兼容性修复',
    '提升 QQ 会话智能草稿对不同兼容模型输出格式的稳定性。',
    '{"改进":["保留模型返回的有效草稿字段并标记需要人工核实的缺项","无法识别的建议跟进时间自动留空，不再使整份草稿失败"],"修复":["兼容模型思考段、Markdown 围栏和 result/draft/data 外层包装","兼容 customerQuotes 与 uncertainties 的单值或空值格式"],"安全":["仍拒绝无法识别的非 JSON 内容","不记录模型原文、不自动重试、不额外消耗 Token"]}'::jsonb,
    '2026-08-18T16:40:00+08:00',
    true,
    true
);

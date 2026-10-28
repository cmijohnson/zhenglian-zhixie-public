UPDATE product_releases SET current = false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000014',
    '2026.08.18.4',
    'HTTP 写操作兼容性修复',
    '修复 HTTP 环境下 QQ 入档确认等幂等写操作无法提交的问题。',
    '{"改进":["统一使用浏览器安全随机源生成写操作幂等键","兼容不提供 crypto.randomUUID 的 HTTP 内网浏览器环境"],"修复":["修复 QQ 智能草稿生成后确认写入未发出请求的问题","同步修复公共池认领和新建业务周期的同类隐患"],"安全":["仍使用 crypto.getRandomValues 生成随机键，不使用可预测的时间戳或普通伪随机数"]}'::jsonb,
    '2026-08-18T23:25:00+08:00',
    true,
    true
);

UPDATE product_releases SET current = false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000012',
    '2026.08.18.2',
    '客户转接与 QQ 入档修复',
    '修复客户转接候选人未及时刷新和 QQ 对话原文目录不可写问题。',
    '{"改进":["每次打开客户转接窗口时重新加载当前可用接收人","转接候选加载期间显示明确状态并防止误提交"],"修复":["修复部分页面进入后接收人列表为空","修复 QQ 对话原文加密保存目录权限异常"],"安全":["QQ 原文目录仅允许应用账号访问","保存失败日志不记录消息正文、文件路径或密钥"]}'::jsonb,
    '2026-08-18T16:00:00+08:00',
    true,
    true
);

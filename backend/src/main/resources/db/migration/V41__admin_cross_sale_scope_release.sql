UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000018',
    '2026.08.19.4',
    '管理员交叉单全组织查询修复',
    '修复同时具备管理员和团队长角色时，交叉单清单被错误限制到管理团队的问题。',
    '{"修复":["组织管理员查看交叉单时不再叠加团队长范围限制","交叉单、全部客户及协作者筛选恢复全组织数据"],"改进":["保留纯团队长仅查看所管理团队的权限边界"],"安全":["查询仍按当前登录组织隔离，不接受客户端伪造组织参数"]}'::jsonb,
    '2026-08-19T17:47:00+08:00',
    true,
    true
);

UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000024',
    '2026.08.28.1',
    '禅道只读账号绑定契约',
    '为禅道 22.5 提供个人 OAuth PKCE 绑定、稳定身份读取、经营看板读取和独立管理只读权限，不开放写操作。',
    '{"新增":["新增 yingge_identity_me，返回稳定用户、成员、组织、角色和团队身份","新增 yingge_leadership_dashboard_get，复用现有经营看板权限裁剪","新增 yingge.admin.read，只允许组织管理员授权"],"兼容":["旧 yingge.admin 继续访问管理只读工具，管理写工具仍要求 yingge.admin","现有 yingge.read、yingge.write、yingge.ai 和 yingge.admin Agent 保持兼容"],"安全":["禅道客户端只申请 yingge.read 与 yingge.admin.read，普通成员只能获得 yingge.read","不返回密码、密码哈希或客户端密钥，不新增万能 HTTP 代理","组织、团队、私有客户和停用成员规则继续由英格云服务端执行"]}'::jsonb,
    '2026-08-28T16:30:00+08:00',
    true,
    true
);

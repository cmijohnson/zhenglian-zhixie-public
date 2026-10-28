INSERT INTO roles (id, organization_id, code, name, is_system) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'ORG_ADMIN', '组织管理员', true),
    ('00000000-0000-0000-0000-000000000002', NULL, 'TEAM_MANAGER', '团队主管', true),
    ('00000000-0000-0000-0000-000000000003', NULL, 'MEMBER', '成员', true),
    ('00000000-0000-0000-0000-000000000004', NULL, 'VIEWER', '只读成员', true);

INSERT INTO permissions (id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000001', 'customer.read', '读取权限范围内客户'),
    ('10000000-0000-0000-0000-000000000002', 'customer.write', '创建和修改权限范围内客户'),
    ('10000000-0000-0000-0000-000000000003', 'customer.export', '批量导出客户'),
    ('10000000-0000-0000-0000-000000000004', 'customer.import', '批量导入客户'),
    ('10000000-0000-0000-0000-000000000005', 'organization.manage', '管理组织成员和团队'),
    ('10000000-0000-0000-0000-000000000006', 'audit.read', '读取审计日志'),
    ('10000000-0000-0000-0000-000000000007', 'assistant.use', '使用智能助手'),
    ('10000000-0000-0000-0000-000000000008', 'migration.run', '执行旧数据迁移');

INSERT INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000001'::uuid, id FROM permissions;

INSERT INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000002'::uuid, id FROM permissions
WHERE code IN ('customer.read', 'customer.write', 'customer.export', 'customer.import', 'assistant.use');

INSERT INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000003'::uuid, id FROM permissions
WHERE code IN ('customer.read', 'customer.write', 'assistant.use');

INSERT INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000004'::uuid, id FROM permissions
WHERE code = 'customer.read';


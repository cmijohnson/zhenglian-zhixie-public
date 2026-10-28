UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000019',
    '2026.08.19.5',
    '交叉单筛选数据导出',
    '市场治理台支持按页面当前筛选条件导出交叉单 XLSX，并继续执行角色范围裁剪和导出审计。',
    '{"新增":["按页面当前筛选条件导出交叉单 XLSX","导出客户、团队、主负责人、协作者、最近业务周期、报价与结果字段"],"改进":["Excel 固定表头、列筛选和适配列宽","导出与页面查询共享同一筛选和排序口径"],"安全":["组织管理员按全组织、团队长按所管团队裁剪导出结果","导出记录行数与筛选状态审计"]}'::jsonb,
    '2026-08-19T18:25:00+08:00',
    true,
    true
);

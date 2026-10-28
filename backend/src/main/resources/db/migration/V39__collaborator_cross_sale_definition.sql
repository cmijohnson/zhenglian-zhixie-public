UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000016',
    '2026.08.19.2',
    '交叉单查询口径修复',
    '交叉单现在直接按客户档案中的有效协作者判断，没有业务周期的共享客户也能被查询。',
    '{"改进":["交叉单以有效协作者为统一判定依据","交叉单清单直接查询客户并附带最近业务周期"],"修复":["修复共享客户因未建立业务周期而无法被交叉单查询发现","高意向清单的交叉单筛选同步统一口径"],"安全":["继续按组织和团队范围裁剪查询结果"]}'::jsonb,
    '2026-08-19T18:00:00+08:00',
    true,
    true
);

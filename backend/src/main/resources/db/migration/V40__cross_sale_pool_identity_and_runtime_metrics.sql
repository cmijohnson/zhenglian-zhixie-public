UPDATE customer_business_cycles cycle
SET cross_sale=EXISTS (
    SELECT 1
    FROM customer_access_grants grant_row
    JOIN memberships collaborator_membership
      ON collaborator_membership.id=grant_row.grantee_membership_id
     AND collaborator_membership.organization_id=grant_row.organization_id
     AND collaborator_membership.status='ACTIVE'
    JOIN user_accounts collaborator_account
      ON collaborator_account.id=collaborator_membership.user_id
     AND collaborator_account.enabled=true
    JOIN customers customer
      ON customer.id=grant_row.customer_id
     AND customer.organization_id=grant_row.organization_id
    WHERE grant_row.organization_id=cycle.organization_id
      AND grant_row.customer_id=cycle.customer_id
      AND collaborator_membership.id<>customer.owner_membership_id
);

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000017',
    '2026.08.19.3',
    '协作口径、公共池与智能分析运行状态',
    '交叉单统一按任一有效协作者判断，公共池展示姓名和简要概述，客户智能分析增加服务器压力看板。',
    '{"改进":["任一有效人员级协作者均统一判定为交叉单","公共池和A类二线招募展示客户姓名与简要概述","客户智能分析管理页每15秒显示CPU、内存、JVM、数据库连接池和分析队列压力"],"修复":["修复只读协作或二线协作未被交叉单查询识别的问题","修复业务周期、高意向清单与交叉单管理清单口径不一致"],"安全":["公共池仍不提前展示手机号、微信、QQ和原负责人","运行状态只返回聚合指标，不返回主机、密钥、SQL或客户数据"]}'::jsonb,
    '2026-08-19T20:30:00+08:00',
    true,
    true
);

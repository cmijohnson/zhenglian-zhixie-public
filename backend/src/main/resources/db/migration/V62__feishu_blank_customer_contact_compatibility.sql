ALTER TABLE customers
    ADD COLUMN contact_completion_status varchar(16) NOT NULL DEFAULT 'COMPLETE';

ALTER TABLE customers
    ADD CONSTRAINT customer_contact_completion_status_check
        CHECK (contact_completion_status IN ('PENDING','COMPLETE'));

ALTER TABLE customers DROP CONSTRAINT ck_customer_contact;
ALTER TABLE customers ADD CONSTRAINT ck_customer_contact CHECK (
    nullif(trim(coalesce(phone, '')), '') IS NOT NULL OR
    nullif(trim(coalesce(wechat_id, '')), '') IS NOT NULL OR
    nullif(trim(coalesce(qq_id, '')), '') IS NOT NULL OR
    (
        contact_completion_status='PENDING'
        AND source_channel_legacy='飞书临时建档'
        AND verification_status='未核实'
    )
);

UPDATE product_releases SET current=false WHERE current;
INSERT INTO product_releases (
    id,version,title,summary,sections,released_at,published,current
) VALUES (
    '20000000-0000-0000-0000-000000000032',
    '2026.09.07.1',
    '飞书空白客户快速建档修复',
    '修复成单机器人无法创建待补全联系方式客户，以及创建失败后群内无明确回复的问题。',
    '{"修复":["飞书 /new 现在可以创建联系方式待补全的正式客户","快速建档失败后机器人会返回明确错误，不再静默无响应"],"安全":["仅飞书快速建档且未核实的客户可暂缺联系方式","普通客户建档仍必须提供手机号、微信号或 QQ 号"]}'::jsonb,
    '2026-09-07T01:00:00+08:00',true,true
);

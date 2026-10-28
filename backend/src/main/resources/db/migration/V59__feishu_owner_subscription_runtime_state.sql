ALTER TABLE feishu_bitable_integrations
    ADD COLUMN event_subscription_authorized_at timestamptz;

UPDATE feishu_bitable_integrations integration
SET event_subscription_authorized_at = authorized.authorized_at,
    event_state = CASE
        WHEN integration.active AND integration.inbound_sync_enabled AND integration.schema_state = 'READY'
            THEN 'CONNECTING'
        ELSE integration.event_state
    END,
    last_event_error_code = CASE
        WHEN integration.active AND integration.inbound_sync_enabled AND integration.schema_state = 'READY'
            THEN NULL
        ELSE integration.last_event_error_code
    END,
    updated_at = now(),
    version = integration.version + 1
FROM (
    SELECT entity_id, max(created_at) AS authorized_at
    FROM audit_logs
    WHERE action = 'FEISHU_EVENT_SUBSCRIPTION_AUTHORIZED'
      AND outcome = 'SUCCESS'
    GROUP BY entity_id
) authorized
WHERE integration.id::text = authorized.entity_id
  AND integration.event_subscription_authorized_at IS NULL;

UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000029',
    '2026.09.05.1',
    '飞书双向事件连接修复',
    '修复 Base 所有者完成订阅授权后双向事件长连接仍无法启动的问题。',
    '{"修复":["修复所有者授权完成后仍使用应用凭证重复校验文件订阅的问题","恢复飞书记录变更事件进入英格云的受控长连接"],"改进":["目标应用或 Base 变更后自动要求重新授权"],"安全":["只保存授权完成时间，不保存飞书用户访问令牌","未授权目标继续失败关闭，不绕过飞书权限边界"]}'::jsonb,
    '2026-09-05T09:00:00+08:00',
    true,
    true
);

CREATE TABLE feishu_bitable_integrations (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    app_id varchar(128) NOT NULL,
    encrypted_app_secret bytea NOT NULL,
    base_app_token varchar(256) NOT NULL,
    table_id varchar(128),
    table_name varchar(160) NOT NULL DEFAULT '英格云客户档案',
    active boolean NOT NULL DEFAULT false,
    schema_state varchar(24) NOT NULL DEFAULT 'NOT_PROVISIONED',
    risk_acknowledged_at timestamptz NOT NULL,
    created_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    updated_by_membership_id uuid NOT NULL REFERENCES memberships(id),
    last_tested_at timestamptz,
    last_success_at timestamptz,
    last_error_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT feishu_schema_state_check CHECK (
        schema_state IN ('NOT_PROVISIONED','READY','MISMATCH','UNAVAILABLE')
    )
);

CREATE TABLE feishu_bitable_fields (
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    logical_key varchar(80) NOT NULL,
    field_id varchar(128) NOT NULL,
    expected_name varchar(160) NOT NULL,
    expected_type integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (integration_id, logical_key),
    CONSTRAINT feishu_field_id_unique UNIQUE (integration_id, field_id)
);

CREATE TABLE feishu_bitable_record_links (
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    record_id varchar(128) NOT NULL,
    source_version bigint NOT NULL DEFAULT 0,
    snapshot_hash varchar(64) NOT NULL,
    synced_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (integration_id, customer_id),
    CONSTRAINT feishu_record_id_unique UNIQUE (integration_id, record_id)
);

CREATE TABLE feishu_bitable_sync_runs (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    run_type varchar(24) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    total_count integer NOT NULL DEFAULT 0,
    success_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    skipped_count integer NOT NULL DEFAULT 0,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    started_at timestamptz,
    completed_at timestamptz,
    last_error_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_run_type_check CHECK (run_type IN ('INITIAL','HOURLY','MANUAL','RECONCILIATION')),
    CONSTRAINT feishu_run_status_check CHECK (status IN ('PENDING','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED'))
);
CREATE INDEX feishu_sync_runs_org_time_idx
    ON feishu_bitable_sync_runs (organization_id, created_at DESC);
CREATE UNIQUE INDEX feishu_sync_runs_one_active_idx
    ON feishu_bitable_sync_runs (integration_id)
    WHERE status IN ('PENDING','RUNNING');

CREATE TABLE feishu_bitable_sync_queue (
    id uuid PRIMARY KEY,
    integration_id uuid NOT NULL REFERENCES feishu_bitable_integrations(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    run_id uuid REFERENCES feishu_bitable_sync_runs(id) ON DELETE SET NULL,
    generation bigint NOT NULL DEFAULT 1,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feishu_queue_status_check CHECK (status IN ('PENDING','SENDING','RETRYING','DELIVERED','FAILED')),
    CONSTRAINT feishu_queue_customer_unique UNIQUE (integration_id, customer_id)
);
CREATE INDEX feishu_sync_queue_ready_idx
    ON feishu_bitable_sync_queue (integration_id, status, next_attempt_at, created_at);
CREATE INDEX feishu_sync_queue_run_idx
    ON feishu_bitable_sync_queue (run_id, status, next_attempt_at);

CREATE OR REPLACE FUNCTION enqueue_feishu_customer_sync(
    target_organization_id uuid,
    target_customer_id uuid
) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO feishu_bitable_sync_queue (
        id, integration_id, organization_id, customer_id, generation, status,
        attempt_count, next_attempt_at, created_at, updated_at
    )
    SELECT gen_random_uuid(), integration.id, target_organization_id, target_customer_id,
           1, 'PENDING', 0, now(), now(), now()
    FROM feishu_bitable_integrations integration
    WHERE integration.organization_id=target_organization_id
      AND integration.active=true
      AND integration.schema_state='READY'
      AND EXISTS (
          SELECT 1 FROM customers customer
          WHERE customer.id=target_customer_id AND customer.organization_id=target_organization_id
      )
    ON CONFLICT (integration_id, customer_id) DO UPDATE SET
        generation=feishu_bitable_sync_queue.generation+1,
        run_id=null,
        status='PENDING',
        attempt_count=0,
        next_attempt_at=now(),
        last_error_code=null,
        updated_at=now();
END;
$$;

CREATE OR REPLACE FUNCTION queue_feishu_customer_row_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_org uuid;
    target_customer uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        target_org := OLD.organization_id;
        target_customer := OLD.id;
    ELSE
        target_org := NEW.organization_id;
        target_customer := NEW.id;
    END IF;
    PERFORM enqueue_feishu_customer_sync(target_org, target_customer);
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION queue_feishu_related_customer_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_org uuid;
    target_customer uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        target_customer := OLD.customer_id;
    ELSE
        target_customer := NEW.customer_id;
    END IF;
    SELECT customer.organization_id INTO target_org FROM customers customer WHERE customer.id=target_customer;
    IF target_org IS NOT NULL THEN
        PERFORM enqueue_feishu_customer_sync(target_org, target_customer);
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER customers_feishu_sync_queue
AFTER INSERT OR UPDATE ON customers
FOR EACH ROW EXECUTE FUNCTION queue_feishu_customer_row_change();

CREATE TRIGGER customer_access_grants_feishu_sync_queue
AFTER INSERT OR UPDATE OR DELETE ON customer_access_grants
FOR EACH ROW EXECUTE FUNCTION queue_feishu_related_customer_change();

CREATE TRIGGER customer_classifications_feishu_sync_queue
AFTER INSERT ON customer_classifications
FOR EACH ROW EXECUTE FUNCTION queue_feishu_related_customer_change();

CREATE TRIGGER customer_deal_requests_feishu_sync_queue
AFTER INSERT OR UPDATE ON customer_deal_requests
FOR EACH ROW EXECUTE FUNCTION queue_feishu_related_customer_change();

CREATE TRIGGER customer_tags_feishu_sync_queue
AFTER INSERT OR DELETE ON customer_tags
FOR EACH ROW EXECUTE FUNCTION queue_feishu_related_customer_change();

CREATE TRIGGER customer_focus_directions_feishu_sync_queue
AFTER INSERT OR DELETE ON customer_focus_directions
FOR EACH ROW EXECUTE FUNCTION queue_feishu_related_customer_change();

UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000025',
    '2026.09.02.1',
    '飞书多维表格客户自动同步',
    '组织管理员可将英格云客户档案单向同步至指定飞书多维表格，支持自动建表、小时增量、夜间校验和失败重试。',
    '{"新增":["新增飞书企业自建应用及目标 Base 配置，系统自动创建英格云客户档案表和标准字段","新增首次同步预览、10 条预检、小时增量同步、夜间一致性校验和人工重试","同步客户分级、负责人、协作者、成单、归档和完整联系方式等已确认字段"],"改进":["客户连续修改合并为最新版本，每批最多 100 条，避免重复记录","字段改名继续按字段 ID 同步，字段删除或类型变化时自动暂停并提示修复","归档客户只更新状态，不删除飞书记录；飞书自定义字段不受影响"],"安全":["App Secret 使用服务端 AES-256-GCM 加密且永不回显，租户 Token 只在内存缓存","仅组织管理员可配置，所有组织身份从登录会话推导，控制面不向 MCP 开放","飞书请求仅允许 https://open.feishu.cn，日志和审计不记录密钥、Token 或完整客户快照"]}'::jsonb,
    '2026-09-02T00:10:00+08:00',
    true,
    true
);

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE customer_intelligence_settings (
    organization_id uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    auto_profile_analysis boolean NOT NULL DEFAULT true,
    auto_timeline_analysis boolean NOT NULL DEFAULT true,
    embedding_base_url varchar(500) NOT NULL DEFAULT 'http://embedding:8080',
    embedding_model varchar(160) NOT NULL DEFAULT 'BAAI/bge-m3',
    recent_interaction_limit integer NOT NULL DEFAULT 10,
    semantic_chunk_limit integer NOT NULL DEFAULT 12,
    backfill_enabled boolean NOT NULL DEFAULT true,
    backfill_paused boolean NOT NULL DEFAULT false,
    nightly_rate_per_minute integer NOT NULL DEFAULT 10,
    updated_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT intelligence_recent_limit_check CHECK (recent_interaction_limit BETWEEN 1 AND 30),
    CONSTRAINT intelligence_semantic_limit_check CHECK (semantic_chunk_limit BETWEEN 1 AND 30),
    CONSTRAINT intelligence_backfill_rate_check CHECK (nightly_rate_per_minute BETWEEN 1 AND 10)
);

INSERT INTO customer_intelligence_settings (organization_id)
SELECT id FROM organizations ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION seed_customer_intelligence_settings_for_organization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO customer_intelligence_settings (organization_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER organizations_seed_customer_intelligence_settings
AFTER INSERT ON organizations FOR EACH ROW EXECUTE FUNCTION seed_customer_intelligence_settings_for_organization();

CREATE TABLE customer_knowledge_chunks (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    source_type varchar(24) NOT NULL,
    source_key varchar(160) NOT NULL,
    source_at timestamptz,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_hash char(64) NOT NULL,
    embedding vector(1024),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_chunk_source_check CHECK (source_type IN ('PROFILE','INTERACTION','CONSULTATION','QQ_SUMMARY')),
    CONSTRAINT customer_chunk_unique UNIQUE (organization_id, customer_id, source_type, source_key, chunk_index, content_hash)
);
CREATE INDEX customer_chunks_customer_time_idx
    ON customer_knowledge_chunks (organization_id, customer_id, source_at DESC NULLS LAST);
CREATE INDEX customer_chunks_embedding_hnsw_idx
    ON customer_knowledge_chunks USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;

CREATE TABLE customer_analysis_jobs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    trigger_type varchar(32) NOT NULL,
    triggered_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    rerun_requested boolean NOT NULL DEFAULT false,
    available_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0,
    locked_at timestamptz,
    completed_at timestamptz,
    last_error_code varchar(80),
    last_error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_analysis_trigger_check CHECK (trigger_type IN ('CUSTOMER_CREATED','PROFILE_UPDATED','TIMELINE_UPDATED','MANUAL','BACKFILL')),
    CONSTRAINT customer_analysis_status_check CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT customer_analysis_attempts_check CHECK (attempts BETWEEN 0 AND 10)
);
CREATE UNIQUE INDEX uq_active_customer_analysis_job
    ON customer_analysis_jobs (organization_id, customer_id)
    WHERE status IN ('PENDING','RUNNING');
CREATE INDEX customer_analysis_job_queue_idx
    ON customer_analysis_jobs (status, available_at, created_at);

CREATE TABLE customer_analysis_versions (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    version_number integer NOT NULL,
    trigger_type varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    structured_result jsonb,
    narrative text,
    evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    provider varchar(80),
    model varchar(160),
    source_watermark timestamptz,
    created_by_membership_id uuid REFERENCES memberships(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_analysis_version_status_check CHECK (status IN ('SUCCEEDED','FAILED')),
    CONSTRAINT customer_analysis_version_unique UNIQUE (organization_id, customer_id, version_number)
);
CREATE INDEX customer_analysis_versions_latest_idx
    ON customer_analysis_versions (organization_id, customer_id, version_number DESC);

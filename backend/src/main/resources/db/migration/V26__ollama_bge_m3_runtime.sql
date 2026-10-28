ALTER TABLE customer_intelligence_settings
    ALTER COLUMN embedding_model SET DEFAULT 'bge-m3';

UPDATE customer_intelligence_settings
SET embedding_model = 'bge-m3', updated_at = now(), version = version + 1
WHERE embedding_model = 'BAAI/bge-m3';

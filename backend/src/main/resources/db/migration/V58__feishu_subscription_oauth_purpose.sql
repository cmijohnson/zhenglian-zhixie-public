ALTER TABLE feishu_oauth_intents
    DROP CONSTRAINT IF EXISTS feishu_oauth_intent_purpose_check;

ALTER TABLE feishu_oauth_intents
    ADD CONSTRAINT feishu_oauth_intent_purpose_check
    CHECK (purpose IN ('LOGIN', 'BIND', 'SUBSCRIBE'));

ALTER TABLE qq_imports
    ADD COLUMN chat_type integer NOT NULL DEFAULT 1,
    ADD COLUMN peer_uid varchar(256),
    ADD COLUMN session_name varchar(240),
    ADD COLUMN target_sender_uid varchar(256),
    ADD COLUMN target_sender_qq varchar(120),
    ADD COLUMN group_context_confirmed boolean NOT NULL DEFAULT false;

UPDATE qq_imports SET peer_uid = peer_qq WHERE peer_uid IS NULL;
ALTER TABLE qq_imports ALTER COLUMN peer_uid SET NOT NULL;
ALTER TABLE qq_imports
    ADD CONSTRAINT qq_import_chat_type_check CHECK (chat_type BETWEEN 1 AND 999),
    ADD CONSTRAINT qq_import_group_context_check CHECK (
        chat_type <> 2 OR (
            group_context_confirmed = true
            AND target_sender_uid IS NOT NULL
            AND target_sender_qq IS NOT NULL
        )
    );

ALTER TABLE qq_imports DROP CONSTRAINT qq_import_message_unique;
ALTER TABLE qq_imports ADD CONSTRAINT qq_import_message_unique
    UNIQUE (organization_id, connection_id, chat_type, peer_uid, range_start, range_end, message_hash, customer_id);

WITH source_default AS (
    SELECT organization.id AS organization_id
    FROM organizations organization
)
INSERT INTO dictionary_items (id, organization_id, type, code, label, sort_order)
SELECT md5(source_default.organization_id::text || ':source:DEFAULT_QQ')::uuid,
       source_default.organization_id, 'SOURCE_CHANNEL', 'DEFAULT_QQ', 'QQ', 7
FROM source_default
WHERE NOT EXISTS (
    SELECT 1 FROM dictionary_items existing
    WHERE existing.organization_id = source_default.organization_id
      AND existing.type = 'SOURCE_CHANNEL'
      AND existing.active = true
      AND lower(existing.label) = lower('QQ')
)
ON CONFLICT (organization_id, type, code) DO UPDATE
SET label = excluded.label, active = true, updated_at = now();

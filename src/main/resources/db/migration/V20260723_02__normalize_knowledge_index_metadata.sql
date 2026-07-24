-- ai_index_version stores the physical Redis index names for operations and auditing. Earlier
-- application versions persisted raw index-version hyphens even though Redis normalized them.
-- Restrict the repair to that exact legacy shape so operator-managed names are not overwritten.

UPDATE ai_index_version
SET vector_index_name = CASE
        WHEN vector_index_name = CONCAT('ai_kb_', code)
            THEN CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
        ELSE vector_index_name
    END,
    lexical_index_name = CASE
        WHEN lexical_index_name = CONCAT('ai_kb_', code)
            THEN CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
        ELSE lexical_index_name
    END
WHERE vector_index_name = CONCAT('ai_kb_', code)
   OR lexical_index_name = CONCAT('ai_kb_', code);

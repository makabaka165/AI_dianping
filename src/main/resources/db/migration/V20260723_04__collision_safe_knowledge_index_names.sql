-- v2 physical names use the first 128 bits of SHA-256(index_version). The Java naming module
-- uses the same UTF-8/hash/prefix formula. v1 names remain derivable for rollback and fallback.
-- Flyway cannot inspect Redis, so a READY/active row is not renamed here: changing its metadata
-- before the v2 FT.CREATE succeeds would make the database claim an index that does not exist.
-- BUILDING rows are safe to switch because the index worker provisions the v2 index before use.

DROP PROCEDURE IF EXISTS `hmdp_assert_unique_ai_index_v2_names`;
DELIMITER $$
CREATE PROCEDURE `hmdp_assert_unique_ai_index_v2_names`()
BEGIN
    DECLARE collisions BIGINT DEFAULT 0;
    DECLARE duplicate_codes BIGINT DEFAULT 0;
    DECLARE collision_message VARCHAR(255);

    SELECT COUNT(*)
    INTO duplicate_codes
    FROM (
        SELECT code
        FROM ai_index_version
        GROUP BY code
        HAVING COUNT(*) > 1
    ) duplicate_index_codes;

    SELECT COUNT(*)
    INTO collisions
    FROM (
        SELECT physical_name
        FROM (
            SELECT id,
                CASE
                    WHEN vector_index_name = CONCAT('ai_kb_', code)
                      OR vector_index_name = CONCAT(
                          'ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
                        THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
                    ELSE vector_index_name
                END AS physical_name
            FROM ai_index_version
            UNION ALL
            SELECT id,
                CASE
                    WHEN lexical_index_name = CONCAT('ai_kb_', code)
                      OR lexical_index_name = CONCAT(
                          'ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
                        THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
                    ELSE lexical_index_name
                END AS physical_name
            FROM ai_index_version
        ) projected_names
        GROUP BY physical_name
        HAVING COUNT(DISTINCT id) > 1
    ) colliding_names;

    IF duplicate_codes > 0 OR collisions > 0 THEN
        SET collision_message = CONCAT(
            'AI Redis v2 identity collisions: duplicate_codes=', duplicate_codes,
            ', projected_name_collisions=', collisions,
            '; make index_version values globally unique before migration'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = collision_message;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_assert_unique_ai_index_v2_names`();
DROP PROCEDURE IF EXISTS `hmdp_assert_unique_ai_index_v2_names`;

UPDATE ai_index_version
SET vector_index_name = CASE
        WHEN vector_index_name = CONCAT('ai_kb_', code)
          OR vector_index_name = CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
            THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
        ELSE vector_index_name
    END,
    lexical_index_name = CASE
        WHEN lexical_index_name = CONCAT('ai_kb_', code)
          OR lexical_index_name = CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
            THEN CONCAT('ai_kb_v2~', LEFT(SHA2(code, 256), 32))
        ELSE lexical_index_name
    END
WHERE (
       vector_index_name = CONCAT('ai_kb_', code)
    OR vector_index_name = CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
    OR lexical_index_name = CONCAT('ai_kb_', code)
    OR lexical_index_name = CONCAT('ai_kb_', REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_'))
      )
  AND status = 'BUILDING'
  AND active = 0;

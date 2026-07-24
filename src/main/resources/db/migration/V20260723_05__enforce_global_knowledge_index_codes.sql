-- The Redis index adapter and cleanup workers address an index by index_version/code alone.
-- Make that interface invariant explicit instead of allowing cross-tenant physical collisions.

DROP PROCEDURE IF EXISTS `hmdp_assert_global_ai_index_codes`;
DELIMITER $$
CREATE PROCEDURE `hmdp_assert_global_ai_index_codes`()
BEGIN
    DECLARE duplicate_index_codes BIGINT DEFAULT 0;
    DECLARE duplicate_version_codes BIGINT DEFAULT 0;
    DECLARE audit_message VARCHAR(255);

    SELECT COUNT(*)
    INTO duplicate_index_codes
    FROM (
        SELECT code
        FROM ai_index_version
        GROUP BY code
        HAVING COUNT(*) > 1
    ) duplicate_codes;

    SELECT COUNT(*)
    INTO duplicate_version_codes
    FROM (
        SELECT index_version
        FROM ai_knowledge_base_version
        GROUP BY index_version
        HAVING COUNT(*) > 1
    ) duplicate_versions;

    IF duplicate_index_codes > 0 OR duplicate_version_codes > 0 THEN
        SET audit_message = CONCAT(
            'Global AI index-version uniqueness audit failed: index_codes=', duplicate_index_codes,
            ', knowledge_version_codes=', duplicate_version_codes,
            '; resolve duplicate code values before migration'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = audit_message;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_assert_global_ai_index_codes`();
DROP PROCEDURE IF EXISTS `hmdp_assert_global_ai_index_codes`;

DROP PROCEDURE IF EXISTS `hmdp_add_global_ai_index_code_keys`;
DELIMITER $$
CREATE PROCEDURE `hmdp_add_global_ai_index_code_keys`()
BEGIN
    DECLARE has_unique_index_code BIGINT DEFAULT 0;
    DECLARE has_named_index_code BIGINT DEFAULT 0;
    DECLARE has_unique_version_code BIGINT DEFAULT 0;
    DECLARE has_named_version_code BIGINT DEFAULT 0;

    SELECT COUNT(*)
    INTO has_unique_index_code
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_index_version'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0
           AND COUNT(*) = 1
           AND MAX(CASE WHEN column_name = 'code' THEN 1 ELSE 0 END) = 1
    ) matching_indexes;

    SELECT COUNT(*)
    INTO has_named_index_code
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_index_version'
      AND index_name = 'uk_ai_index_version_code';

    SELECT COUNT(*)
    INTO has_unique_version_code
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_knowledge_base_version'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0
           AND COUNT(*) = 1
           AND MAX(CASE WHEN column_name = 'index_version' THEN 1 ELSE 0 END) = 1
    ) matching_version_indexes;

    SELECT COUNT(*)
    INTO has_named_version_code
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_knowledge_base_version'
      AND index_name = 'uk_ai_knowledge_base_index_version';

    IF has_unique_index_code = 0 AND has_named_index_code > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Named AI index code key exists with an incompatible definition';
    END IF;
    IF has_unique_version_code = 0 AND has_named_version_code > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Named knowledge version code key exists with an incompatible definition';
    END IF;

    IF has_unique_index_code = 0 THEN
        ALTER TABLE ai_index_version
            ADD UNIQUE KEY uk_ai_index_version_code (code);
    END IF;
    IF has_unique_version_code = 0 THEN
        ALTER TABLE ai_knowledge_base_version
            ADD UNIQUE KEY uk_ai_knowledge_base_index_version (index_version);
    END IF;
END$$
DELIMITER ;

CALL `hmdp_add_global_ai_index_code_keys`();
DROP PROCEDURE IF EXISTS `hmdp_add_global_ai_index_code_keys`;

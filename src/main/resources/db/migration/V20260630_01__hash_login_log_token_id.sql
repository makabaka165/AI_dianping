-- Store audit token identifiers as irreversible SHA-256 digests.

UPDATE `sys_login_log`
SET `token_id` = CONCAT('sha256:', SHA2(`token_id`, 256))
WHERE `token_id` IS NOT NULL
  AND `token_id` <> ''
  AND `token_id` NOT LIKE 'sha256:%';

package com.hmdp.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaConsistencyTest {

    private static final Path HMDP_SQL = Path.of("src/main/resources/db/hmdp.sql");
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path MYSQL8_COMPAT_DIR = Path.of("src/main/resources/db/mysql8-compat");

    @Test
    void hmdpSqlAndMigrationsShouldProvideCurrentBlogSchema() throws IOException {
        String allSql = allSchemaSql();

        assertThat(allSql).contains(
                "CREATE TABLE `tb_blog`",
                "ADD COLUMN status",
                "ADD COLUMN deleted",
                "ADD COLUMN publish_time",
                "CREATE TABLE IF NOT EXISTS `tb_blog_like`",
                "UNIQUE KEY `uk_blog_user` (`blog_id`, `user_id`)"
        );
        assertThat(allSql).contains(
                "idx_blog_shop_active_time (shop_id, status, deleted, create_time)",
                "idx_blog_user_active_time (user_id, status, deleted, create_time)",
                "idx_blog_active_liked_time (status, deleted, liked, create_time)"
        );
    }

    @Test
    void hmdpSqlAndMigrationsShouldProvideVoucherOrderConstraints() throws IOException {
        String allSql = allSchemaSql();

        assertThat(allSql).contains(
                "CREATE TABLE `tb_voucher_order`",
                "`status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1",
                "`pay_request_id` varchar(64)",
                "`active_order_key` tinyint(1) DEFAULT 1",
                "`pay_time` timestamp NULL DEFAULT NULL",
                "uk_voucher_order_user_voucher_active",
                "idx_voucher_order_status_create (status, create_time)",
                "WHERE `status` IN (4, 6)",
                "WHERE `status` NOT IN (4, 6)"
        );
    }

    @Test
    void voucherOrderActiveKeyMigrationShouldFailOnDuplicateActiveOrdersInsteadOfDeleting() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20260630_02__voucher_order_active_key_and_pay_request.sql"),
                StandardCharsets.UTF_8);
        Pattern voucherOrderDelete = Pattern.compile("(?is)\\bDELETE\\b.{0,120}\\btb_voucher_order\\b");
        Pattern voucherOrderAliasDelete = Pattern.compile("(?is)\\bDELETE\\s+o1\\b");
        Pattern signalAssignedToDynamicSql = Pattern.compile(
                "(?is)\\bSET\\s+@\\w+\\s*(?::=|=)\\s*(?:(?!;).)*\\bSIGNAL\\s+SQLSTATE\\b");
        Pattern signalPreparedFromLiteral = Pattern.compile(
                "(?is)\\bPREPARE\\s+\\w+\\s+FROM\\s+['\"]\\s*SIGNAL\\s+SQLSTATE\\b");
        int duplicateCheckIndex = migration.indexOf("CALL `hmdp_assert_no_duplicate_active_voucher_orders`()");
        int dropOldIndex = migration.indexOf("DROP INDEX uk_voucher_order_user_voucher");

        assertThat(voucherOrderDelete.matcher(migration).find()).isFalse();
        assertThat(voucherOrderAliasDelete.matcher(migration).find()).isFalse();
        assertThat(signalAssignedToDynamicSql.matcher(migration).find()).isFalse();
        assertThat(signalPreparedFromLiteral.matcher(migration).find()).isFalse();
        assertThat(migration).doesNotContain("SIGNAL SQLSTATE ''45000''");
        assertThat(migration).contains(
                "CREATE PROCEDURE `hmdp_assert_no_duplicate_active_voucher_orders`()",
                "DECLARE duplicate_active_order_groups BIGINT DEFAULT 0",
                "GROUP BY `user_id`, `voucher_id`",
                "HAVING COUNT(*) > 1",
                "SIGNAL SQLSTATE '45000'",
                "CALL `hmdp_assert_no_duplicate_active_voucher_orders`()",
                "resolve manually before migration"
        );
        assertThat(duplicateCheckIndex).isGreaterThanOrEqualTo(0);
        assertThat(dropOldIndex).isGreaterThanOrEqualTo(0);
        assertThat(duplicateCheckIndex).isLessThan(dropOldIndex);
    }

    @Test
    void voucherOrderLegacyUniqueMigrationShouldFailOnDuplicatesBeforeAddingOldIndex() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20260610_03__voucher_seckill_enterprise_upgrade.sql"),
                StandardCharsets.UTF_8);
        int duplicateCheckIndex = migration.indexOf("CALL `hmdp_assert_no_duplicate_voucher_orders`()");
        int addOldUniqueIndex = migration.indexOf(
                "ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id)");

        assertThat(migration).contains(
                "CREATE PROCEDURE `hmdp_assert_no_duplicate_voucher_orders`()",
                "DECLARE duplicate_order_groups BIGINT DEFAULT 0",
                "GROUP BY `user_id`, `voucher_id`",
                "HAVING COUNT(*) > 1",
                "SIGNAL SQLSTATE '45000'",
                "resolve manually before migration"
        );
        assertThat(duplicateCheckIndex).isGreaterThanOrEqualTo(0);
        assertThat(addOldUniqueIndex).isGreaterThanOrEqualTo(0);
        assertThat(duplicateCheckIndex).isLessThan(addOldUniqueIndex);
    }

    @Test
    void voucherOrderMigrationsShouldNotDestructivelyDeleteBusinessOrders() throws IOException {
        List<Pattern> destructiveVoucherOrderStatements = List.of(
                Pattern.compile("(?is)\\bDELETE\\s+o1\\b.{0,200}\\bFROM\\s+`?tb_voucher_order`?\\b"),
                Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+`?tb_voucher_order`?\\b"),
                Pattern.compile("(?is)\\bTRUNCATE\\s+(?:TABLE\\s+)?`?tb_voucher_order`?\\b"),
                Pattern.compile("(?is)\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?`?tb_voucher_order`?\\b")
        );
        List<String> violations = new ArrayList<>();

        try (var stream = Files.list(MIGRATION_DIR)) {
            List<Path> migrations = stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path migration : migrations) {
                String sql = Files.readString(migration, StandardCharsets.UTF_8);
                for (Pattern destructiveStatement : destructiveVoucherOrderStatements) {
                    if (destructiveStatement.matcher(sql).find()) {
                        violations.add(migration.getFileName() + " matches " + destructiveStatement.pattern());
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void aiSchemaRepairMigrationAuditsDuplicatesAndPhysicalIndexDefinitions() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20260723_03__audit_ai_schema_repair_preconditions.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration).contains(
                "CREATE PROCEDURE `hmdp_assert_ai_schema_repair_ready`()",
                "duplicate_deduplication_keys",
                "duplicate_dead_letters",
                "uk_ai_outbox_deduplication",
                "uk_ai_outbox_dead_letter_consumer",
                "SIGNAL SQLSTATE '45000'",
                "CALL `hmdp_assert_ai_schema_repair_ready`()",
                "resolve manually before migration");
    }

    @Test
    void redisIndexNameMigrationUsesStableHashAndPreservesCustomNames() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20260723_04__collision_safe_knowledge_index_names.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration).contains(
                "SHA2(code, 256)",
                "ai_kb_v2~",
                "REGEXP_REPLACE(code, '[^A-Za-z0-9_]', '_')",
                "hmdp_assert_unique_ai_index_v2_names",
                "make index_version values globally unique before migration",
                "AND status = 'BUILDING'",
                "AND active = 0");
        assertThat(migration).doesNotContain("DROP TABLE", "DELETE FROM ai_index_version");
    }

    @Test
    void mysql8CompatibilityScriptsShouldAvoidMariaDbOnlyColumnSyntax() throws IOException {
        String approvalCompatibility = Files.readString(
                MYSQL8_COMPAT_DIR.resolve("V20260720_03__approval_outbox_and_index_recovery.sql"),
                StandardCharsets.UTF_8);
        String shadowCompatibility = Files.readString(
                MYSQL8_COMPAT_DIR.resolve("V20260721_01__knowledge_shadow_index_activation.sql"),
                StandardCharsets.UTF_8);

        assertThat(approvalCompatibility).doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(shadowCompatibility).doesNotContain("ADD COLUMN IF NOT EXISTS");
        assertThat(approvalCompatibility).contains("information_schema.columns", "PREPARE stmt FROM @ddl");
        assertThat(shadowCompatibility).contains(
                "information_schema.columns",
                "information_schema.statistics",
                "PREPARE stmt FROM @ddl");
    }

    @Test
    void publishedFlywayMigrationsShouldKeepTheirOriginalMariaDbSyntax() throws IOException {
        String approvalHistory = Files.readString(
                MIGRATION_DIR.resolve("V20260720_03__approval_outbox_and_index_recovery.sql"),
                StandardCharsets.UTF_8);
        String shadowHistory = Files.readString(
                MIGRATION_DIR.resolve("V20260721_01__knowledge_shadow_index_activation.sql"),
                StandardCharsets.UTF_8);

        assertThat(approvalHistory).contains(
                "ADD COLUMN IF NOT EXISTS build_mode",
                "ADD COLUMN IF NOT EXISTS redacted_structure_json");
        assertThat(shadowHistory).contains(
                "ADD COLUMN IF NOT EXISTS active_index_version",
                "ADD COLUMN IF NOT EXISTS shadow_of_index_version",
                "ADD COLUMN IF NOT EXISTS deduplication_key");
    }

    @Test
    void mysql8HistoryBridgeShouldUseExactNullSafeHistoryContracts() throws IOException {
        String bridge = Files.readString(
                Path.of("docs/review/sql/mysql8-flyway-history-bridge.sql"),
                StandardCharsets.UTF_8);

        assertThat(bridge).contains(
                "history_table_exists",
                "duplicate_history",
                "description <=> 'approval outbox and index recovery'",
                "checksum <=> 2143241596",
                "description <=> 'knowledge shadow index activation'",
                "checksum <=> 814957484",
                "target_successes <> 2");
        assertThat(bridge).doesNotContain("WHERE success = 0 AND version IN");
    }

    @Test
    void mysql8PowerShellBridgeShouldHandleFreshAndAlreadyBridgedDatabases() throws IOException {
        String script = Files.readString(
                Path.of("scripts/repair-mysql8-flyway-compatibility.ps1"),
                StandardCharsets.UTF_8);
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);

        assertThat(script).contains(
                "${Database}?useSSL=false",
                "FLYWAY_URL",
                "if ($historyTableExists)",
                "skipping history backup and targeted cleanup",
                "compatibility bridge was already complete",
                "if ($failedTargets -gt 0)",
                "$deletedFailedTargets",
                "DELETE FROM flyway_schema_history",
                "SELECT ROW_COUNT()",
                "skipping targeted history cleanup",
                "mysql8-flyway-success-checksum-reconcile.sql",
                "$approvalLegacySuccess -eq 1",
                "$shadowLegacySuccess -eq 1",
                "Invoke-Flyway 'validate'",
                ".local-backups/flyway-compat",
                "History backup directory must not be inside the Maven target directory");
        assertThat(script).doesNotContain(
                "-Dflyway.url=$script:jdbcUrl",
                "Invoke-Flyway 'repair'",
                "target/flyway-compat-backups");
        assertThat(gitignore.lines()).contains(".local-backups/");
    }

    @Test
    void mysql8SuccessfulChecksumReconciliationShouldBeExactAndTransactional() throws IOException {
        String reconciliation = Files.readString(
                Path.of("docs/review/sql/mysql8-flyway-success-checksum-reconcile.sql"),
                StandardCharsets.UTF_8);

        assertThat(reconciliation).contains(
                "target_rows <> 2",
                "target_versions <> 2",
                "legacy_contract_rows <> 2",
                "checksum <=> -128447297",
                "checksum <=> -946922017",
                "SET checksum = 2143241596",
                "SET checksum = 814957484",
                "SET approval_updates = ROW_COUNT()",
                "SET shadow_updates = ROW_COUNT()",
                "approval_updates <> 1 OR shadow_updates <> 1",
                "START TRANSACTION",
                "ROLLBACK",
                "COMMIT",
                "missing_tables",
                "missing_columns",
                "invalid_indexes",
                "uk_ai_approval_decision",
                "uk_ai_outbox_consumer",
                "uk_ai_outbox_deduplication",
                "uk_ai_outbox_dead_letter_consumer");
        assertThat(reconciliation).doesNotContain(
                "UPDATE flyway_schema_history\n    SET checksum = CASE",
                "DELETE FROM flyway_schema_history",
                "TRUNCATE TABLE",
                "flyway repair");
    }

    @Test
    void redisIndexIdentityMigrationEnforcesTheGlobalCodeInvariant() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20260723_05__enforce_global_knowledge_index_codes.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration).contains(
                "duplicate_index_codes",
                "duplicate_version_codes",
                "uk_ai_index_version_code",
                "uk_ai_knowledge_base_index_version",
                "SIGNAL SQLSTATE '45000'",
                "resolve duplicate code values before migration");
        assertThat(migration).doesNotContain("DELETE FROM", "TRUNCATE TABLE", "DROP TABLE");
    }

    @Test
    void aiMigrationPreflightDocumentsDuplicateAndCustomNameAudits() throws IOException {
        String audit = Files.readString(
                Path.of("docs/review/sql/ai-schema-migration-preflight.sql"),
                StandardCharsets.UTF_8);

        assertThat(audit).contains(
                "duplicate_deduplication_key",
                "duplicate_dead_letter",
                "duplicate_index_version_code",
                "duplicate_knowledge_version_code",
                "COUNT(DISTINCT id) > 1",
                "uk_ai_outbox_deduplication",
                "uk_ai_outbox_dead_letter_consumer");
        assertThat(audit).doesNotContain("DELETE FROM", "TRUNCATE TABLE");
    }

    @Test
    void hmdpSqlAndMigrationsShouldProvideShopRbacAndAuditSchema() throws IOException {
        String allSql = allSchemaSql();

        assertThat(allSql).contains(
                "`version` int NOT NULL DEFAULT 0",
                "CREATE TABLE IF NOT EXISTS `sys_role`",
                "CREATE TABLE IF NOT EXISTS `sys_permission`",
                "CREATE TABLE IF NOT EXISTS `sys_user_role`",
                "CREATE TABLE IF NOT EXISTS `sys_operation_log`",
                "CREATE TABLE IF NOT EXISTS `sys_merchant_shop`",
                "device_fingerprint",
                "fail_count"
        );
    }

    @Test
    void flywayMigrationFileNamesShouldFollowProjectVersionPattern() throws IOException {
        Pattern migrationPattern = Pattern.compile("V\\d{8}_\\d{2}__.+\\.sql");
        List<String> invalidNames;
        try (var stream = Files.list(MIGRATION_DIR)) {
            invalidNames = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !migrationPattern.matcher(name).matches())
                    .collect(Collectors.toList());
        }

        assertThat(invalidNames).isEmpty();
    }

    @Test
    void readmeShouldDescribeHmdpSqlThenFlywayInitialization() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(readme).contains("先导入 `src/main/resources/db/hmdp.sql`");
        assertThat(readme).contains("启动应用时 Flyway 会自动执行 `src/main/resources/db/migration`");
        assertThat(readme).contains("不要只导入 `hmdp.sql` 后关闭 Flyway");
        assertThat(readme).contains(
                "repair-mysql8-flyway-compatibility.ps1",
                "Oracle MySQL 8",
                "2143241596",
                "814957484");
    }

    @Test
    void composeInfrastructurePortsShouldRemainLoopbackOnlyAndConsistent() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.ai.yml"), StandardCharsets.UTF_8);
        String environment = Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8);
        List<String> environmentLines = environment.lines().collect(Collectors.toList());
        String localConfiguration = Files.readString(
                Path.of("src/main/resources/application-local.yaml"), StandardCharsets.UTF_8);
        String exampleConfiguration = Files.readString(
                Path.of("src/main/resources/application-example.yaml"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String verification = Files.readString(
                Path.of("scripts/verify-ai-platform.sh"), StandardCharsets.UTF_8);

        assertThat(compose).contains(
                "127.0.0.1:${AI_MYSQL_PORT:-3307}:3306",
                "127.0.0.1:${AI_REDIS_PORT:-6381}:6379",
                "127.0.0.1:${AI_REDIS_STACK_PORT:-6380}:6379",
                "127.0.0.1:${AI_MINIO_PORT:-9000}:9000",
                "127.0.0.1:${AI_MINIO_CONSOLE_PORT:-9001}:9001");
        assertThat(compose).doesNotContain(
                "ports: [\"${AI_MYSQL_PORT:-",
                "ports: [\"${AI_REDIS_PORT:-",
                "ports: [\"${AI_REDIS_STACK_PORT:-",
                "- \"${AI_MINIO_PORT:-",
                "- \"${AI_MINIO_CONSOLE_PORT:-");
        assertThat(environmentLines).contains(
                "REDIS_PORT=6381",
                "MEMORY_REDIS_PORT=6381",
                "VECTOR_REDIS_PORT=6380",
                "AI_REDIS_PORT=6381",
                "AI_REDIS_STACK_PORT=6380");
        assertThat(environmentLines).doesNotContain(
                "REDIS_PORT=6379",
                "MEMORY_REDIS_PORT=6379",
                "AI_REDIS_PORT=6379");
        assertThat(localConfiguration).contains(
                "port: ${REDIS_PORT:6381}",
                "port: ${MEMORY_REDIS_PORT:6381}");
        assertThat(exampleConfiguration).contains(
                "port: ${REDIS_PORT:6381}",
                "port: ${HMDP_REDIS_PORT:${REDIS_PORT:6381}}");
        assertThat(readme).contains(
                "REDIS_PORT=6381",
                "MEMORY_REDIS_PORT=6381",
                "VECTOR_REDIS_PORT=6380");
        assertThat(verification).contains(
                "${AI_REDIS_PORT:-6381}",
                "${AI_REDIS_STACK_PORT:-6380}",
                "assert_compose_loopback_port mysql 3306",
                "assert_compose_loopback_port redis 6379",
                "assert_compose_loopback_port redis-stack 6379",
                "assert_compose_loopback_port minio 9000",
                "export_verified_endpoint DB_URL",
                "export_verified_endpoint REDIS_PORT",
                "export_verified_endpoint VECTOR_REDIS_PORT",
                "export_verified_endpoint MEMORY_REDIS_PORT",
                "export_verified_endpoint MINIO_ENDPOINT",
                "checking public document deletion across Redis Stack and MinIO",
                "mc ls --recursive --json",
                "unexpected MinIO listing response",
                "assert_document_absent");
        assertThat(verification).doesNotContain("mc find", "--type f | wc -l");
        assertThat(compose).doesNotContain("${AI_REDIS_PORT:-6379}");
    }

    private String allSchemaSql() throws IOException {
        StringBuilder builder = new StringBuilder(Files.readString(HMDP_SQL, StandardCharsets.UTF_8));
        try (var stream = Files.list(MIGRATION_DIR)) {
            List<Path> migrations = stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path migration : migrations) {
                builder.append('\n')
                        .append(Files.readString(migration, StandardCharsets.UTF_8));
            }
        }
        return builder.toString();
    }
}

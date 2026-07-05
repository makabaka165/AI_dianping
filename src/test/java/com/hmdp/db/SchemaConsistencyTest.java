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

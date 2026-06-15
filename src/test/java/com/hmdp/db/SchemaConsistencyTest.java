package com.hmdp.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "`pay_time` timestamp NULL DEFAULT NULL",
                "uk_voucher_order_user_voucher (user_id, voucher_id)",
                "idx_voucher_order_status_create (status, create_time)"
        );
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

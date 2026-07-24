package com.hmdp.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class Mysql8SuccessfulChecksumReconciliationIT {
  private static final int APPROVAL_CHECKSUM = 2143241596;
  private static final int SHADOW_CHECKSUM = 814957484;
  private static final int LEGACY_APPROVAL_CHECKSUM = -128447297;
  private static final int LEGACY_SHADOW_CHECKSUM = -946922017;
  private static final String RECONCILIATION_SQL =
      "/tmp/mysql8-flyway-success-checksum-reconcile.sql";

  @Container static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  @BeforeAll
  static void prepareSchemaAndScript() {
    MYSQL.migrateSchema();
    MYSQL.copyFileToContainer(
        MountableFile.forHostPath(
            Path.of("docs/review/sql/mysql8-flyway-success-checksum-reconcile.sql")
                .toAbsolutePath()),
        RECONCILIATION_SQL);
  }

  @BeforeEach
  @AfterEach
  void restorePublishedChecksums() {
    setChecksum("20260720.03", APPROVAL_CHECKSUM);
    setChecksum("20260721.01", SHADOW_CHECKSUM);
  }

  @Test
  void reconcilesOnlyTheKnownSuccessfulChecksumPair() throws Exception {
    setChecksum("20260720.03", LEGACY_APPROVAL_CHECKSUM);
    setChecksum("20260721.01", LEGACY_SHADOW_CHECKSUM);

    org.testcontainers.containers.Container.ExecResult result = executeReconciliation();

    assertThat(result.getExitCode()).isZero();
    assertThat(targetChecksums()).containsExactly(APPROVAL_CHECKSUM, SHADOW_CHECKSUM);
    assertThat(targetSuccessCount()).isEqualTo(2);
  }

  @Test
  void rejectsAnUnknownSuccessfulChecksumWithoutPartialUpdate() throws Exception {
    setChecksum("20260720.03", LEGACY_APPROVAL_CHECKSUM);
    setChecksum("20260721.01", -1);

    org.testcontainers.containers.Container.ExecResult result = executeReconciliation();

    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("history contract failed");
    assertThat(targetChecksums()).containsExactly(LEGACY_APPROVAL_CHECKSUM, -1);
  }

  private org.testcontainers.containers.Container.ExecResult executeReconciliation()
      throws Exception {
    return MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --user=\"$MYSQL_USER\" --password=\"$MYSQL_PASSWORD\" "
            + "--database=\"$MYSQL_DATABASE\" < "
            + RECONCILIATION_SQL);
  }

  private void setChecksum(String version, int checksum) {
    int updates =
        MYSQL.jdbcTemplate()
            .update(
                "update flyway_schema_history set checksum=? where version=? and success=1",
                checksum,
                version);
    assertThat(updates).isEqualTo(1);
  }

  private List<Integer> targetChecksums() {
    return MYSQL.jdbcTemplate()
        .queryForList(
            "select checksum from flyway_schema_history "
                + "where version in ('20260720.03','20260721.01') order by installed_rank",
            Integer.class);
  }

  private int targetSuccessCount() {
    Integer count =
        MYSQL.jdbcTemplate()
            .queryForObject(
                "select count(*) from flyway_schema_history where success=1 and "
                    + "((version='20260720.03' and checksum=?) or "
                    + "(version='20260721.01' and checksum=?))",
                Integer.class,
                APPROVAL_CHECKSUM,
                SHADOW_CHECKSUM);
    return count == null ? 0 : count;
  }
}

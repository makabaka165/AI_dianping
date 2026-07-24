package com.hmdp.ai.integration.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** MySQL container with the legacy application schema required before Flyway migrations. */
public final class IntegrationMySqlContainer
    extends MySQLContainer<IntegrationMySqlContainer> {
  private static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.0.36");
  private static final String COMPAT_HISTORY_TABLE = "flyway_mysql8_compat_history";
  private static final int APPROVAL_MIGRATION_CHECKSUM = 2143241596;
  private static final int SHADOW_MIGRATION_CHECKSUM = 814957484;

  public IntegrationMySqlContainer() {
    super(IMAGE);
    withDatabaseName("hmdp");
    withUsername("hmdp");
    withPassword("hmdp-test");
    withInitScript("db/hmdp.sql");
    withUrlParam("useSSL", "false");
    withUrlParam("allowPublicKeyRetrieval", "true");
  }

  public synchronized void migrateSchema() {
    // MySQL 8 rejects the historical MariaDB-only ADD COLUMN IF NOT EXISTS syntax.
    // Migrate the real history to the last safe version, execute replacements in an
    // isolated history table, then preserve the published checksums in the real table.
    JdbcTemplate jdbc = jdbcTemplate();
    if (publishedHistoryIsComplete(jdbc)) {
      migrateCurrentSchema();
      return;
    }

    Flyway.configure()
        .dataSource(getJdbcUrl(), getUsername(), getPassword())
        .baselineOnMigrate(true)
        .target("20260720.02")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    jdbc.execute("DROP TABLE IF EXISTS " + COMPAT_HISTORY_TABLE);
    Flyway.configure()
        .dataSource(getJdbcUrl(), getUsername(), getPassword())
        .table(COMPAT_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion("20260720.02")
        .locations("classpath:db/mysql8-compat")
        .load()
        .migrate();
    bridgePublishedHistory(jdbc);
    jdbc.execute("DROP TABLE IF EXISTS " + COMPAT_HISTORY_TABLE);

    Flyway.configure()
        .dataSource(getJdbcUrl(), getUsername(), getPassword())
        .baselineOnMigrate(true)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private void migrateCurrentSchema() {
    Flyway.configure()
        .dataSource(getJdbcUrl(), getUsername(), getPassword())
        .baselineOnMigrate(true)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  public JdbcTemplate jdbcTemplate() {
    return new JdbcTemplate(
        new DriverManagerDataSource(getJdbcUrl(), getUsername(), getPassword()));
  }

  private void bridgePublishedHistory(JdbcTemplate jdbc) {
    assertTargetHistoryShape(jdbc);
    Integer baseRank =
        jdbc.queryForObject(
            "select max(installed_rank) from flyway_schema_history "
                + "where version='20260720.02' and success=1",
            Integer.class);
    if (baseRank == null) {
      throw new IllegalStateException("Flyway base migration 20260720.02 is missing");
    }
    Integer later =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success=1 and installed_rank>? "
                + "and version not in ('20260720.03','20260721.01')",
            Integer.class,
            baseRank);
    if (later != null && later > 0) {
      throw new IllegalStateException("Unexpected Flyway migrations after 20260720.02");
    }
    jdbc.update(
        "delete from flyway_schema_history where success=0 and "
            + "((version='20260720.03' and description <=> 'approval outbox and index recovery' "
            + "and type <=> 'SQL' and script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql' "
            + "and checksum <=> 2143241596) or "
            + "(version='20260721.01' and description <=> 'knowledge shadow index activation' "
            + "and type <=> 'SQL' and script <=> 'V20260721_01__knowledge_shadow_index_activation.sql' "
            + "and checksum <=> 814957484))");
    Integer nextRank =
        jdbc.queryForObject("select coalesce(max(installed_rank),0)+1 from flyway_schema_history", Integer.class);
    if (nextRank == null) throw new IllegalStateException("Flyway history rank is unavailable");
    if (migrationMissing(jdbc, "20260720.03")) {
      insertHistory(
          jdbc,
          nextRank,
          "20260720.03",
          "approval outbox and index recovery",
          "V20260720_03__approval_outbox_and_index_recovery.sql",
          APPROVAL_MIGRATION_CHECKSUM);
      nextRank++;
    }
    if (migrationMissing(jdbc, "20260721.01")) {
      insertHistory(
          jdbc,
          nextRank,
          "20260721.01",
          "knowledge shadow index activation",
          "V20260721_01__knowledge_shadow_index_activation.sql",
          SHADOW_MIGRATION_CHECKSUM);
    }
  }

  private boolean migrationMissing(JdbcTemplate jdbc, String version) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version=? and success=1",
            Integer.class,
            version);
    return count == null || count == 0;
  }

  private boolean publishedHistoryIsComplete(JdbcTemplate jdbc) {
    if (!tableExists(jdbc, "flyway_schema_history")) {
      return false;
    }
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version in ('20260720.03','20260721.01')",
            Integer.class);
    Integer exactSuccesses =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success=1 and "
                + "((version='20260720.03' and description <=> 'approval outbox and index recovery' "
                + "and type <=> 'SQL' and script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql' "
                + "and checksum <=> 2143241596) or "
                + "(version='20260721.01' and description <=> 'knowledge shadow index activation' "
                + "and type <=> 'SQL' and script <=> 'V20260721_01__knowledge_shadow_index_activation.sql' "
                + "and checksum <=> 814957484))",
            Integer.class);
    Integer exactVersions =
        jdbc.queryForObject(
            "select count(distinct version) from flyway_schema_history where success=1 and "
                + "((version='20260720.03' and checksum <=> 2143241596) or "
                + "(version='20260721.01' and checksum <=> 814957484))",
            Integer.class);
    if (rows == null || rows == 0) {
      return false;
    }
    if (rows != 2
        || exactSuccesses == null
        || exactSuccesses != 2
        || exactVersions == null
        || exactVersions != 2) {
      throw new IllegalStateException("Published Flyway history has an invalid MySQL 8 bridge state");
    }
    return true;
  }

  private void assertTargetHistoryShape(JdbcTemplate jdbc) {
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version in ('20260720.03','20260721.01')",
            Integer.class);
    Integer invalidRows =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where "
                + "(version='20260720.03' and not (description <=> 'approval outbox and index recovery' "
                + "and type <=> 'SQL' and script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql' "
                + "and checksum <=> 2143241596)) or "
                + "(version='20260721.01' and not (description <=> 'knowledge shadow index activation' "
                + "and type <=> 'SQL' and script <=> 'V20260721_01__knowledge_shadow_index_activation.sql' "
                + "and checksum <=> 814957484))",
            Integer.class);
    Integer duplicateVersions =
        jdbc.queryForObject(
            "select count(*) from (select version from flyway_schema_history "
                + "where version in ('20260720.03','20260721.01') group by version having count(*)>1) d",
            Integer.class);
    if ((rows != null && rows > 2)
        || (invalidRows != null && invalidRows > 0)
        || (duplicateVersions != null && duplicateVersions > 0)) {
      throw new IllegalStateException("Published Flyway history has an invalid target row");
    }
  }

  private boolean tableExists(JdbcTemplate jdbc, String tableName) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema=database() and table_name=?",
            Integer.class,
            tableName);
    return count != null && count == 1;
  }

  private void insertHistory(
      JdbcTemplate jdbc,
      int rank,
      String version,
      String description,
      String script,
      int checksum) {
    jdbc.update(
        "insert into flyway_schema_history "
            + "(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
            + "values (?,?,?,'SQL',?,?,?,0,1)",
        rank,
        version,
        description,
        script,
        checksum,
        getUsername());
  }
}

package com.hmdp.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MemoryPersistenceIntegrationTest {
  @Container static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  @Test
  void flywayCreatesConversationMemoryFeedbackAndEvaluationTables() {
    MYSQL.migrateSchema();
    JdbcTemplate jdbc = MYSQL.jdbcTemplate();

    Integer count =
        jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema=database() "
                + "and table_name in ('ai_conversation','ai_message','ai_memory_fact',"
                + "'ai_feedback','ai_eval_run')",
            Integer.class);

    assertThat(count).isEqualTo(5);
  }
}

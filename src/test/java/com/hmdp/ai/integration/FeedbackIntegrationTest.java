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
class FeedbackIntegrationTest {
  @Container static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  @Test
  void feedbackTablePersistsRunAndMessageReferences() {
    MYSQL.migrateSchema();
    JdbcTemplate jdbc = MYSQL.jdbcTemplate();

    jdbc.update(
        "insert into ai_feedback (id,tenant_id,workspace_id,run_id,message_id,rating,"
            + "tags_json,review_status,status,created_by,updated_by) values "
            + "('f','t','w','r','m',-1,'[\"FACT_ERROR\"]','PENDING','ACTIVE','u','u')");

    assertThat(jdbc.queryForObject("select run_id from ai_feedback where id='f'", String.class))
        .isEqualTo("r");
  }
}

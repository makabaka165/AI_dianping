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
class DefaultShopSeedModelBudgetIntegrationTest {
  @Container
  static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  @Test
  void everyDefaultLlmNodeFitsTheAgentBoundModelVersion() {
    MYSQL.migrateSchema();
    JdbcTemplate jdbc = MYSQL.jdbcTemplate();

    Integer incompatibleNodes =
        jdbc.queryForObject(
            "select count(*) from ai_workflow_node node"
                + " join ai_agent_version agent_version"
                + " on agent_version.workflow_version_id=node.workflow_version_id"
                + " and agent_version.deleted=0"
                + " join ai_model_profile_version model_version"
                + " on model_version.id=agent_version.model_profile_version_id"
                + " and model_version.deleted=0"
                + " where agent_version.id='agent-shop-consultant-v1'"
                + " and node.node_type='LLM' and node.status='ACTIVE' and node.deleted=0"
                + " and cast(json_unquote(json_extract(node.configuration_json,"
                + " '$.maxOutputTokensOverride')) as unsigned) > model_version.max_output_tokens",
            Integer.class);
    Integer publishedModelLimit =
        jdbc.queryForObject(
            "select max_output_tokens from ai_model_profile_version"
                + " where id='model-shop-chat-v1' and status='PUBLISHED' and deleted=0",
            Integer.class);

    assertThat(incompatibleNodes).isZero();
    assertThat(publishedModelLimit).isEqualTo(1000);
  }
}

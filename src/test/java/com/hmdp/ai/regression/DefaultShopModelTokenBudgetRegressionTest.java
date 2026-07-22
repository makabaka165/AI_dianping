package com.hmdp.ai.regression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DefaultShopModelTokenBudgetRegressionTest {
  @Test
  void correctionUsesTheExactBoundModelVersionWithoutMutatingIt() throws Exception {
    String migration =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/"
                    + "V20260722_01__align_shop_llm_token_budget.sql"));

    assertTrue(migration.contains("agent_version.model_profile_version_id"));
    assertTrue(migration.contains("JSON_SET("));
    assertTrue(migration.contains("model_version.max_output_tokens"));
    assertTrue(migration.contains("agent_version.id = 'agent-shop-consultant-v1'"));
    assertFalse(migration.contains("UPDATE ai_model_profile_version"));
  }
}

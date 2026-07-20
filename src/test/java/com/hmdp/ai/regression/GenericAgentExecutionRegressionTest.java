package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GenericAgentExecutionRegressionTest {
    @Test
    void genericRuntimeMustNotDelegateToShopCompatibilityService() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/legacy/compatibility/ShopCompatibilityExecutionEngine.java"));
        String llm = Files.readString(Path.of("src/main/java/com/hmdp/ai/runtime/node/LlmNodeExecutor.java"));
        assertFalse(source.contains("implements AgentModelExecutionPort"),
                "legacy shop compatibility must not be the generic model execution port");
        assertFalse(llm.contains("ShopAIApplicationService"),
                "generic agent execution must not re-enter the legacy shop application service");
    }
}

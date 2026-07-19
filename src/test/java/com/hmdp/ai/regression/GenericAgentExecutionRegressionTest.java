package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GenericAgentExecutionRegressionTest {
    @Test
    void genericRuntimeMustNotDelegateToShopCompatibilityService() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/infrastructure/external/ShopCompatibilityExecutionEngine.java"));
        assertFalse(source.contains("implements AgentModelExecutionPort"),
                "legacy shop compatibility must not be the generic model execution port");
        assertFalse(source.contains("ShopAIApplicationService"),
                "generic agent execution must not re-enter the legacy shop application service");
    }
}

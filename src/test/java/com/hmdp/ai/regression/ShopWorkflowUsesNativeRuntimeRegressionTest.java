package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopWorkflowUsesNativeRuntimeRegressionTest {
    @Test
    void defaultSeedMustContainNativeToolAndRetrievalNodes() throws Exception {
        String seed = Files.readString(Path.of("src/main/resources/db/migration/V20260718_03__ai_workflow_tool_runtime.sql"));
        assertFalse(seed.contains("SHOP_COMPATIBILITY"));
        assertTrue(seed.contains("'TOOL'"));
        assertTrue(seed.contains("'KNOWLEDGE_RETRIEVE'"));
        assertTrue(seed.contains("'MEMORY_RECALL'"));
    }
}

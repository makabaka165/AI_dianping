package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopWorkflowUsesNativeRuntimeRegressionTest {
    @Test
    void defaultSeedMustContainNativeToolAndRetrievalNodes() throws Exception {
        String seed = Stream.of(
                        Path.of("src/main/resources/db/migration/V20260718_03__ai_workflow_tool_runtime.sql"),
                        Path.of("src/main/resources/db/migration/V20260720_02__native_shop_workflow_and_runtime_hardening.sql"),
                        Path.of("src/main/resources/db/migration/V20260721_04__complete_native_shop_workflow.sql"))
                .map(path -> {
                    try { return Files.readString(path); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                }).collect(Collectors.joining("\n"));
        assertFalse(seed.contains("SHOP_COMPATIBILITY"));
        assertTrue(seed.contains("'TOOL'"));
        assertTrue(seed.contains("'KNOWLEDGE_RETRIEVE'"));
        assertTrue(seed.contains("'MEMORY_RECALL'"));
        assertTrue(seed.contains("'HUMAN_FEEDBACK'"));
        assertTrue(seed.contains("node_type = 'FOREACH'"));
        assertTrue(seed.contains("'compare-tool','compare-analysis'"));
    }
}

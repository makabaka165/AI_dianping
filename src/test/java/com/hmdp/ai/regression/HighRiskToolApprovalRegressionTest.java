package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HighRiskToolApprovalRegressionTest {
    @Test
    void highRiskExecutionMustReferenceIndependentApprovalAndPermission() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/runtime/tool/ToolExecutionPipeline.java"));
        assertTrue(source.contains("ApprovalRequest"));
        assertTrue(source.contains("TOOL_APPROVE"));
        assertTrue(source.contains("inputHash"));
    }
}

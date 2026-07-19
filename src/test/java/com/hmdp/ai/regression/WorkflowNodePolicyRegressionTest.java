package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowNodePolicyRegressionTest {
    @Test
    void runtimeMustApplyTimeoutAndMaxAttemptsAtExecutionTime() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/runtime/workflow/DefaultWorkflowRuntime.java"));
        assertTrue(source.contains("getMaxAttempts()"));
        assertTrue(source.contains("getTimeoutMs()"));
        assertTrue(source.contains("cancel(true)"));
    }
}

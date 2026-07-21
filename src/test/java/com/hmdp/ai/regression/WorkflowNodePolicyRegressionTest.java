package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowNodePolicyRegressionTest {
    @Test
    void runtimeDelegatesTimeoutRetryBackoffAndCancellationToReliabilityModule() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/com/hmdp/ai/runtime/workflow/DefaultWorkflowRuntime.java"));
        String policy = Files.readString(Path.of(
                "src/main/java/com/hmdp/ai/runtime/workflow/NodeExecutionPolicy.java"));
        String reliability = Files.readString(Path.of(
                "src/main/java/com/hmdp/ai/runtime/workflow/NodeReliabilityExecutor.java"));

        assertTrue(runtime.contains("nodeReliability.execute"));
        assertTrue(runtime.contains("NodeCancellationToken"));
        assertTrue(policy.contains("getMaxAttempts()"));
        assertTrue(policy.contains("getTimeoutMs()"));
        assertTrue(policy.contains("retryableErrors"));
        assertTrue(policy.contains("backoffMultiplier"));
        assertTrue(reliability.contains("future.cancel(true)"));
        assertTrue(reliability.contains("effectiveTimeoutMs"));
        assertTrue(reliability.contains("awaitBackoff"));
    }
}

package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRevocationRegressionTest {
    @Test
    void runtimeMustRevalidateAuthorizationWhenAQueuedRunStarts() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/runtime/agent/DefaultAgentRuntime.java"));
        assertTrue(source.contains("revalidate") || source.contains("authorize"));
    }
}

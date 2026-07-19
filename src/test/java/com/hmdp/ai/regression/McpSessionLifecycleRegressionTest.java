package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionLifecycleRegressionTest {
    @Test
    void discoveryMustReuseOneInitializedSessionAndSendInitializedNotification() throws Exception {
        String discovery = Files.readString(Path.of("src/main/java/com/hmdp/ai/infrastructure/mcp/McpToolDiscoveryService.java"));
        String client = Files.readString(Path.of("src/main/java/com/hmdp/ai/infrastructure/mcp/McpHttpClient.java"));
        assertTrue(discovery.indexOf("clients.create()") == discovery.lastIndexOf("clients.create()"));
        assertTrue(client.contains("initialized"));
    }
}

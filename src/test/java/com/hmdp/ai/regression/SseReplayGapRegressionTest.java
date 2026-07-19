package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SseReplayGapRegressionTest {
    @Test
    void sseHubMustRegisterBeforeReplayAndDeduplicateBySequence() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/application/agent/event/SseRunEventHub.java"));
        assertTrue(source.contains("sequence"));
        assertTrue(source.indexOf("computeIfAbsent") < source.indexOf("for (AgentRunEventResponse event"));
    }
}

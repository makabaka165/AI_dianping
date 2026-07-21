package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SseReplayGapRegressionTest {
    @Test
    void sseHubMustRegisterBeforeReplayAndDeduplicateBySequence() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/application/agent/event/SseRunEventHub.java"));
        assertTrue(source.indexOf("emitters.computeIfAbsent") < source.indexOf("replayToStableBoundary"));
        assertTrue(source.contains("latestSequence.getAsLong()"));
        assertTrue(source.contains("state.pending.put"));
        assertTrue(source.contains("finishReplay"));
        assertTrue(source.contains("comment(\"heartbeat\")"));
    }
}

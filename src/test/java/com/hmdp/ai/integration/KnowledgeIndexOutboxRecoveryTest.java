package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.IndexBuildRequestRepository;
import com.hmdp.ai.domain.knowledge.IndexVerificationResult;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.OutboxEvent;
import com.hmdp.ai.runtime.knowledge.IndexBuildWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
class KnowledgeIndexOutboxRecoveryTest {
    @Test
    void retriesSameOutboxRequestAfterInterruptedRedisWrite() {
        IndexBuildRequestRepository requests = mock(IndexBuildRequestRepository.class);
        KnowledgeRepository knowledge = mock(KnowledgeRepository.class);
        KnowledgeIndexPort index = mock(KnowledgeIndexPort.class);
        ObjectMapper mapper = new ObjectMapper();
        OutboxEvent event = new OutboxEvent("event", "tenant", "workspace", "INDEX_VERSION", "index-v2",
                "INDEX_BUILD_REQUESTED", "{\"jobId\":\"job\",\"documentVersionId\":\"dv\","
                + "\"documentId\":\"document\",\"knowledgeBaseId\":\"kb\","
                + "\"indexVersion\":\"index-v2\",\"embeddingDimension\":3}", 0);
        KnowledgeChunk chunk = new KnowledgeChunk("chunk", "tenant", "workspace", "kb", 2,
                "document", 1, "dv", "index-v2", 0, "service policy", "service policy",
                "hash", 3, new float[]{1, 0, 0}, 1, "text/plain", 1,
                "Policy", "Policy", null, null, null, null, null, 0, 14, "{}");
        when(requests.claim(eq("event"), anyString(), anyString(), any())).thenReturn(true);
        when(knowledge.findIndexBuildChunks("tenant", "workspace", "index-v2"))
                .thenReturn(Collections.singletonList(chunk));
        doThrow(new IllegalStateException("REDIS_WRITE_INTERRUPTED")).doNothing()
                .when(index).index(any());
        when(requests.fail(eq("event"), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);
        when(index.verify(any(), any())).thenReturn(new IndexVerificationResult(1, 1, 1, 1,
                true, true, true, true));
        IndexBuildWorker worker = new IndexBuildWorker(requests, knowledge, index, mapper, "test-worker");

        worker.process(event);
        worker.process(event);

        verify(index, times(2)).index(any());
        verify(knowledge).completeIndexBuild(eq("job"), eq("dv"), eq("document"), eq("index-v2"),
                any(IndexVerificationResult.class), eq("index-worker"));
        verify(requests).complete(eq("event"), anyString());
    }
}

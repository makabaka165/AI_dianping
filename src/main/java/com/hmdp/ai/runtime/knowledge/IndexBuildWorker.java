package com.hmdp.ai.runtime.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.IndexBuildRequestRepository;
import com.hmdp.ai.domain.knowledge.IndexVerificationResult;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.domain.knowledge.OutboxEvent;
import com.hmdp.ai.infra.AiLogSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

@Component
public class IndexBuildWorker {
    private static final String CONSUMER = "knowledge-shadow-index-builder";
    private static final int MAX_ATTEMPTS = 5;

    private final IndexBuildRequestRepository requests;
    private final KnowledgeRepository knowledge;
    private final KnowledgeIndexPort index;
    private final ObjectMapper mapper;
    private final String workerId;

    public IndexBuildWorker(IndexBuildRequestRepository requests, KnowledgeRepository knowledge,
                            KnowledgeIndexPort index, ObjectMapper mapper,
                            @Value("${hmdp.ai.knowledge.index-worker-id:}") String configuredWorkerId) {
        this.requests = requests;
        this.knowledge = knowledge;
        this.index = index;
        this.mapper = mapper;
        this.workerId = configuredWorkerId == null || configuredWorkerId.trim().isEmpty()
                ? ManagementFactory.getRuntimeMXBean().getName() : configuredWorkerId;
    }

    @Scheduled(fixedDelayString = "${hmdp.ai.knowledge.index-build-delay-ms:1000}")
    public void poll() {
        for (OutboxEvent event : requests.findAvailable(CONSUMER, 20)) {
            process(event);
        }
    }

    public void process(OutboxEvent event) {
        if (!requests.claim(event.getId(), CONSUMER, workerId, Duration.ofMinutes(5))) return;
        String jobId = null;
        try {
            JsonNode payload = mapper.readTree(event.getPayloadJson());
            jobId = required(payload, "jobId");
            String documentVersionId = required(payload, "documentVersionId");
            String documentId = required(payload, "documentId");
            String knowledgeBaseId = required(payload, "knowledgeBaseId");
            String indexVersion = required(payload, "indexVersion");
            int dimension = payload.path("embeddingDimension").asInt();
            List<KnowledgeChunk> chunks = knowledge.findIndexBuildChunks(event.getTenantId(),
                    event.getWorkspaceId(), indexVersion);
            validateChunks(chunks, dimension);
            index.ensureIndex(indexVersion, dimension);
            index.index(chunks);
            KnowledgeSearchScope scope = new KnowledgeSearchScope(event.getTenantId(), event.getWorkspaceId(),
                    knowledgeBaseId, indexVersion, "index-verifier");
            IndexVerificationResult verification = index.verify(scope, chunks);
            if (!verification.isValid()) throw new IllegalStateException("INDEX_VERIFICATION_FAILED");
            knowledge.completeIndexBuild(jobId, documentVersionId, documentId, indexVersion,
                    verification, "index-worker");
            requests.complete(event.getId(), CONSUMER);
        } catch (Exception e) {
            String reason = AiLogSanitizer.safe(e.getMessage(), 1000);
            boolean dead = requests.fail(event.getId(), CONSUMER, event.getPayloadJson(),
                    reason == null ? "INDEX_BUILD_FAILED" : reason, MAX_ATTEMPTS);
            if (dead && jobId != null) {
                knowledge.failJob(jobId, "INDEX_BUILD_DEAD_LETTER",
                        reason == null ? "index build exhausted retries" : reason);
            }
        }
    }

    private void validateChunks(List<KnowledgeChunk> chunks, int dimension) {
        if (dimension <= 0 || chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("INDEX_BUILD_CHUNKS_MISSING");
        }
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.getEmbedding() == null || chunk.getEmbedding().length != dimension
                    || chunk.getEmbeddingDimension() != dimension) {
                throw new IllegalStateException("INDEX_VECTOR_DIMENSION_MISMATCH");
            }
            boolean located = chunk.getPage() != null || chunk.getSheet() != null
                    || chunk.getSourceOffsetStart() != null || chunk.getHeadingPath() != null;
            if (!located) throw new IllegalStateException("INDEX_CITATION_LOCATION_MISSING");
        }
    }

    private String required(JsonNode payload, String name) {
        String value = payload.path(name).asText();
        if (value.isEmpty()) throw new IllegalArgumentException("INDEX_BUILD_PAYLOAD_INVALID");
        return value;
    }
}

package com.hmdp.ai.runtime.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.ChunkFragment;
import com.hmdp.ai.domain.knowledge.EmbeddingGateway;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParsingPort;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedFile;
import com.hmdp.ai.domain.knowledge.parsing.ParsedSection;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.infra.DocumentQualityLevel;
import com.hmdp.ai.infra.DocumentQualityProfile;
import com.hmdp.ai.runtime.knowledge.chunking.ChunkingStrategy;
import com.hmdp.ai.runtime.knowledge.chunking.ChunkingStrategyRegistry;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.text.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobWorkerTest {
    @Test
    void stagesChunksAndOutboxWithoutWritingRedisDirectly() throws Exception {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        ObjectStoragePort objects = mock(ObjectStoragePort.class);
        DocumentParsingPort parsers = mock(DocumentParsingPort.class);
        ChunkingStrategyRegistry registry = mock(ChunkingStrategyRegistry.class);
        ChunkingStrategy strategy = mock(ChunkingStrategy.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        KnowledgeIndexPort index = mock(KnowledgeIndexPort.class);
        PiiRedactionService pii = mock(PiiRedactionService.class);
        DocumentQualityAssessor quality = mock(DocumentQualityAssessor.class);
        ObjectMapper mapper = new ObjectMapper();
        IngestionJob job = new IngestionJob("job", "tenant", "workspace", "kb", "kbv", "document", "dv",
                IngestionStatus.CREATED, 0, 0, null, null, "{}");
        KnowledgeBaseVersion kb = new KnowledgeBaseVersion("kbv", "tenant", "workspace", "kb", 2,
                "embedding", 3,
                "{\"strategy\":\"RECURSIVE\",\"maxChars\":800,\"minChars\":100,\"overlapChars\":80}",
                "{}", "index-v2", "BUILDING", "DRAFT");
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion("dv", "tenant", "workspace", "kb",
                "document", 1, "object", "bucket", "policy.txt", "text/plain", 4, "sha", "DRAFT");
        ParsedDocument parsed = new ParsedDocument("Policy", "text/plain",
                Collections.singletonList(new ParsedSection("Policy", "safe policy text", 1,
                        Collections.singletonList("Policy"), 0, 16)), Collections.emptyList(),
                Collections.emptyList());
        when(repository.claimJob("job")).thenReturn(Optional.of(job));
        when(repository.findVersionById("kbv")).thenReturn(Optional.of(kb));
        when(repository.findDocumentVersion("dv")).thenReturn(Optional.of(version));
        when(objects.get("bucket", "object")).thenReturn(new ByteArrayInputStream("safe".getBytes()));
        when(parsers.parse(any(), eq("policy.txt"), eq("text/plain")))
                .thenReturn(new ParsedFile(parsed, "sha", "text/plain"));
        when(registry.require(any())).thenReturn(strategy);
        when(strategy.chunk(any(ParsedDocument.class), any())).thenReturn(Collections.singletonList(
                new ChunkFragment("safe policy text", 1, "Policy", "Policy", null,
                        null, null, null, null, 0, 16)));
        when(pii.redact(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(quality.assess(anyString(), eq(DocumentQualityProfile.GENERAL))).thenReturn(
                DocumentQualityAssessment.builder().profile(DocumentQualityProfile.GENERAL)
                        .level(DocumentQualityLevel.GOOD).score(0.9).build());
        when(embeddings.embed(eq("tenant"), eq("workspace"), eq("embedding"), anyList(), eq(3)))
                .thenReturn(Collections.singletonList(new float[]{1, 0, 0}));
        IngestionJobWorker worker = new IngestionJobWorker(repository, objects, parsers, registry, embeddings,
                index, new TextNormalizer(), pii, quality, new AiIdGenerator(), new ContentHashService(mapper),
                mapper, mock(ThreadPoolTaskExecutor.class));

        worker.process("job");

        verify(repository).stageIndexBuild(eq("job"), eq("dv"), eq("document"), anyList(), eq("worker"));
        org.mockito.Mockito.verifyNoInteractions(index);
    }
}

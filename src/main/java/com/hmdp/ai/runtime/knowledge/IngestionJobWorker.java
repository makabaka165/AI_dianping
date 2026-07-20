package com.hmdp.ai.runtime.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.ChunkFragment;
import com.hmdp.ai.domain.knowledge.ChunkingPolicy;
import com.hmdp.ai.domain.knowledge.EmbeddingGateway;
import com.hmdp.ai.domain.knowledge.IngestionDispatcher;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParsingPort;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedFile;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.infra.DocumentQualityProfile;
import com.hmdp.ai.runtime.knowledge.chunking.ChunkingStrategyRegistry;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.text.TextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class IngestionJobWorker implements IngestionDispatcher {
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private final KnowledgeRepository repository;
    private final ObjectStoragePort objects;
    private final DocumentParsingPort parsers;
    private final ChunkingStrategyRegistry strategies;
    private final EmbeddingGateway embeddings;
    private final KnowledgeIndexPort index;
    private final TextNormalizer normalizer;
    private final PiiRedactionService pii;
    private final StructuredDocumentRedactor structuredRedactor;
    private final DocumentQualityAssessor quality;
    private final AiIdGenerator ids;
    private final ContentHashService hashes;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor executor;

    @org.springframework.beans.factory.annotation.Autowired
    public IngestionJobWorker(KnowledgeRepository repository, ObjectStoragePort objects,
                              DocumentParsingPort parsers, ChunkingStrategyRegistry strategies,
                              EmbeddingGateway embeddings, KnowledgeIndexPort index, TextNormalizer normalizer,
                              PiiRedactionService pii, StructuredDocumentRedactor structuredRedactor,
                              DocumentQualityAssessor quality, AiIdGenerator ids,
                              ContentHashService hashes, ObjectMapper mapper,
                              @Qualifier("knowledgeIngestionExecutor") ThreadPoolTaskExecutor executor) {
        this.repository = repository;
        this.objects = objects;
        this.parsers = parsers;
        this.strategies = strategies;
        this.embeddings = embeddings;
        this.index = index;
        this.normalizer = normalizer;
        this.pii = pii;
        this.structuredRedactor = structuredRedactor;
        this.quality = quality;
        this.ids = ids;
        this.hashes = hashes;
        this.mapper = mapper;
        this.executor = executor;
    }

    public IngestionJobWorker(KnowledgeRepository repository, ObjectStoragePort objects,
                              DocumentParsingPort parsers, ChunkingStrategyRegistry strategies,
                              EmbeddingGateway embeddings, KnowledgeIndexPort index, TextNormalizer normalizer,
                              PiiRedactionService pii, DocumentQualityAssessor quality, AiIdGenerator ids,
                              ContentHashService hashes, ObjectMapper mapper,
                              ThreadPoolTaskExecutor executor) {
        this(repository, objects, parsers, strategies, embeddings, index, normalizer, pii,
                new StructuredDocumentRedactor(), quality, ids, hashes, mapper, executor);
    }

    @Override
    public void submit(String jobId) {
        try { executor.execute(() -> process(jobId)); }
        catch (Exception e) { repository.failJob(jobId, "INGESTION_QUEUE_FULL", "knowledge ingestion queue is full"); }
    }

    public void recover() {
        for (IngestionJob job : repository.findRecoverableJobs(100)) submit(job.getId());
    }

    public void process(String jobId) {
        Optional<IngestionJob> claimed = repository.claimJob(jobId);
        if (!claimed.isPresent()) return;
        IngestionJob job = claimed.get();
        try {
            KnowledgeBaseVersion kb = repository.findVersionById(job.getKnowledgeBaseVersionId())
                    .orElseThrow(() -> new IllegalStateException("KNOWLEDGE_VERSION_NOT_FOUND"));
            KnowledgeDocumentVersion version = repository.findDocumentVersion(job.getDocumentVersionId())
                    .orElseThrow(() -> new IllegalStateException("DOCUMENT_VERSION_NOT_FOUND"));
            repository.updateJob(jobId, IngestionStatus.PARSING, 15, "{}");
            byte[] bytes;
            try (InputStream input = objects.get(version.getBucket(), version.getObjectKey())) {
                bytes = input.readAllBytes();
            }
            ParsedFile parsed = parsers.parse(bytes, version.getOriginalFileName(), version.getContentType());
            ParsedDocument document = parsed.getDocument();
            repository.updateJob(jobId, IngestionStatus.NORMALIZING, 30, "{}");
            String plain = normalizer.normalize(document.getPlainText());
            repository.updateJob(jobId, IngestionStatus.REDACTING, 38, "{}");
            ParsedDocument redactedDocument = structuredRedactor.redact(document);
            String redacted = pii.redact(normalizer.normalize(redactedDocument.getPlainText()));
            DocumentQualityAssessment assessment = quality.assess(redacted, DocumentQualityProfile.GENERAL);
            repository.saveParsed(version.getId(), redactedDocument.getTitle(), redacted,
                    mapper.writeValueAsString(redactedDocument), mapper.writeValueAsString(redactedDocument.getWarnings()),
                    assessment.getScore(), mapper.writeValueAsString(assessment));
            repository.updateJob(jobId, IngestionStatus.CHUNKING, 45, "{}");
            ChunkingPolicy policy = ChunkingPolicy.parse(kb.getChunkingPolicyJson(), mapper);
            List<ChunkFragment> fragments = strategies.require(policy).chunk(redactedDocument, policy);
            if (fragments.isEmpty()) throw new IllegalStateException("DOCUMENT_CHUNKS_EMPTY");
            List<String> texts = new ArrayList<>();
            for (ChunkFragment fragment : fragments) {
                texts.add(pii.redact(normalizer.normalize(fragment.getText())));
            }
            repository.updateJob(jobId, IngestionStatus.EMBEDDING, 55, stats("chunkCount", texts.size()));
            List<float[]> vectors = embed(job, kb, texts);
            List<KnowledgeChunk> chunks = chunks(job, kb, version, redactedDocument, assessment, fragments, texts, vectors);
            repository.updateJob(jobId, IngestionStatus.INDEXING, 80, stats("chunkCount", chunks.size()));
            repository.stageIndexBuild(jobId, version.getId(), job.getDocumentId(), chunks, "worker");
        } catch (Exception e) {
            String message = AiLogSanitizer.safe(e.getMessage(), 500);
            repository.failJob(jobId, errorCode(e), message == null ? "knowledge ingestion failed" : message);
            log.warn("Knowledge ingestion failed, jobId={}, errorCode={}", jobId, errorCode(e), e);
        }
    }

    private List<float[]> embed(IngestionJob job, KnowledgeBaseVersion kb, List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        for (int offset = 0; offset < texts.size(); offset += EMBEDDING_BATCH_SIZE) {
            List<String> batch = texts.subList(offset, Math.min(texts.size(), offset + EMBEDDING_BATCH_SIZE));
            vectors.addAll(embeddings.embed(job.getTenantId(), job.getWorkspaceId(),
                    kb.getEmbeddingModelProfileId(), batch, kb.getEmbeddingDimension()));
            repository.updateJob(job.getId(), IngestionStatus.EMBEDDING,
                    55 + (int) (20.0 * vectors.size() / texts.size()), stats("embedded", vectors.size()));
        }
        return vectors;
    }

    private List<KnowledgeChunk> chunks(IngestionJob job, KnowledgeBaseVersion kb,
                                        KnowledgeDocumentVersion version, ParsedDocument document,
                                        DocumentQualityAssessment assessment, List<ChunkFragment> fragments,
                                        List<String> texts, List<float[]> vectors) throws Exception {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            ChunkFragment fragment = fragments.get(i);
            String text = texts.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("title", document.getTitle());
            metadata.put("mimeType", document.getMimeType());
            chunks.add(new KnowledgeChunk(ids.nextId(), job.getTenantId(), job.getWorkspaceId(),
                    job.getKnowledgeBaseId(), kb.getVersion(), job.getDocumentId(), version.getVersion(),
                    version.getId(), kb.getIndexVersion(), i, text, normalizer.searchText(text),
                    hashes.sha256(text), kb.getEmbeddingDimension(), vectors.get(i), assessment.getScore(),
                    version.getContentType(), fragment.getPage(), fragment.getSection(), fragment.getHeadingPath(),
                    fragment.getSheet(), fragment.getRowStart(), fragment.getRowEnd(), fragment.getColumnStart(),
                    fragment.getColumnEnd(), fragment.getSourceOffsetStart(), fragment.getSourceOffsetEnd(),
                    mapper.writeValueAsString(metadata)));
        }
        return chunks;
    }

    private String stats(String key, Object value) {
        try { return mapper.writeValueAsString(Collections.singletonMap(key, value)); }
        catch (Exception e) { return "{}"; }
    }

    private String errorCode(Exception e) {
        String value = String.valueOf(e.getMessage());
        return value.matches("[A-Z0-9_]+") ? value : "KNOWLEDGE_INGESTION_FAILED";
    }
}

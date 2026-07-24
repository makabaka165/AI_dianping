package com.hmdp.ai.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.knowledge.CreateKnowledgeBaseRequest;
import com.hmdp.ai.application.dto.knowledge.CreateKnowledgeBaseVersionRequest;
import com.hmdp.ai.application.dto.knowledge.IngestionCreatedResponse;
import com.hmdp.ai.application.dto.knowledge.IngestionJobResponse;
import com.hmdp.ai.application.dto.knowledge.KnowledgeBaseResponse;
import com.hmdp.ai.application.dto.knowledge.KnowledgeBaseVersionResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionDispatcher;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.ChunkingPolicy;
import com.hmdp.ai.domain.knowledge.KnowledgeBase;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeDocument;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgePublishValidator;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.domain.knowledge.parsing.DocumentInspectionPort;
import com.hmdp.ai.domain.knowledge.parsing.FileInspection;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.shared.validation.ValidationResult;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KnowledgeIngestionApplicationService {
    private final KnowledgeRepository repository;
    private final ObjectStoragePort objects;
    private final DocumentInspectionPort parsers;
    private final IngestionDispatcher worker;
    private final KnowledgeIndexPort index;
    private final KnowledgePublishValidator publishValidator;
    private final AiAccessGuard access;
    private final AiIdGenerator ids;
    private final ContentHashService hashes;
    private final ObjectMapper mapper;

    public KnowledgeIngestionApplicationService(KnowledgeRepository repository, ObjectStoragePort objects,
                                                DocumentInspectionPort parsers, IngestionDispatcher worker,
                                                KnowledgeIndexPort index, KnowledgePublishValidator publishValidator,
                                                AiAccessGuard access,
                                                AiIdGenerator ids, ContentHashService hashes, ObjectMapper mapper) {
        this.repository = repository;
        this.objects = objects;
        this.parsers = parsers;
        this.worker = worker;
        this.index = index;
        this.publishValidator = publishValidator;
        this.access = access;
        this.ids = ids;
        this.hashes = hashes;
        this.mapper = mapper;
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_WRITE);
        KnowledgeBase knowledgeBase = new KnowledgeBase(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(),
                request.getDescription(), 0, "ACTIVE");
        return new KnowledgeBaseResponse(repository.createKnowledgeBase(knowledgeBase, context.getUserId()));
    }

    public PageResponse<KnowledgeBaseResponse> list(int page, int size) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_READ);
        int offset = Math.multiplyExact(page - 1, size);
        List<KnowledgeBaseResponse> items = repository.findKnowledgeBases(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), offset, size).stream()
                .map(KnowledgeBaseResponse::new).collect(Collectors.toList());
        return new PageResponse<>(items, repository.countKnowledgeBases(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId()), page, size);
    }

    @Transactional
    public KnowledgeBaseVersionResponse createVersion(String knowledgeBaseId,
                                                      CreateKnowledgeBaseVersionRequest request) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_WRITE);
        requireKnowledgeBase(context, knowledgeBaseId);
        ChunkingPolicy.parse(request.getChunkingPolicyJson(), mapper);
        readJson(request.getRetrievalPolicyJson(), "retrievalPolicyJson");
        int version = repository.lockAndNextKnowledgeVersion(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), knowledgeBaseId);
        String indexVersion = "kb-" + knowledgeBaseId + "-v" + version;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("embeddingModelProfileId", request.getEmbeddingModelProfileId());
        content.put("embeddingDimension", request.getEmbeddingDimension());
        content.put("chunkingPolicy", request.getChunkingPolicyJson());
        content.put("retrievalPolicy", request.getRetrievalPolicyJson());
        KnowledgeBaseVersion value = new KnowledgeBaseVersion(ids.nextId(),
                context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), knowledgeBaseId,
                version, request.getEmbeddingModelProfileId(), request.getEmbeddingDimension(),
                request.getChunkingPolicyJson(), request.getRetrievalPolicyJson(), indexVersion,
                "BUILDING", "DRAFT");
        return new KnowledgeBaseVersionResponse(repository.createKnowledgeBaseVersion(value,
                hashes.sha256(content), request.getChangeNote(), context.getUserId()));
    }

    @Transactional
    public KnowledgeBaseVersionResponse publishVersion(String knowledgeBaseId, int version) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_PUBLISH);
        KnowledgeBaseVersion ready = repository.findVersionNumber(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), knowledgeBaseId, version)
                .orElseThrow(() -> new IllegalArgumentException("knowledge version not found"));
        ValidationResult validation = publishValidator.validate(ready,
                repository.isEmbeddingModelUsable(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), ready.getEmbeddingModelProfileId()),
                repository.countIncompleteJobs(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), ready.getId()));
        if (!validation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                    "knowledge version cannot be published", validation.getIssues());
        }
        return new KnowledgeBaseVersionResponse(repository.publishKnowledgeBaseVersion(
                context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), knowledgeBaseId,
                version, context.getUserId()));
    }

    @Transactional
    public IngestionCreatedResponse upload(String knowledgeBaseId, Integer knowledgeBaseVersion, String title,
                                           String fileName, String declaredMime, byte[] bytes) {
        return uploadWithContext(access.require(AiPermission.KNOWLEDGE_WRITE), knowledgeBaseId,
                knowledgeBaseVersion, title, fileName, declaredMime, bytes);
    }

    @Transactional
    public IngestionCreatedResponse uploadSystem(String tenantId, String workspaceId, String knowledgeBaseId,
                                                 Integer knowledgeBaseVersion, String title, String fileName,
                                                 String declaredMime, byte[] bytes) {
        AiSecurityContext system = new AiSecurityContext("system", new TenantContext(tenantId),
                new WorkspaceContext(workspaceId), new AuthorizationContext(EnumSet.of(AiPermission.ADMIN)), false);
        return uploadWithContext(system, knowledgeBaseId, knowledgeBaseVersion, title, fileName, declaredMime, bytes);
    }

    public IngestionJobResponse job(String jobId) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_READ);
        return new IngestionJobResponse(repository.findJob(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), jobId)
                .orElseThrow(() -> new IllegalArgumentException("ingestion job not found")));
    }

    @Transactional
    public IngestionCreatedResponse uploadDocumentVersion(String documentId, Integer knowledgeBaseVersion,
                                                          String title, String changeNote, String fileName,
                                                          String declaredMime, byte[] bytes) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_WRITE);
        KnowledgeDocument document = repository.findDocument(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found"));
        StoredObject object = null;
        try {
            KnowledgeBaseVersion kb = resolveIngestionVersion(context, document.getKnowledgeBaseId(),
                    knowledgeBaseVersion);
            FileInspection inspection = parsers.inspect(bytes, fileName, declaredMime);
            Optional<KnowledgeDocumentVersion> duplicate = repository.findBySha(document.getTenantId(),
                    document.getWorkspaceId(), document.getKnowledgeBaseId(), inspection.getSha256());
            if (duplicate.isPresent()) {
                if (!documentId.equals(duplicate.get().getDocumentId())) {
                    throw new IllegalArgumentException("DUPLICATE_DOCUMENT_CONTENT");
                }
                return reuseOrReindex(context, kb, duplicate.get());
            }
            object = objects.put(document.getTenantId(), document.getKnowledgeBaseId(), fileName,
                    inspection.getMimeType(), bytes, inspection.getSha256());
            int nextVersion = repository.lockAndNextDocumentVersion(document.getTenantId(),
                    document.getWorkspaceId(), documentId);
            KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(ids.nextId(), document.getTenantId(),
                    document.getWorkspaceId(), document.getKnowledgeBaseId(), documentId, nextVersion,
                    object.getObjectKey(), object.getBucket(), object.getOriginalFileName(), object.getContentType(),
                    object.getSize(), object.getSha256(), "DRAFT");
            IngestionJob job = new IngestionJob(ids.nextId(), document.getTenantId(), document.getWorkspaceId(),
                    document.getKnowledgeBaseId(), kb.getId(), documentId, version.getId(),
                    IngestionStatus.CREATED, 0, 0, null, null, "{}");
            KnowledgeRepository.UploadRegistration registered = repository.registerDocumentVersion(version, job,
                    blank(changeNote) ? "Document version " + nextVersion : changeNote.trim(), context.getUserId());
            submitAfterCommit(job.getId());
            return new IngestionCreatedResponse(registered.getDocumentId(), registered.getDocumentVersionId(),
                    registered.getJobId(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (RuntimeException e) {
            if (object != null) objects.delete(object.getBucket(), object.getObjectKey());
            throw e;
        }
    }

    @Transactional
    public IngestionCreatedResponse reindex(String documentId, Integer knowledgeBaseVersion) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_WRITE);
        KnowledgeDocument document = repository.findDocument(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found"));
        KnowledgeDocumentVersion version = repository.findCurrentDocumentVersion(document.getTenantId(),
                        document.getWorkspaceId(), documentId)
                .orElseThrow(() -> new IllegalArgumentException("document version not found"));
        KnowledgeBaseVersion kb = resolveIngestionVersion(context, document.getKnowledgeBaseId(),
                knowledgeBaseVersion);
        return reuseOrReindex(context, kb, version);
    }

    @Transactional
    public void deleteDocument(String documentId) {
        AiSecurityContext context = access.require(AiPermission.KNOWLEDGE_WRITE);
        if (repository.isDocumentBoundToImmutableVersion(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), documentId)) {
            throw new IllegalArgumentException("PUBLISHED_KNOWLEDGE_VERSION_IMMUTABLE");
        }
        List<String> ids = repository.findChunkIds(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), documentId);
        List<KnowledgeChunk> chunks = repository.findChunksByIds(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), ids);
        List<StoredObject> storedObjects = repository.findDocumentObjects(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), documentId);
        if (!repository.deleteDocument(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), documentId, context.getUserId())) {
            throw new IllegalArgumentException("document not found");
        }
        afterCommit(() -> {
            chunks.stream().collect(Collectors.groupingBy(KnowledgeChunk::getIndexVersion))
                    .forEach((version, values) -> index.delete(version,
                            values.stream().map(KnowledgeChunk::getId).collect(Collectors.toList())));
            storedObjects.forEach(object -> objects.delete(object.getBucket(), object.getObjectKey()));
        });
    }

    private IngestionCreatedResponse uploadWithContext(AiSecurityContext context, String knowledgeBaseId,
                                                       Integer knowledgeBaseVersion, String title, String fileName,
                                                       String declaredMime, byte[] bytes) {
        StoredObject object = null;
        try {
            KnowledgeBaseVersion kb = resolveIngestionVersion(context, knowledgeBaseId, knowledgeBaseVersion);
            FileInspection inspection = parsers.inspect(bytes, fileName, declaredMime);
            Optional<KnowledgeDocumentVersion> duplicate = repository.findBySha(
                    context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), knowledgeBaseId,
                    inspection.getSha256());
            if (duplicate.isPresent()) {
                return reuseOrReindex(context, kb, duplicate.get());
            }
            object = objects.put(context.getTenant().getTenantId(), knowledgeBaseId, fileName,
                    inspection.getMimeType(), bytes, inspection.getSha256());
            String documentId = ids.nextId();
            String versionId = ids.nextId();
            String jobId = ids.nextId();
            KnowledgeDocument document = new KnowledgeDocument(documentId, context.getTenant().getTenantId(),
                    context.getWorkspace().getWorkspaceId(), knowledgeBaseId,
                    "doc-" + inspection.getSha256().substring(0, 20), blank(title) ? fileName : title.trim(),
                    "UPLOAD", 1, "PROCESSING");
            KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(versionId, document.getTenantId(),
                    document.getWorkspaceId(), knowledgeBaseId, documentId, 1, object.getObjectKey(),
                    object.getBucket(), object.getOriginalFileName(), object.getContentType(), object.getSize(),
                    object.getSha256(), "DRAFT");
            IngestionJob job = new IngestionJob(jobId, document.getTenantId(), document.getWorkspaceId(),
                    knowledgeBaseId, kb.getId(), documentId, versionId, IngestionStatus.CREATED, 0, 0,
                    null, null, "{}");
            KnowledgeRepository.UploadRegistration registered = repository.registerUpload(document, version, job,
                    context.getUserId());
            submitAfterCommit(jobId);
            return new IngestionCreatedResponse(registered.getDocumentId(), registered.getDocumentVersionId(),
                    registered.getJobId(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (RuntimeException e) {
            if (object != null) objects.delete(object.getBucket(), object.getObjectKey());
            throw e;
        }
    }

    private KnowledgeBase requireKnowledgeBase(AiSecurityContext context, String knowledgeBaseId) {
        return repository.findKnowledgeBase(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge base not found"));
    }

    private KnowledgeBaseVersion resolveIngestionVersion(AiSecurityContext context, String knowledgeBaseId,
                                                         Integer version) {
        if (version == null) {
            throw new IllegalArgumentException("KNOWLEDGE_DRAFT_VERSION_REQUIRED");
        }
        KnowledgeBaseVersion target = repository.findVersionNumber(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), knowledgeBaseId, version)
                .orElseThrow(() -> new IllegalArgumentException("knowledge base version not found"));
        if (!"DRAFT".equals(target.getStatus())) {
            throw new IllegalArgumentException("knowledge base version cannot accept ingestion");
        }
        return target;
    }

    private IngestionCreatedResponse reuseOrReindex(AiSecurityContext context, KnowledgeBaseVersion kb,
                                                    KnowledgeDocumentVersion version) {
        Optional<IngestionJob> existing = repository.findJobByDocumentVersionAndKnowledgeVersion(
                version.getId(), kb.getId());
        if (existing.isPresent() && existing.get().getStatus() != IngestionStatus.FAILED &&
                existing.get().getStatus() != IngestionStatus.CANCELLED) {
            return new IngestionCreatedResponse(version.getDocumentId(), version.getId(),
                    existing.get().getId(), true);
        }
        IngestionJob job = new IngestionJob(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), version.getKnowledgeBaseId(), kb.getId(),
                version.getDocumentId(), version.getId(), IngestionStatus.CREATED, 0, 0,
                null, null, "{}");
        repository.registerReindex(job, context.getUserId());
        submitAfterCommit(job.getId());
        return new IngestionCreatedResponse(version.getDocumentId(), version.getId(), job.getId(), true);
    }

    private void submitAfterCommit(String jobId) {
        afterCommit(() -> worker.submit(jobId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void readJson(String value, String field) {
        try { mapper.readTree(value); }
        catch (Exception e) { throw new IllegalArgumentException(field + " is invalid JSON", e); }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}

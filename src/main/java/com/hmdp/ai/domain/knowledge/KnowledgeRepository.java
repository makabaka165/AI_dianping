package com.hmdp.ai.domain.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeRepository {
    KnowledgeBase createKnowledgeBase(KnowledgeBase knowledgeBase, String actorId);
    Optional<KnowledgeBase> findKnowledgeBase(String tenantId, String workspaceId, String knowledgeBaseId);
    List<KnowledgeBase> findKnowledgeBases(String tenantId, String workspaceId, int offset, int limit);
    long countKnowledgeBases(String tenantId, String workspaceId);

    int lockAndNextKnowledgeVersion(String tenantId, String workspaceId, String knowledgeBaseId);
    KnowledgeBaseVersion createKnowledgeBaseVersion(KnowledgeBaseVersion version, String contentHash,
                                                    String changeNote, String actorId);
    KnowledgeBaseVersion publishKnowledgeBaseVersion(String tenantId, String workspaceId,
                                                     String knowledgeBaseId, int version, String actorId);
    Optional<KnowledgeBaseVersion> findPublishedVersion(String tenantId, String workspaceId,
                                                        String knowledgeBaseId, Integer version);
    Optional<KnowledgeBaseVersion> findVersionById(String versionId);
    Optional<KnowledgeBaseVersion> findVersionNumber(String tenantId, String workspaceId,
                                                     String knowledgeBaseId, int version);
    boolean isEmbeddingModelUsable(String tenantId, String workspaceId, String modelProfileId);
    void markIndexReady(String tenantId, String workspaceId, String knowledgeBaseId,
                        String indexVersion, String actorId);
    long countIncompleteJobs(String tenantId, String workspaceId, String knowledgeBaseVersionId);

    Optional<KnowledgeDocument> findDocument(String tenantId, String workspaceId, String documentId);
    Optional<KnowledgeDocumentVersion> findCurrentDocumentVersion(String tenantId, String workspaceId,
                                                                  String documentId);
    Optional<KnowledgeDocumentVersion> findDocumentVersion(String documentVersionId);
    Optional<KnowledgeDocumentVersion> findBySha(String tenantId, String workspaceId,
                                                 String knowledgeBaseId, String sha256);
    List<StoredObject> findDocumentObjects(String tenantId, String workspaceId, String documentId);
    int lockAndNextDocumentVersion(String tenantId, String workspaceId, String documentId);
    UploadRegistration registerUpload(KnowledgeDocument document, KnowledgeDocumentVersion version,
                                      IngestionJob job, String actorId);
    UploadRegistration registerDocumentVersion(KnowledgeDocumentVersion version, IngestionJob job,
                                                String changeNote, String actorId);
    IngestionJob registerReindex(IngestionJob job, String actorId);
    Optional<IngestionJob> findJobByDocumentVersionAndKnowledgeVersion(String documentVersionId,
                                                                       String knowledgeBaseVersionId);
    Map<String, List<String>> findDocumentReadPrincipals(String tenantId, String workspaceId,
                                                        List<String> documentIds);

    Optional<IngestionJob> findJob(String tenantId, String workspaceId, String jobId);
    Optional<IngestionJob> findJobByDocumentVersion(String documentVersionId);
    Optional<IngestionJob> claimJob(String jobId);
    List<IngestionJob> findRecoverableJobs(int limit);
    void updateJob(String jobId, IngestionStatus status, int progress, String statisticsJson);
    void failJob(String jobId, String errorCode, String errorMessage);
    void saveParsed(String documentVersionId, String title, String plainText, String structureJson,
                    String warningsJson, double qualityScore, String qualityJson);
    List<String> findChunkIds(String documentVersionId, String indexVersion);
    void replaceChunks(String documentVersionId, List<KnowledgeChunk> chunks, String actorId);
    void stageIndexBuild(String jobId, String documentVersionId, String documentId,
                         List<KnowledgeChunk> chunks, String actorId);
    List<KnowledgeChunk> findIndexBuildChunks(String tenantId, String workspaceId, String indexVersion);
    void completeIndexBuild(String jobId, String documentVersionId, String documentId,
                            String indexVersion, IndexVerificationResult verification, String actorId);
    List<String> findExpiredInactiveIndexes(int limit);
    void markIndexDeleted(String indexVersion, String actorId);
    void publishIngestion(String jobId, String documentVersionId, String documentId, String actorId);
    List<KnowledgeChunk> findChunksByIds(String tenantId, String workspaceId, List<String> chunkIds);
    boolean deleteDocument(String tenantId, String workspaceId, String documentId, String actorId);
    boolean isDocumentBoundToImmutableVersion(String tenantId, String workspaceId, String documentId);
    List<String> findChunkIds(String tenantId, String workspaceId, String documentId);

    final class UploadRegistration {
        private final String documentId;
        private final String documentVersionId;
        private final String jobId;

        public UploadRegistration(String documentId, String documentVersionId, String jobId) {
            this.documentId = documentId;
            this.documentVersionId = documentVersionId;
            this.jobId = jobId;
        }

        public String getDocumentId() { return documentId; }
        public String getDocumentVersionId() { return documentVersionId; }
        public String getJobId() { return jobId; }
    }
}

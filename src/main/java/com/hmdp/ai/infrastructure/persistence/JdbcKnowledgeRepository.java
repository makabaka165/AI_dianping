package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.KnowledgeBase;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeDocument;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.infrastructure.vector.RedisKnowledgeIndexNaming;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcKnowledgeRepository implements KnowledgeRepository {
    private static final String KNOWLEDGE_VERSION_COLUMNS =
            "id,tenant_id,workspace_id,knowledge_base_id,version,embedding_model_profile_id," +
                    "embedding_dimension,chunking_policy_json,retrieval_policy_json,index_version,index_status,status";
    private static final String DOCUMENT_VERSION_COLUMNS =
            "id,tenant_id,workspace_id,knowledge_base_id,document_id,version,object_key,bucket," +
                    "original_file_name,content_type,size_bytes,sha256,status";
    private static final String JOB_COLUMNS =
            "id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version_id,document_id," +
                    "document_version_id,status,progress,attempt,error_code,error_message,statistics_json";
    private static final String CHUNK_COLUMNS =
            "id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version,document_id," +
                    "document_version,document_version_id,index_version,ordinal_no,content,search_text," +
                    "content_hash,embedding_dimension,embedding,quality_score,source_type,page_no,section_name," +
                    "heading_path,sheet_name,row_start,row_end,column_start,column_end,source_offset_start," +
                    "source_offset_end,metadata_json";

    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public JdbcKnowledgeRepository(JdbcTemplate jdbc, AiIdGenerator ids, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.mapper = mapper;
    }

    @Override
    public KnowledgeBase createKnowledgeBase(KnowledgeBase kb, String actor) {
        jdbc.update("insert into ai_knowledge_base " +
                        "(id,tenant_id,workspace_id,code,name,description,latest_version,status,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,0,'ACTIVE',?,?)",
                kb.getId(), kb.getTenantId(), kb.getWorkspaceId(), kb.getCode(), kb.getName(),
                kb.getDescription(), actor, actor);
        return kb;
    }

    @Override
    public Optional<KnowledgeBase> findKnowledgeBase(String tenant, String workspace, String id) {
        return jdbc.query("select id,tenant_id,workspace_id,code,name,description,latest_version,status " +
                        "from ai_knowledge_base where tenant_id=? and workspace_id=? and id=? and deleted=0",
                (rs, row) -> knowledgeBase(rs), tenant, workspace, id).stream().findFirst();
    }

    @Override
    public List<KnowledgeBase> findKnowledgeBases(String tenant, String workspace, int offset, int limit) {
        return jdbc.query("select id,tenant_id,workspace_id,code,name,description,latest_version,status " +
                        "from ai_knowledge_base where tenant_id=? and workspace_id=? and deleted=0 " +
                        "order by updated_at desc,id limit ? offset ?",
                (rs, row) -> knowledgeBase(rs), tenant, workspace, limit, offset);
    }

    @Override
    public long countKnowledgeBases(String tenant, String workspace) {
        Long value = jdbc.queryForObject("select count(*) from ai_knowledge_base " +
                "where tenant_id=? and workspace_id=? and deleted=0", Long.class, tenant, workspace);
        return value == null ? 0 : value;
    }

    @Override
    @Transactional
    public int lockAndNextKnowledgeVersion(String tenant, String workspace, String id) {
        int updated = jdbc.update("update ai_knowledge_base set latest_version=last_insert_id(latest_version+1) " +
                        "where tenant_id=? and workspace_id=? and id=? and status='ACTIVE' and deleted=0",
                tenant, workspace, id);
        if (updated != 1) throw new IllegalStateException("KNOWLEDGE_BASE_NOT_FOUND");
        Integer value = jdbc.queryForObject("select last_insert_id()", Integer.class);
        return value == null ? 0 : value;
    }

    @Override
    @Transactional
    public KnowledgeBaseVersion createKnowledgeBaseVersion(KnowledgeBaseVersion value, String hash,
                                                            String note, String actor) {
        jdbc.update("insert into ai_knowledge_base_version " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,version,embedding_model_profile_id," +
                        "embedding_dimension,chunking_policy_json,retrieval_policy_json,index_version,index_status," +
                        "status,content_hash,change_note,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,?,?,?,'BUILDING','DRAFT',?,?,?,?)",
                value.getId(), value.getTenantId(), value.getWorkspaceId(), value.getKnowledgeBaseId(),
                value.getVersion(), value.getEmbeddingModelProfileId(), value.getEmbeddingDimension(),
                value.getChunkingPolicyJson(), value.getRetrievalPolicyJson(), value.getIndexVersion(),
                hash, note, actor, actor);
        jdbc.update("insert into ai_index_version " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version,code," +
                        "embedding_model_profile_id,embedding_dimension,vector_index_name,lexical_index_name," +
                        "status,active,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,'BUILDING',0,?,?)",
                ids.nextId(), value.getTenantId(), value.getWorkspaceId(), value.getKnowledgeBaseId(),
                value.getVersion(), value.getIndexVersion(), value.getEmbeddingModelProfileId(),
                value.getEmbeddingDimension(), RedisKnowledgeIndexNaming.indexName(value.getIndexVersion()),
                RedisKnowledgeIndexNaming.indexName(value.getIndexVersion()), actor, actor);
        return value;
    }

    @Override
    @Transactional
    public KnowledgeBaseVersion publishKnowledgeBaseVersion(String tenant, String workspace,
                                                             String knowledgeBaseId, int version, String actor) {
        KnowledgeBaseVersion target = findVersionNumber(tenant, workspace, knowledgeBaseId, version)
                .filter(value -> "DRAFT".equals(value.getStatus()) && "READY".equals(value.getIndexStatus()))
                .orElseThrow(() -> new IllegalStateException("KNOWLEDGE_VERSION_NOT_READY"));
        jdbc.update("update ai_index_version set active=0,updated_by=? " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and deleted=0",
                actor, tenant, workspace, knowledgeBaseId);
        jdbc.update("update ai_index_version set rollback_after=?,updated_by=? " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and code<>? " +
                        "and status='READY' and deleted=0",
                Timestamp.from(Instant.now().plusSeconds(86400)), actor, tenant, workspace,
                knowledgeBaseId, target.getIndexVersion());
        int activated = jdbc.update("update ai_index_version set active=1,activated_at=?,updated_by=? " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and code=? " +
                        "and status='READY' and deleted=0",
                Timestamp.from(Instant.now()), actor, tenant, workspace, knowledgeBaseId,
                target.getIndexVersion());
        if (activated != 1) throw new IllegalStateException("KNOWLEDGE_INDEX_NOT_READY");
        jdbc.update("update ai_knowledge_base set active_index_version=?,updated_by=? where tenant_id=? " +
                        "and workspace_id=? and id=? and deleted=0",
                target.getIndexVersion(), actor, tenant, workspace, knowledgeBaseId);
        jdbc.update("update ai_knowledge_base_version set status='ARCHIVED',updated_by=? where tenant_id=? " +
                        "and workspace_id=? and knowledge_base_id=? and status='PUBLISHED' and id<>? " +
                        "and deleted=0", actor, tenant, workspace, knowledgeBaseId, target.getId());
        int published = jdbc.update("update ai_knowledge_base_version set status='PUBLISHED',published_at=?," +
                        "published_by=?,updated_by=? where id=? and status='DRAFT' and index_status='READY'",
                Timestamp.from(Instant.now()), actor, actor, target.getId());
        if (published != 1) throw new IllegalStateException("KNOWLEDGE_PUBLISH_FAILED");
        return findPublishedVersion(tenant, workspace, knowledgeBaseId, version)
                .orElseThrow(() -> new IllegalStateException("KNOWLEDGE_PUBLISH_FAILED"));
    }

    @Override
    public Optional<KnowledgeBaseVersion> findPublishedVersion(String tenant, String workspace,
                                                               String knowledgeBaseId, Integer version) {
        String sql = "select " + KNOWLEDGE_VERSION_COLUMNS + " from ai_knowledge_base_version " +
                "where tenant_id=? and workspace_id=? and knowledge_base_id=? and status='PUBLISHED' " +
                "and index_status='READY' and deleted=0" +
                (version == null ? " order by version desc limit 1" : " and version=?");
        List<KnowledgeBaseVersion> values = version == null
                ? jdbc.query(sql, (rs, row) -> knowledgeBaseVersion(rs), tenant, workspace, knowledgeBaseId)
                : jdbc.query(sql, (rs, row) -> knowledgeBaseVersion(rs), tenant, workspace, knowledgeBaseId, version);
        return values.stream().findFirst();
    }

    @Override
    public Optional<KnowledgeBaseVersion> findVersionById(String id) {
        return jdbc.query("select " + KNOWLEDGE_VERSION_COLUMNS + " from ai_knowledge_base_version " +
                        "where id=? and status in ('DRAFT','BUILDING','PUBLISHED','ARCHIVED','FAILED') and deleted=0",
                (rs, row) -> knowledgeBaseVersion(rs), id).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeBaseVersion> findVersionNumber(String tenant, String workspace,
                                                            String knowledgeBaseId, int version) {
        return jdbc.query("select " + KNOWLEDGE_VERSION_COLUMNS + " from ai_knowledge_base_version " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and version=? and deleted=0",
                (rs, row) -> knowledgeBaseVersion(rs), tenant, workspace, knowledgeBaseId, version)
                .stream().findFirst();
    }

    @Override
    public boolean isEmbeddingModelUsable(String tenant, String workspace, String modelProfileId) {
        Long count = jdbc.queryForObject("select count(*) from ai_model_profile where tenant_id=? and " +
                        "workspace_id=? and id=? and model_type='EMBEDDING' and enabled=1 and status='ACTIVE' " +
                        "and (secret_ref like 'env:%' or secret_ref like 'vault:%' or secret_ref like 'secret:%') " +
                        "and deleted=0",
                Long.class, tenant, workspace, modelProfileId);
        return count != null && count == 1;
    }

    @Override
    @Transactional
    public void markIndexReady(String tenant, String workspace, String knowledgeBaseId,
                               String indexVersion, String actor) {
        int updated = jdbc.update("update ai_index_version set status='READY',updated_by=? " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and code=? and deleted=0",
                actor, tenant, workspace, knowledgeBaseId, indexVersion);
        if (updated != 1) throw new IllegalStateException("KNOWLEDGE_INDEX_NOT_FOUND");
        jdbc.update("update ai_knowledge_base_version set index_status='READY',updated_by=? " +
                        "where tenant_id=? and workspace_id=? and knowledge_base_id=? and index_version=? " +
                        "and status='DRAFT' and deleted=0",
                actor, tenant, workspace, knowledgeBaseId, indexVersion);
    }

    @Override
    public long countIncompleteJobs(String tenant, String workspace, String knowledgeBaseVersionId) {
        Long count = jdbc.queryForObject("select count(*) from ai_ingestion_job where tenant_id=? and " +
                        "workspace_id=? and knowledge_base_version_id=? and status not in ('PUBLISHED','CANCELLED') " +
                        "and deleted=0",
                Long.class, tenant, workspace, knowledgeBaseVersionId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String tenant, String workspace, String documentId) {
        return jdbc.query("select id,tenant_id,workspace_id,knowledge_base_id,code,title,source_type," +
                        "current_version,status from ai_document where tenant_id=? and workspace_id=? and id=? " +
                        "and deleted=0",
                (rs, row) -> knowledgeDocument(rs), tenant, workspace, documentId).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findCurrentDocumentVersion(String tenant, String workspace,
                                                                         String documentId) {
        return jdbc.query("select v." + DOCUMENT_VERSION_COLUMNS.replace(",", ",v.") +
                        " from ai_document d join ai_document_version v on v.document_id=d.id " +
                        "and v.version=d.current_version and v.deleted=0 where d.tenant_id=? and " +
                        "d.workspace_id=? and d.id=? and d.deleted=0",
                (rs, row) -> documentVersion(rs), tenant, workspace, documentId).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findDocumentVersion(String id) {
        return jdbc.query("select " + DOCUMENT_VERSION_COLUMNS + " from ai_document_version " +
                        "where id=? and deleted=0", (rs, row) -> documentVersion(rs), id).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findBySha(String tenant, String workspace, String kb, String sha) {
        return jdbc.query("select " + DOCUMENT_VERSION_COLUMNS + " from ai_document_version where tenant_id=? " +
                        "and workspace_id=? and knowledge_base_id=? and sha256=? and deleted=0 " +
                        "order by created_at desc limit 1",
                (rs, row) -> documentVersion(rs), tenant, workspace, kb, sha).stream().findFirst();
    }

    @Override
    public List<StoredObject> findDocumentObjects(String tenant, String workspace, String documentId) {
        return jdbc.query("select distinct object_key,bucket,content_type,size_bytes,sha256,original_file_name " +
                        "from ai_document_version where tenant_id=? and workspace_id=? and document_id=? " +
                        "and deleted=0 and object_key is not null and bucket is not null order by object_key",
                (rs, row) -> new StoredObject(rs.getString("object_key"), rs.getString("bucket"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("original_file_name")), tenant, workspace, documentId);
    }

    @Override
    @Transactional
    public int lockAndNextDocumentVersion(String tenant, String workspace, String documentId) {
        int updated = jdbc.update("update ai_document set current_version=last_insert_id(current_version+1)," +
                        "status='PROCESSING' where tenant_id=? and workspace_id=? and id=? and deleted=0",
                tenant, workspace, documentId);
        if (updated != 1) throw new IllegalStateException("DOCUMENT_NOT_FOUND");
        Integer value = jdbc.queryForObject("select last_insert_id()", Integer.class);
        return value == null ? 0 : value;
    }

    @Override
    @Transactional
    public UploadRegistration registerUpload(KnowledgeDocument document, KnowledgeDocumentVersion version,
                                             IngestionJob job, String actor) {
        jdbc.update("insert into ai_document " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,code,title,source_type,current_version,status," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,1,'PROCESSING',?,?)",
                document.getId(), document.getTenantId(), document.getWorkspaceId(),
                document.getKnowledgeBaseId(), document.getCode(), document.getTitle(), document.getSourceType(),
                actor, actor);
        insertDocumentVersion(version, "Initial upload", actor);
        insertJob(job, actor);
        insertDefaultAcl(document.getTenantId(), document.getWorkspaceId(), document.getId(), actor);
        outbox(document.getTenantId(), document.getWorkspaceId(), "DOCUMENT", document.getId(),
                "DOCUMENT_UPLOADED", json(Collections.singletonMap("jobId", job.getId())), actor);
        return new UploadRegistration(document.getId(), version.getId(), job.getId());
    }

    @Override
    @Transactional
    public UploadRegistration registerDocumentVersion(KnowledgeDocumentVersion version, IngestionJob job,
                                                       String changeNote, String actor) {
        insertDocumentVersion(version, changeNote, actor);
        insertJob(job, actor);
        outbox(version.getTenantId(), version.getWorkspaceId(), "DOCUMENT_VERSION", version.getId(),
                "DOCUMENT_VERSION_CREATED", json(Collections.singletonMap("jobId", job.getId())), actor);
        return new UploadRegistration(version.getDocumentId(), version.getId(), job.getId());
    }

    @Override
    @Transactional
    public IngestionJob registerReindex(IngestionJob job, String actor) {
        insertJob(job, actor);
        outbox(job.getTenantId(), job.getWorkspaceId(), "DOCUMENT_VERSION", job.getDocumentVersionId(),
                "DOCUMENT_REINDEX_REQUESTED", json(Collections.singletonMap("jobId", job.getId())), actor);
        return job;
    }

    @Override
    public Optional<IngestionJob> findJobByDocumentVersionAndKnowledgeVersion(String documentVersionId,
                                                                              String knowledgeBaseVersionId) {
        return jdbc.query("select " + JOB_COLUMNS + " from ai_ingestion_job where document_version_id=? " +
                        "and knowledge_base_version_id=? and deleted=0 order by created_at desc limit 1",
                (rs, row) -> job(rs), documentVersionId, knowledgeBaseVersionId).stream().findFirst();
    }

    @Override
    public Map<String, List<String>> findDocumentReadPrincipals(String tenant, String workspace,
                                                               List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Collections.emptyMap();
        String placeholders = String.join(",", Collections.nCopies(documentIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(workspace);
        args.addAll(documentIds);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String documentId : documentIds) result.put(documentId, new ArrayList<>());
        jdbc.query("select document_id,principal_type,principal_id from ai_document_acl where tenant_id=? " +
                        "and workspace_id=? and permission in ('READ','OWNER') and status='ACTIVE' and deleted=0 " +
                        "and document_id in (" + placeholders + ")",
                rs -> {
                    String token = principalToken(rs.getString("principal_type"), rs.getString("principal_id"),
                            workspace);
                    if (token != null) result.computeIfAbsent(rs.getString("document_id"), key -> new ArrayList<>())
                            .add(token);
                }, args.toArray());
        return result;
    }

    @Override
    public Optional<IngestionJob> findJob(String tenant, String workspace, String jobId) {
        return jdbc.query("select " + JOB_COLUMNS + " from ai_ingestion_job where tenant_id=? and " +
                        "workspace_id=? and id=? and deleted=0",
                (rs, row) -> job(rs), tenant, workspace, jobId).stream().findFirst();
    }

    @Override
    public Optional<IngestionJob> findJobByDocumentVersion(String versionId) {
        return jdbc.query("select " + JOB_COLUMNS + " from ai_ingestion_job where document_version_id=? " +
                        "and deleted=0 order by created_at desc limit 1",
                (rs, row) -> job(rs), versionId).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<IngestionJob> claimJob(String jobId) {
        int updated = jdbc.update("update ai_ingestion_job set status='VALIDATING',progress=5,attempt=attempt+1," +
                        "started_at=coalesce(started_at,?),error_code=null,error_message=null,updated_by='worker' " +
                        "where id=? and status in ('CREATED','FAILED') and attempt<3 and deleted=0",
                Timestamp.from(Instant.now()), jobId);
        if (updated != 1) return Optional.empty();
        return jdbc.query("select " + JOB_COLUMNS + " from ai_ingestion_job where id=? and deleted=0",
                (rs, row) -> job(rs), jobId).stream().findFirst();
    }

    @Override
    public List<IngestionJob> findRecoverableJobs(int limit) {
        return jdbc.query("select " + JOB_COLUMNS + " from ai_ingestion_job where status in ('CREATED','FAILED') " +
                        "and attempt<3 and deleted=0 order by updated_at,id limit ?",
                (rs, row) -> job(rs), limit);
    }

    @Override
    public void updateJob(String id, IngestionStatus status, int progress, String statistics) {
        jdbc.update("update ai_ingestion_job set status=?,progress=?,statistics_json=?,updated_by='worker' " +
                        "where id=? and status not in ('PUBLISHED','CANCELLED') and deleted=0",
                status.name(), progress, statistics == null ? "{}" : statistics, id);
    }

    @Override
    public void failJob(String id, String code, String message) {
        jdbc.update("update ai_ingestion_job set status='FAILED',error_code=?,error_message=?,finished_at=?," +
                        "updated_by='worker' where id=? and status not in ('PUBLISHED','CANCELLED') and deleted=0",
                code, limit(message, 1000), Timestamp.from(Instant.now()), id);
    }

    @Override
    public void saveParsed(String id, String title, String text, String structure, String warnings,
                           double score, String quality) {
        jdbc.update("update ai_document_version set parsed_title=?,plain_text=?,structure_json=?," +
                        "parse_warnings_json=?,quality_score=?,quality_json=?,updated_by='worker' " +
                        "where id=? and deleted=0",
                title, text, structure, warnings, score, quality, id);
    }

    @Override
    public List<String> findChunkIds(String documentVersionId, String indexVersion) {
        return jdbc.query("select id from ai_document_chunk where document_version_id=? and index_version=? " +
                        "and deleted=0", (rs, row) -> rs.getString(1), documentVersionId, indexVersion);
    }

    @Override
    @Transactional
    public void replaceChunks(String versionId, List<KnowledgeChunk> chunks, String actor) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be empty");
        String indexVersion = chunks.get(0).getIndexVersion();
        jdbc.update("update ai_document_chunk set deleted=1,status='DELETED',updated_by=? " +
                        "where document_version_id=? and index_version=? and deleted=0",
                actor, versionId, indexVersion);
        for (KnowledgeChunk chunk : chunks) insertChunk(chunk, actor);
    }

    @Override
    @Transactional
    public void stageIndexBuild(String jobId, String versionId, String documentId,
                                List<KnowledgeChunk> chunks, String actor) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be empty");
        KnowledgeChunk first = chunks.get(0);
        Long mutable = jdbc.queryForObject("select count(*) from ai_knowledge_base_version k "
                        + "join ai_document_version d on d.knowledge_base_id=k.knowledge_base_id "
                        + "where k.tenant_id=? and k.workspace_id=? and k.knowledge_base_id=? "
                        + "and k.version=? and k.index_version=? and k.status in ('DRAFT','BUILDING') "
                        + "and d.id=? and d.deleted=0 and k.deleted=0",
                Long.class, first.getTenantId(), first.getWorkspaceId(), first.getKnowledgeBaseId(),
                first.getKnowledgeBaseVersion(), first.getIndexVersion(), versionId);
        if (mutable == null || mutable != 1) throw new IllegalStateException("KNOWLEDGE_VERSION_IMMUTABLE");
        jdbc.update("delete from ai_document_chunk where document_version_id=? and index_version=?",
                versionId, first.getIndexVersion());
        for (KnowledgeChunk chunk : chunks) insertChunk(chunk, actor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId);
        payload.put("documentVersionId", versionId);
        payload.put("documentId", documentId);
        payload.put("knowledgeBaseId", first.getKnowledgeBaseId());
        payload.put("indexVersion", first.getIndexVersion());
        payload.put("embeddingDimension", first.getEmbeddingDimension());
        payload.put("expectedDocumentChunkCount", chunks.size());
        String deduplicationKey = "index-build:" + jobId;
        jdbc.update("insert into ai_outbox_event "
                        + "(id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,deduplication_key,"
                        + "payload_json,status,attempt,available_at,created_by,updated_by) "
                        + "values (?,?,?,?,?,'INDEX_BUILD_REQUESTED',?,?,'PENDING',0,?,?,?) "
                        + "on duplicate key update payload_json=values(payload_json),available_at=values(available_at),"
                        + "status=if(status='PUBLISHED',status,'PENDING'),updated_by=values(updated_by)",
                ids.nextId(), first.getTenantId(), first.getWorkspaceId(), "INDEX_VERSION",
                first.getIndexVersion(), deduplicationKey, json(payload), Timestamp.from(Instant.now()), actor, actor);
        jdbc.update("update ai_ingestion_job set status='VERIFYING',progress=90,statistics_json=?,"
                        + "updated_by=? where id=? and status not in ('PUBLISHED','CANCELLED') and deleted=0",
                json(Collections.singletonMap("stagedChunks", chunks.size())), actor, jobId);
        jdbc.update("update ai_index_version set status='BUILDING',updated_by=? where tenant_id=? "
                        + "and workspace_id=? and knowledge_base_id=? and code=? and active=0 and deleted=0",
                actor, first.getTenantId(), first.getWorkspaceId(), first.getKnowledgeBaseId(),
                first.getIndexVersion());
    }

    @Override
    public List<KnowledgeChunk> findIndexBuildChunks(String tenant, String workspace, String indexVersion) {
        return jdbc.query("select " + CHUNK_COLUMNS + " from ai_document_chunk where tenant_id=? and "
                        + "workspace_id=? and index_version=? and status in ('INDEXING','PUBLISHED') and deleted=0 "
                        + "order by document_id,ordinal_no",
                (rs, row) -> chunk(rs), tenant, workspace, indexVersion);
    }

    @Override
    @Transactional
    public void completeIndexBuild(String jobId, String versionId, String documentId,
                                   String indexVersion, com.hmdp.ai.domain.knowledge.IndexVerificationResult result,
                                   String actor) {
        if (result == null || !result.isValid()) throw new IllegalStateException("INDEX_VERIFICATION_FAILED");
        int updated = jdbc.update("update ai_index_version set status='READY',document_count=?,chunk_count=?,"
                        + "verification_json=?,ready_at=?,updated_by=? where code=? and active=0 and deleted=0",
                result.getIndexedDocumentCount(), result.getIndexedChunkCount(), json(result),
                Timestamp.from(Instant.now()), actor, indexVersion);
        if (updated != 1) throw new IllegalStateException("KNOWLEDGE_INDEX_NOT_FOUND");
        publishIngestion(jobId, versionId, documentId, actor);
    }

    @Override
    public List<String> findExpiredInactiveIndexes(int limit) {
        return jdbc.query("select code from ai_index_version where active=0 and status='READY' "
                        + "and rollback_after is not null and rollback_after<=? and deleted=0 "
                        + "order by rollback_after,id limit ?",
                (rs, row) -> rs.getString(1), Timestamp.from(Instant.now()), limit);
    }

    @Override
    public void markIndexDeleted(String indexVersion, String actor) {
        jdbc.update("update ai_index_version set status='DELETED',deleted=1,updated_by=? where code=? "
                        + "and active=0 and rollback_after<=? and deleted=0",
                actor, indexVersion, Timestamp.from(Instant.now()));
    }

    @Override
    @Transactional
    public void publishIngestion(String jobId, String versionId, String documentId, String actor) {
        List<Map<String, Object>> targets = jdbc.queryForList("select v.index_version from ai_ingestion_job j " +
                "join ai_knowledge_base_version v on v.id=j.knowledge_base_version_id where j.id=? and " +
                "j.document_version_id=? and j.deleted=0", jobId, versionId);
        if (targets.isEmpty()) throw new IllegalStateException("INGESTION_TARGET_NOT_FOUND");
        String indexVersion = String.valueOf(targets.get(0).get("index_version"));
        jdbc.update("update ai_document_chunk set status='PUBLISHED',updated_by=? where document_version_id=? " +
                "and index_version=? and deleted=0", actor, versionId, indexVersion);
        jdbc.update("update ai_document_version set status='PUBLISHED',published_at=coalesce(published_at,?)," +
                        "published_by=coalesce(published_by,?),updated_by=? where id=? and deleted=0",
                Timestamp.from(Instant.now()), actor, actor, versionId);
        jdbc.update("update ai_document set status='PUBLISHED',updated_by=? where id=? and deleted=0",
                actor, documentId);
        jdbc.update("update ai_ingestion_job set status='PUBLISHED',progress=100,finished_at=?,updated_by=? " +
                        "where id=? and deleted=0",
                Timestamp.from(Instant.now()), actor, jobId);
        jdbc.update("update ai_index_version i set status='READY',document_count=(select count(distinct c.document_id) " +
                        "from ai_document_chunk c where c.index_version=i.code and c.status='PUBLISHED' and c.deleted=0)," +
                        "chunk_count=(select count(*) from ai_document_chunk c where c.index_version=i.code " +
                        "and c.status='PUBLISHED' and c.deleted=0),updated_by=? where i.code=? and i.deleted=0",
                actor, indexVersion);
        jdbc.update("update ai_knowledge_base_version set index_status='READY',updated_by=? " +
                        "where index_version=? and deleted=0", actor, indexVersion);
        outboxScope(versionId, "DOCUMENT_VERSION_INDEXED", actor);
    }

    @Override
    public List<KnowledgeChunk> findChunksByIds(String tenant, String workspace, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Collections.emptyList();
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(workspace);
        args.addAll(chunkIds);
        return jdbc.query("select " + CHUNK_COLUMNS + " from ai_document_chunk where tenant_id=? and " +
                        "workspace_id=? and id in (" + placeholders + ") and status='PUBLISHED' and deleted=0",
                (rs, row) -> chunk(rs), args.toArray());
    }

    @Override
    @Transactional
    public boolean deleteDocument(String tenant, String workspace, String documentId, String actor) {
        int updated = jdbc.update("update ai_document set deleted=1,status='DELETED',updated_by=? " +
                        "where tenant_id=? and workspace_id=? and id=? and deleted=0",
                actor, tenant, workspace, documentId);
        if (updated != 1) return false;
        jdbc.update("update ai_document_version set deleted=1,status='DELETED',updated_by=? where tenant_id=? " +
                "and workspace_id=? and document_id=? and deleted=0", actor, tenant, workspace, documentId);
        jdbc.update("update ai_document_chunk set deleted=1,status='DELETED',updated_by=? where tenant_id=? " +
                "and workspace_id=? and document_id=? and deleted=0", actor, tenant, workspace, documentId);
        jdbc.update("update ai_document_acl set deleted=1,status='DELETED',updated_by=? where tenant_id=? " +
                "and workspace_id=? and document_id=? and deleted=0", actor, tenant, workspace, documentId);
        outbox(tenant, workspace, "DOCUMENT", documentId, "DOCUMENT_DELETED",
                json(Collections.singletonMap("documentId", documentId)), actor);
        return true;
    }

    @Override
    public boolean isDocumentBoundToImmutableVersion(String tenant, String workspace, String documentId) {
        Integer count = jdbc.queryForObject(
                "select count(1) from ai_document_chunk c "
                        + "join ai_knowledge_base_version v on v.tenant_id=c.tenant_id "
                        + "and v.workspace_id=c.workspace_id and v.knowledge_base_id=c.knowledge_base_id "
                        + "and v.version=c.knowledge_base_version and v.deleted=0 "
                        + "where c.tenant_id=? and c.workspace_id=? and c.document_id=? "
                        + "and c.deleted=0 and v.status in ('PUBLISHED','ARCHIVED')",
                Integer.class, tenant, workspace, documentId);
        return count != null && count > 0;
    }

    @Override
    public List<String> findChunkIds(String tenant, String workspace, String documentId) {
        return jdbc.query("select id from ai_document_chunk where tenant_id=? and workspace_id=? " +
                        "and document_id=? and deleted=0", (rs, row) -> rs.getString(1),
                tenant, workspace, documentId);
    }

    private void insertDocumentVersion(KnowledgeDocumentVersion version, String changeNote, String actor) {
        jdbc.update("insert into ai_document_version " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,document_id,version,object_key,bucket," +
                        "original_file_name,content_type,size_bytes,sha256,status,content_hash,change_note," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)",
                version.getId(), version.getTenantId(), version.getWorkspaceId(), version.getKnowledgeBaseId(),
                version.getDocumentId(), version.getVersion(), version.getObjectKey(), version.getBucket(),
                version.getOriginalFileName(), version.getContentType(), version.getSizeBytes(), version.getSha256(),
                version.getSha256(), changeNote, actor, actor);
    }

    private void insertJob(IngestionJob job, String actor) {
        jdbc.update("insert into ai_ingestion_job " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version_id,document_id," +
                        "document_version_id,status,progress,attempt,statistics_json,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,'CREATED',0,0,'{}',?,?)",
                job.getId(), job.getTenantId(), job.getWorkspaceId(), job.getKnowledgeBaseId(),
                job.getKnowledgeBaseVersionId(), job.getDocumentId(), job.getDocumentVersionId(), actor, actor);
    }

    private void insertDefaultAcl(String tenant, String workspace, String documentId, String actor) {
        jdbc.update("insert into ai_document_acl " +
                        "(id,tenant_id,workspace_id,document_id,principal_type,principal_id,permission,status," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,'ACTIVE',?,?)",
                ids.nextId(), tenant, workspace, documentId, "WORKSPACE", workspace, "READ", actor, actor);
        jdbc.update("insert into ai_document_acl " +
                        "(id,tenant_id,workspace_id,document_id,principal_type,principal_id,permission,status," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,'ACTIVE',?,?)",
                ids.nextId(), tenant, workspace, documentId, "USER", actor, "OWNER", actor, actor);
    }

    private void insertChunk(KnowledgeChunk chunk, String actor) {
        jdbc.update("insert into ai_document_chunk " +
                        "(id,tenant_id,workspace_id,knowledge_base_id,knowledge_base_version,document_id," +
                        "document_version,document_version_id,index_version,ordinal_no,content,search_text," +
                        "content_hash,embedding_dimension,embedding,quality_score,source_type,page_no,section_name," +
                        "heading_path,sheet_name,row_start,row_end,column_start,column_end,source_offset_start," +
                        "source_offset_end,metadata_json,status,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INDEXING',?,?)",
                chunk.getId(), chunk.getTenantId(), chunk.getWorkspaceId(), chunk.getKnowledgeBaseId(),
                chunk.getKnowledgeBaseVersion(), chunk.getDocumentId(), chunk.getDocumentVersion(),
                chunk.getDocumentVersionId(), chunk.getIndexVersion(), chunk.getOrdinal(), chunk.getContent(),
                chunk.getSearchText(), chunk.getContentHash(), chunk.getEmbeddingDimension(),
                EmbeddingBinaryCodec.encode(chunk.getEmbedding()), chunk.getQualityScore(), chunk.getSourceType(),
                chunk.getPage(), chunk.getSection(), chunk.getHeadingPath(), chunk.getSheet(), chunk.getRowStart(),
                chunk.getRowEnd(), chunk.getColumnStart(), chunk.getColumnEnd(), chunk.getSourceOffsetStart(),
                chunk.getSourceOffsetEnd(), chunk.getMetadataJson(), actor, actor);
    }

    private KnowledgeBase knowledgeBase(ResultSet rs) throws SQLException {
        return new KnowledgeBase(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("latest_version"), rs.getString("status"));
    }

    private KnowledgeBaseVersion knowledgeBaseVersion(ResultSet rs) throws SQLException {
        return new KnowledgeBaseVersion(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("workspace_id"), rs.getString("knowledge_base_id"), rs.getInt("version"),
                rs.getString("embedding_model_profile_id"), rs.getInt("embedding_dimension"),
                rs.getString("chunking_policy_json"), rs.getString("retrieval_policy_json"),
                rs.getString("index_version"), rs.getString("index_status"), rs.getString("status"));
    }

    private KnowledgeDocument knowledgeDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("workspace_id"), rs.getString("knowledge_base_id"), rs.getString("code"),
                rs.getString("title"), rs.getString("source_type"), rs.getInt("current_version"),
                rs.getString("status"));
    }

    private KnowledgeDocumentVersion documentVersion(ResultSet rs) throws SQLException {
        return new KnowledgeDocumentVersion(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("workspace_id"), rs.getString("knowledge_base_id"), rs.getString("document_id"),
                rs.getInt("version"), rs.getString("object_key"), rs.getString("bucket"),
                rs.getString("original_file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("status"));
    }

    private IngestionJob job(ResultSet rs) throws SQLException {
        return new IngestionJob(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("knowledge_base_id"), rs.getString("knowledge_base_version_id"),
                rs.getString("document_id"), rs.getString("document_version_id"),
                IngestionStatus.valueOf(rs.getString("status")), rs.getInt("progress"), rs.getInt("attempt"),
                rs.getString("error_code"), rs.getString("error_message"), rs.getString("statistics_json"));
    }

    private KnowledgeChunk chunk(ResultSet rs) throws SQLException {
        return new KnowledgeChunk(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("knowledge_base_id"), rs.getInt("knowledge_base_version"),
                rs.getString("document_id"), rs.getInt("document_version"),
                rs.getString("document_version_id"), rs.getString("index_version"), rs.getInt("ordinal_no"),
                rs.getString("content"), rs.getString("search_text"), rs.getString("content_hash"),
                rs.getInt("embedding_dimension"), EmbeddingBinaryCodec.decode(rs.getBytes("embedding")),
                rs.getDouble("quality_score"), rs.getString("source_type"), (Integer) rs.getObject("page_no"),
                rs.getString("section_name"), rs.getString("heading_path"), rs.getString("sheet_name"),
                (Integer) rs.getObject("row_start"), (Integer) rs.getObject("row_end"),
                (Integer) rs.getObject("column_start"), (Integer) rs.getObject("column_end"),
                (Integer) rs.getObject("source_offset_start"), (Integer) rs.getObject("source_offset_end"),
                rs.getString("metadata_json"));
    }

    private String principalToken(String type, String id, String workspace) {
        if ("WORKSPACE".equals(type) && workspace.equals(id)) return "workspace";
        if ("USER".equals(type)) return "user:" + id;
        if ("ALL".equals(type)) return "all";
        return null;
    }

    private void outboxScope(String versionId, String type, String actor) {
        List<Map<String, Object>> scope = jdbc.queryForList("select tenant_id,workspace_id,document_id " +
                "from ai_document_version where id=?", versionId);
        if (!scope.isEmpty()) {
            outbox(String.valueOf(scope.get(0).get("tenant_id")), String.valueOf(scope.get(0).get("workspace_id")),
                    "DOCUMENT_VERSION", versionId, type,
                    json(Collections.singletonMap("documentId", scope.get(0).get("document_id"))), actor);
        }
    }

    private void outbox(String tenant, String workspace, String aggregate, String aggregateId,
                        String type, String payload, String actor) {
        jdbc.update("insert into ai_outbox_event " +
                        "(id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,payload_json,status," +
                        "attempt,available_at,created_by,updated_by) values (?,?,?,?,?,?,?,'PENDING',0,?,?,?)",
                ids.nextId(), tenant, workspace, aggregate, aggregateId, type, payload,
                Timestamp.from(Instant.now()), actor, actor);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("knowledge payload cannot be serialized", e);
        }
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}

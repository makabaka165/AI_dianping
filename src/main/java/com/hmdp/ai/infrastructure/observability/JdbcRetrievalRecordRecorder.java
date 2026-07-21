package com.hmdp.ai.infrastructure.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalRecordPort;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRetrievalRecordRecorder implements RetrievalRecordPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiIdGenerator ids;
    private final PiiRedactionService redactor;

    public JdbcRetrievalRecordRecorder(JdbcTemplate jdbc, ObjectMapper mapper, AiIdGenerator ids,
                                       PiiRedactionService redactor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ids = ids;
        this.redactor = redactor;
    }

    @Override
    public void record(RetrievalRecord record) {
        InvocationContext context = record.getContext();
        String selectedIds = json(record.getSelectedChunkIds());
        jdbc.update("insert into ai_retrieval_record (id,tenant_id,workspace_id,run_id,node_run_id," +
                        "invocation_id,knowledge_base_id,knowledge_base_version,index_version,query_summary," +
                        "filters_json,result_chunk_ids_json,selected_chunk_ids_json,citation_ids_json," +
                        "vector_candidate_count,lexical_candidate_count,fused_candidate_count," +
                        "reranked_candidate_count,final_count,rerank_mode,latency_ms,status,error_code," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ids.nextId(), context.getTenantId(), context.getWorkspaceId(), context.getRunId(),
                context.getNodeRunId(), context.getInvocationId(), record.getKnowledgeBaseId(),
                record.getKnowledgeBaseVersion(), record.getIndexVersion(), querySummary(record.getQuery()),
                "{}", selectedIds, selectedIds, json(record.getCitationIds()),
                record.getVectorCandidateCount(), record.getLexicalCandidateCount(),
                record.getFusedCandidateCount(), record.getRerankedCandidateCount(),
                record.getSelectedChunkIds().size(), record.getRerankMode(), record.getLatencyMs(),
                record.getStatus(), safeCode(record.getErrorCode()), context.getUserId(), context.getUserId());
    }

    private String querySummary(String value) {
        return AiLogSanitizer.safe(redactor.redact(value == null ? "" : value), 1000);
    }

    private String safeCode(String value) {
        if (value == null) {
            return null;
        }
        String safe = AiLogSanitizer.safe(value, 64);
        return safe == null || safe.trim().isEmpty() ? "KNOWLEDGE_RETRIEVAL_FAILED" : safe;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RETRIEVAL_RECORD_SERIALIZATION_FAILED", e);
        }
    }
}

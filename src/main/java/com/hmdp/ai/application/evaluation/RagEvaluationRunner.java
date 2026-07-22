package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.KnowledgeRetriever;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalRecordPort;
import com.hmdp.ai.domain.knowledge.RetrievedChunk;
import com.hmdp.ai.domain.observability.InvocationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RagEvaluationRunner implements EvaluationTargetRunner {
    private final KnowledgeRetriever retriever;
    private final RetrievalRecordPort records;
    private final EvaluationRunSupport runs;
    private final ObjectMapper mapper;

    public RagEvaluationRunner(KnowledgeRetriever retriever, RetrievalRecordPort records,
                               EvaluationRunSupport runs, ObjectMapper mapper) {
        this.retriever = retriever;
        this.records = records;
        this.runs = runs;
        this.mapper = mapper;
    }

    @Override
    public String targetType() { return "RAG"; }

    @Override
    public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
        try {
            JsonNode input = mapper.readTree(request.getEvaluationCase().getInputJson());
            String query = input.path("query").asText(input.path("text").asText());
            if (query.trim().isEmpty()) throw new IllegalArgumentException("EVALUATION_RAG_QUERY_REQUIRED");
            Integer topK = input.has("topK") ? input.path("topK").asInt() : null;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("knowledgeBaseId", request.getTargetId());
            snapshot.put("knowledgeBaseVersion", request.getTargetVersion());
            EvaluationRunSupport.EvaluationRunDescriptor descriptor = new EvaluationRunSupport
                    .EvaluationRunDescriptor(request.getTargetId(), request.targetVersionOr(1),
                    mapper.writeValueAsString(snapshot), "{}", Collections.emptyList(),
                    Collections.emptyList(), "zh-CN", "Asia/Shanghai");
            return runs.execute(request, descriptor, session -> retrieve(request, session, query, topK));
        } catch (Exception error) {
            return failure(error);
        }
    }

    private EvaluationTargetOutput retrieve(EvaluationTargetRequest request,
                                            EvaluationRunSupport.EvaluationRunSession session,
                                            String query, Integer topK) {
        long started = System.currentTimeMillis();
        InvocationContext invocation = InvocationContext.from(session.getContext(),
                session.getNodeRunId(), session.getInvocationId());
        KnowledgeRetrievalRequest retrievalRequest = KnowledgeRetrievalRequest.forRun(invocation,
                request.getTargetId(), request.getTargetVersion(), query, topK);
        try {
            HybridRetrievalResult result = retriever.retrieve(retrievalRequest);
            records.record(RetrievalRecord.succeeded(invocation, retrievalRequest, result,
                    System.currentTimeMillis() - started));
            ObjectNode actual = mapper.createObjectNode();
            ArrayNode results = actual.putArray("results");
            ArrayNode evidenceIds = actual.putArray("evidenceIds");
            ArrayNode citationIds = actual.putArray("citationIds");
            for (RetrievedChunk chunk : result.getChunks()) {
                ObjectNode item = results.addObject().put("chunkId", chunk.getChunk().getId())
                        .put("text", chunk.getText()).put("score", chunk.getScore());
                evidenceIds.add(chunk.getChunk().getId());
                Citation citation = chunk.getCitation();
                if (citation != null && citation.getCitationId() != null) {
                    item.put("citationId", citation.getCitationId());
                    citationIds.add(citation.getCitationId());
                }
            }
            actual.put("rerankMode", result.getRerankMode());
            actual.put("grounded", !result.getChunks().isEmpty());
            actual.set("warnings", mapper.valueToTree(result.getWarnings()));
            actual.set("trace", mapper.valueToTree(result.getTrace()));
            return EvaluationTargetOutput.success(actual, 0, 0, 0, 0,
                    java.math.BigDecimal.ZERO);
        } catch (RuntimeException error) {
            try {
                records.record(RetrievalRecord.failed(invocation, query, request.getTargetId(),
                        request.getTargetVersion(), errorCode(error), System.currentTimeMillis() - started));
            } catch (RuntimeException ignored) {
                // Preserve the retrieval failure.
            }
            throw error;
        }
    }

    private EvaluationExecutionResult failure(Exception error) {
        String code = errorCode(error);
        String message = error.getMessage();
        return new EvaluationExecutionResult(null, mapper.createObjectNode().put("success", false)
                .put("errorCode", code).put("errorMessage", message == null ? "target execution failed" : message),
                0, 0, 0, 0, 0, 0, false, code, message);
    }

    private String errorCode(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[A-Z0-9_]{3,64}")
                ? message : "EVALUATION_RAG_FAILED";
    }
}

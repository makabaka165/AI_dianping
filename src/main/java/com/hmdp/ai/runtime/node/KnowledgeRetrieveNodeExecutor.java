package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.KnowledgeRetriever;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalRecordPort;
import com.hmdp.ai.domain.knowledge.RetrievedChunk;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class KnowledgeRetrieveNodeExecutor implements NodeExecutor {
    private final KnowledgeRetriever retriever;
    private final ObjectMapper mapper;
    private final RetrievalRecordPort records;
    private final AiIdGenerator ids;

    public KnowledgeRetrieveNodeExecutor(KnowledgeRetriever retriever, ObjectMapper mapper,
                                         RetrievalRecordPort records, AiIdGenerator ids) {
        this.retriever = retriever;
        this.mapper = mapper;
        this.records = records;
        this.ids = ids;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return EnumSet.of(WorkflowNodeType.KNOWLEDGE_RETRIEVE, WorkflowNodeType.SEMANTIC_SEARCH);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        long started = System.currentTimeMillis();
        InvocationContext invocationContext = InvocationContext.from(context.getExecutionContext(),
                context.getNodeRunId(), ids.nextId());
        String knowledgeBaseId = "unknown";
        Integer requestedVersion = null;
        String query = "";
        try {
            JsonNode configuration = mapper.readTree(context.getNode().getConfigurationJson());
            knowledgeBaseId = configuration.path("knowledgeBaseId")
                    .asText(String.valueOf(context.getVariables().getOrDefault("knowledgeBaseId", "")));
            if (knowledgeBaseId.trim().isEmpty()) {
                throw new RetrievalNodeException("KNOWLEDGE_BASE_REQUIRED");
            }
            String queryVariable = configuration.path("queryVariable").asText("query");
            query = String.valueOf(context.getVariables().getOrDefault(queryVariable,
                    context.getVariables().getOrDefault("text", "")));
            requestedVersion = configuration.has("knowledgeBaseVersion")
                    ? configuration.path("knowledgeBaseVersion").asInt() : null;
            KnowledgeRetrievalRequest request = KnowledgeRetrievalRequest.forRun(invocationContext,
                    knowledgeBaseId, requestedVersion, query,
                    configuration.has("topK") ? configuration.path("topK").asInt() : null);
            HybridRetrievalResult result = retriever.retrieve(request);
            if (result == null) {
                throw new RetrievalNodeException("KNOWLEDGE_RETRIEVAL_EMPTY");
            }
            records.record(RetrievalRecord.succeeded(invocationContext, request, result,
                    System.currentTimeMillis() - started));

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(configuration.path("resultVariable").asText("retrievalResults"),
                    mapper.convertValue(result.getChunks(), Object.class));
            updates.put("rerankMode", result.getRerankMode());
            updates.put("retrievalTrace", traceMap(result));
            return new NodeExecutionResult(NodeRunStatus.SUCCEEDED, mapper.valueToTree(result.getChunks()),
                    null, updates, null, result.getChunks().stream().map(RetrievedChunk::getCitation)
                    .collect(Collectors.toList()), result.getWarnings(),
                    com.hmdp.ai.domain.run.UsageSummary.empty(0), false, null);
        } catch (Exception e) {
            String errorCode = errorCode(e);
            recordFailure(invocationContext, query, knowledgeBaseId, requestedVersion, errorCode,
                    System.currentTimeMillis() - started);
            return NodeExecutionResult.failure(errorCode, true);
        }
    }

    private Map<String, Object> traceMap(HybridRetrievalResult result) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("knowledgeBaseVersion", result.getTrace().getKnowledgeBaseVersion());
        trace.put("indexVersion", result.getTrace().getIndexVersion());
        trace.put("vectorCandidateCount", result.getTrace().getVectorCandidateCount());
        trace.put("lexicalCandidateCount", result.getTrace().getLexicalCandidateCount());
        trace.put("fusedCandidateCount", result.getTrace().getFusedCandidateCount());
        trace.put("rerankedCandidateCount", result.getTrace().getRerankedCandidateCount());
        return trace;
    }

    private void recordFailure(InvocationContext context, String query, String knowledgeBaseId,
                               Integer requestedVersion, String errorCode, long latencyMs) {
        try {
            records.record(RetrievalRecord.failed(context, query, knowledgeBaseId, requestedVersion,
                    errorCode, latencyMs));
        } catch (RuntimeException ignored) {
            // Preserve the execution error; the recorder is retried by the persistence layer.
        }
    }

    private String errorCode(Exception exception) {
        String value = exception.getMessage();
        if (value != null && value.matches("[A-Z][A-Z0-9_]+")) {
            return value;
        }
        return "KNOWLEDGE_RETRIEVAL_FAILED";
    }

    private static final class RetrievalNodeException extends RuntimeException {
        private RetrievalNodeException(String message) {
            super(message);
        }
    }
}

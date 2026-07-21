package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.KnowledgeRetriever;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalRecordPort;
import com.hmdp.ai.domain.knowledge.RetrievalTrace;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrieveNodeExecutorTest {
    @Test
    void passesInvocationContextAndRecordsDetailedSuccessfulRetrieval() {
        AtomicReference<KnowledgeRetrievalRequest> requestRef = new AtomicReference<>();
        KnowledgeRetriever retriever = new KnowledgeRetriever() {
            @Override
            public HybridRetrievalResult retrieve(KnowledgeRetrievalRequest request) {
                requestRef.set(request);
                return new HybridRetrievalResult(Collections.emptyList(), "FALLBACK_RRF",
                        Collections.singletonList("INSUFFICIENT_KNOWLEDGE_EVIDENCE"),
                        new RetrievalTrace(4, "index-v4", 12, 8, 14, 10, Collections.emptyList()));
            }

            @Override
            public HybridRetrievalResult retrieve(String tenantId, String workspaceId, String userId,
                                                  String knowledgeBaseId, Integer knowledgeBaseVersion,
                                                  String query, Integer topK) {
                throw new AssertionError("runtime retrieval must use InvocationContext");
            }
        };
        AtomicReference<RetrievalRecord> recordRef = new AtomicReference<>();
        RetrievalRecordPort records = recordRef::set;
        AiIdGenerator ids = mock(AiIdGenerator.class);
        when(ids.nextId()).thenReturn("retrieval-invocation-1");
        KnowledgeRetrieveNodeExecutor executor = new KnowledgeRetrieveNodeExecutor(retriever,
                new ObjectMapper(), records, ids);

        NodeExecutionResult result = executor.execute(context());

        assertEquals(NodeRunStatus.SUCCEEDED, result.getStatus());
        assertNotNull(requestRef.get().getInvocationContext());
        assertEquals("node-run-7", requestRef.get().getInvocationContext().getNodeRunId());
        assertEquals("retrieval-invocation-1",
                requestRef.get().getInvocationContext().getInvocationId());
        assertEquals("index-v4", result.getVariableUpdates().get("retrievalTrace") instanceof Map
                ? ((Map<?, ?>) result.getVariableUpdates().get("retrievalTrace")).get("indexVersion") : null);
        assertNotNull(recordRef.get());
        assertEquals("node-run-7", recordRef.get().getContext().getNodeRunId());
        assertEquals(12, recordRef.get().getVectorCandidateCount());
        assertEquals(14, recordRef.get().getFusedCandidateCount());
    }

    private NodeExecutionContext context() {
        ExecutionContext execution = new ExecutionContext("tenant", "workspace", "user", "session",
                null, "run-1", "agent-1", 3, "en-US", "UTC", Collections.emptyList(),
                Collections.emptyList(), new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)),
                ExecutionBudget.defaults(), Instant.now().plusSeconds(30), Collections.emptyMap(), "trace-1");
        WorkflowNodeDefinition node = new WorkflowNodeDefinition("node", "retrieve",
                WorkflowNodeType.KNOWLEDGE_RETRIEVE, "Retrieve",
                "{\"knowledgeBaseId\":\"kb-1\",\"knowledgeBaseVersion\":4," +
                        "\"queryVariable\":\"question\",\"topK\":6}",
                "{}", "{}", 1000, 1);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", "Which policy applies?");
        return new NodeExecutionContext(execution, null, null, node,
                variables, Collections.emptyList(), "node-run-7");
    }
}

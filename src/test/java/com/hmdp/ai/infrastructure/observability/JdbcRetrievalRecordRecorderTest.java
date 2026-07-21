package com.hmdp.ai.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalTrace;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcRetrievalRecordRecorderTest {
    @Test
    void persistsNodeAndInvocationIdentityAlongsideCandidateTrace() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        AiIdGenerator ids = mock(AiIdGenerator.class);
        when(ids.nextId()).thenReturn("retrieval-record-1");
        PiiRedactionService redactor = mock(PiiRedactionService.class);
        when(redactor.redact("email@example.com")).thenReturn("[REDACTED_EMAIL]");
        JdbcRetrievalRecordRecorder recorder = new JdbcRetrievalRecordRecorder(jdbc,
                new ObjectMapper(), ids, redactor);
        InvocationContext context = new InvocationContext("tenant", "workspace", "run-1", "node-1",
                "invocation-1", "trace-1", "agent-1", 2, "user-1");
        KnowledgeRetrievalRequest request = KnowledgeRetrievalRequest.forRun(context, "kb-1", 7,
                "email@example.com", 5);
        HybridRetrievalResult result = new HybridRetrievalResult(Collections.emptyList(), "MODEL",
                Collections.emptyList(), new RetrievalTrace(7, "idx-7", 10, 9, 12, 8,
                Arrays.asList("chunk-1", "chunk-2")));

        recorder.record(RetrievalRecord.succeeded(context, request, result, 42));

        assertTrue(jdbc.sql.contains("node_run_id"));
        assertEquals("node-1", jdbc.args[4]);
        assertEquals("invocation-1", jdbc.args[5]);
        assertEquals(10, jdbc.args[14]);
        assertEquals(12, jdbc.args[16]);
        assertEquals(8, jdbc.args[17]);
        assertEquals("[REDACTED_EMAIL]", jdbc.args[9]);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}

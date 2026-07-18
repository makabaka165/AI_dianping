package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdbcKnowledgeRepositorySqlContractTest {
    @Test
    void documentVersionRegistrationBindsEverySqlPlaceholder() {
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if (!"update".equals(invocation.getMethod().getName()) ||
                    invocation.getArguments().length == 0 || !(invocation.getArgument(0) instanceof String)) {
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            String sql = invocation.getArgument(0);
            Object[] raw = invocation.getArguments();
            Object[] arguments;
            if (raw.length == 2 && raw[1] instanceof Object[]) {
                arguments = (Object[]) raw[1];
            } else {
                arguments = java.util.Arrays.copyOfRange(raw, 1, raw.length);
            }
            assertThat(arguments).as(sql).hasSize((int) sql.chars().filter(value -> value == '?').count());
            statements.incrementAndGet();
            return 1;
        });
        JdbcKnowledgeRepository repository = new JdbcKnowledgeRepository(jdbc, new AiIdGenerator(),
                new ObjectMapper());
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion("dv", "tenant", "workspace", "kb",
                "document", 2, "object", "bucket", "policy.pdf", "application/pdf", 12, "sha", "DRAFT");
        IngestionJob job = new IngestionJob("job", "tenant", "workspace", "kb", "kbv", "document", "dv",
                IngestionStatus.CREATED, 0, 0, null, null, "{}");

        repository.registerDocumentVersion(version, job, "updated policy", "user");

        assertThat(statements.get()).isEqualTo(3);
    }
}

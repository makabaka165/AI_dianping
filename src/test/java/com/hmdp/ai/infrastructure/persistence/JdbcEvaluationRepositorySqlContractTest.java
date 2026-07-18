package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.evaluation.EvaluationDataset;
import com.hmdp.ai.domain.evaluation.EvaluationResult;
import com.hmdp.ai.domain.evaluation.EvaluationRun;
import com.hmdp.ai.domain.evaluation.EvaluationType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdbcEvaluationRepositorySqlContractTest {

    @Test
    void writesBindEverySqlPlaceholder() {
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if ("update".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length > 0
                    && invocation.getArgument(0) instanceof String) {
                String sql = invocation.getArgument(0);
                Object[] raw = invocation.getArguments();
                Object[] arguments = raw.length == 2 && raw[1] instanceof Object[]
                        ? (Object[]) raw[1]
                        : java.util.Arrays.copyOfRange(raw, 1, raw.length);
                assertThat(arguments).as(sql).hasSize((int) sql.chars().filter(value -> value == '?').count());
                statements.incrementAndGet();
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        JdbcEvaluationRepository repository = new JdbcEvaluationRepository(jdbc);
        repository.createDataset(new EvaluationDataset("dataset", "tenant", "workspace", "regression",
                "Regression", "deterministic suite", EvaluationType.AGENT, "ACTIVE"), "actor");
        repository.createCase(new EvaluationCase("case", "tenant", "workspace", "dataset", "case one",
                "{}", "{}", "{}", "ACTIVE"), "actor");
        repository.createRun(new EvaluationRun("run", "tenant", "workspace", "dataset", "AGENT",
                "shop-consultant", 1, "RUNNING", "{}", Instant.now(), null), "actor");
        repository.saveResults("run", Collections.singletonList(new EvaluationResult("result", "tenant",
                "workspace", "run", "case", "{}", "{}", true, null, null, "COMPLETED")),
                "{}", "actor");

        assertThat(statements.get()).isEqualTo(5);
    }
}

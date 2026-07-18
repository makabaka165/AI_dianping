package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionBudgetFactoryTest {

    @Test
    void normalizesPolicyIntoCompleteSnapshot() {
        ExecutionBudgetFactory factory = new ExecutionBudgetFactory(new ObjectMapper());
        ExecutionBudget budget = factory.fromPolicy("{\"maxWorkflowNodes\":10,\"maxRunDurationSeconds\":30}");

        ExecutionBudget restored = factory.fromStoredJson(factory.snapshotJson(budget));

        assertEquals(10, restored.getMaxWorkflowNodes());
        assertEquals(30, restored.getMaxRunDuration().getSeconds());
        assertEquals(4, restored.getMaxParallelism());
    }

    @Test
    void rejectsNonPositiveLimits() {
        ExecutionBudgetFactory factory = new ExecutionBudgetFactory(new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> factory.fromPolicy("{\"maxToolCalls\":0}"));
    }
}

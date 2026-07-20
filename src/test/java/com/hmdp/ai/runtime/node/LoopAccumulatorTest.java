package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.ConditionDslEvaluator;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopAccumulatorTest {
    @Test
    void sumStartsAtZeroAndPersistsAcrossIterations() {
        ObjectMapper mapper = new ObjectMapper();
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("loop", WorkflowNodeType.LOOP,
                "{\"maxIterations\":3,\"terminationCondition\":{\"eq\":[1,2]},"
                        + "\"perIterationTimeoutMs\":1000,\"deduplicationKey\":\"itemId\","
                        + "\"accumulatorStrategy\":\"SUM\",\"accumulatorVariable\":\"total\","
                        + "\"valueVariable\":\"amount\"}");
        List<WorkflowEdgeDefinition> edges = Arrays.asList(
                NodeExecutorTestSupport.edge("loop", "body", null, 1, "body"),
                NodeExecutorTestSupport.edge("loop", "exit", null, 0, "exit"));
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 3);
        variables.put("itemId", "one");

        NodeExecutionResult first = new LoopNodeExecutor(mapper, new ConditionDslEvaluator(mapper))
                .execute(NodeExecutorTestSupport.context(node, variables, edges));
        assertEquals(new BigDecimal("3"), first.getVariableUpdates().get("total"));

        variables.putAll(first.getVariableUpdates());
        variables.put("amount", 4);
        variables.put("itemId", "two");
        NodeExecutionResult second = new LoopNodeExecutor(mapper, new ConditionDslEvaluator(mapper))
                .execute(NodeExecutorTestSupport.context(node, variables, edges));
        assertEquals(new BigDecimal("7"), second.getVariableUpdates().get("total"));
        assertEquals(Collections.singletonList("body"), second.getNextNodeIds());
    }
}

package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinNodeExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsTheExactObjectConflictPath() {
        Map<String, Object> left = Collections.singletonMap("shop", Collections.singletonMap("name", "A"));
        Map<String, Object> right = Collections.singletonMap("shop", Collections.singletonMap("name", "B"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("left", left);
        variables.put("right", right);
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("join", WorkflowNodeType.JOIN,
                "{\"mode\":\"MERGE_OBJECT\",\"inputVariables\":[\"left\",\"right\"]}");

        NodeExecutionResult result = new JoinNodeExecutor(mapper).execute(
                NodeExecutorTestSupport.context(node, variables, Collections.emptyList()));

        assertEquals("JOIN_VARIABLE_CONFLICT:$.shop.name", result.getErrorCode());
    }

    @Test
    void zipPreservesMissingPositionsAsNull() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("left", Arrays.asList(1, 2));
        variables.put("right", Collections.singletonList("a"));
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("join", WorkflowNodeType.JOIN,
                "{\"mode\":\"ZIP\",\"inputVariables\":[\"left\",\"right\"]}");

        NodeExecutionResult result = new JoinNodeExecutor(mapper).execute(
                NodeExecutorTestSupport.context(node, variables, Collections.emptyList()));

        assertEquals(2, result.getOutput().size());
        assertEquals("a", result.getOutput().get(0).get(1).asText());
        assertEquals(true, result.getOutput().get(1).get(1).isNull());
    }
}

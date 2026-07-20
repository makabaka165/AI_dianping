package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataTransformNodeExecutorTest {
    @Test
    void executesOnlyTheDeclaredOperations() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "A");
        first.put("score", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("name", "B");
        second.put("score", 5);
        Map<String, Object> variables = new HashMap<>();
        variables.put("rows", Arrays.asList(first, second));
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("transform", WorkflowNodeType.DATA_TRANSFORM,
                "{\"inputVariable\":\"rows\",\"outputVariable\":\"result\",\"operations\":["
                        + "{\"op\":\"filter\",\"field\":\"score\",\"operator\":\"gte\",\"value\":2},"
                        + "{\"op\":\"calculate\",\"field\":\"weighted\",\"operator\":\"multiply\","
                        + "\"operands\":[\"score\",2]},"
                        + "{\"op\":\"sort\",\"field\":\"weighted\",\"direction\":\"desc\"},"
                        + "{\"op\":\"select\",\"fields\":[\"name\",\"weighted\"]},"
                        + "{\"op\":\"limit\",\"count\":1}]}");

        NodeExecutionResult result = new DataTransformNodeExecutor(new ObjectMapper()).execute(
                NodeExecutorTestSupport.context(node, variables, Collections.emptyList()));

        assertEquals("B", result.getOutput().get(0).path("name").asText());
        assertEquals(10, result.getOutput().get(0).path("weighted").asInt());
        assertEquals(1, ((java.util.List<?>) result.getVariableUpdates().get("result")).size());
    }

    @Test
    void rejectsAnArbitraryExpressionOperation() {
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("transform", WorkflowNodeType.DATA_TRANSFORM,
                "{\"inputVariable\":\"rows\",\"operations\":[{\"op\":\"spel\","
                        + "\"expression\":\"T(java.lang.Runtime).getRuntime()\"}]}");

        NodeExecutionResult result = new DataTransformNodeExecutor(new ObjectMapper()).execute(
                NodeExecutorTestSupport.context(node, Collections.singletonMap("rows", Collections.emptyList()),
                        Collections.emptyList()));

        assertEquals("DATA_TRANSFORM_OPERATION_UNSUPPORTED", result.getErrorCode());
    }
}

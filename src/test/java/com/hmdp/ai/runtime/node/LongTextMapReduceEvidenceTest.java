package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTextMapReduceEvidenceTest {
    @Test
    void deduplicatesClaimsAndKeepsStructuredSourceLocations() {
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("reduce",
                WorkflowNodeType.LONG_TEXT_MAP_REDUCE,
                "{\"inputVariable\":\"document\",\"outputVariable\":\"summary\",\"chunkChars\":256}");
        String repeated = "Service is fast. Service is fast. ";
        String text = repeated.repeat(12) + "Parking is limited.";

        NodeExecutionResult result = new LongTextMapReduceNodeExecutor(new ObjectMapper()).execute(
                NodeExecutorTestSupport.context(node, Collections.singletonMap("document", text),
                        Collections.emptyList()));

        assertEquals("SUCCEEDED", result.getStatus().name());
        JsonNode ledger = result.getOutput().path("evidenceLedger");
        long serviceClaims = 0;
        for (JsonNode claim : ledger) {
            if ("Service is fast.".equals(claim.path("claim").asText())) {
                serviceClaims++;
                assertTrue(claim.path("sourceLocations").isArray());
                assertTrue(claim.path("sourceLocations").size() >= 1);
            }
        }
        assertEquals(1, serviceClaims);
        assertTrue(result.getOutput().path("maps").size() >= 2);
    }
}

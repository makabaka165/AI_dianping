package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class ArtifactGenerateNodeExecutorTest {
    @Test
    void storesCsvAndPersistsItsNodeRunIdentity() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiIdGenerator ids = mock(AiIdGenerator.class);
        when(ids.nextId()).thenReturn("artifact-1");
        when(storage.put(eq("t"), eq("artifacts-r"), eq("shops.csv"), eq("text/csv"),
                any(byte[].class), anyString())).thenReturn(new StoredObject("objects/shops.csv", "hmdp-ai",
                "text/csv", 10, "sha", "shops.csv"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "A");
        row.put("score", 5);
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("artifact", WorkflowNodeType.ARTIFACT_GENERATE,
                "{\"inputVariable\":\"rows\",\"format\":\"CSV\",\"name\":\"shops.csv\","
                        + "\"outputVariable\":\"download\"}");

        NodeExecutionResult result = new ArtifactGenerateNodeExecutor(storage, jdbc, ids, new ObjectMapper())
                .execute(NodeExecutorTestSupport.context(node,
                        Collections.singletonMap("rows", Arrays.asList(row)), Collections.emptyList(),
                        Collections.emptyList(), "node-run-1"));

        assertEquals("artifact-1", result.getArtifacts().get(0).getArtifactId());
        assertEquals("/api/v1/artifacts/artifact-1", result.getArtifacts().get(0).getDownloadPath());
        Object[] invocationArguments = mockingDetails(jdbc).getInvocations().stream()
                .findFirst().orElseThrow(AssertionError::new).getArguments();
        assertEquals("node-run-1", invocationArguments[5]);
    }
}

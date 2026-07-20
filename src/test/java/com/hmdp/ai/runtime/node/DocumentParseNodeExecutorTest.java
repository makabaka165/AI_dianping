package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.ArtifactRecord;
import com.hmdp.ai.domain.artifact.ArtifactRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParsingPort;
import com.hmdp.ai.domain.knowledge.parsing.ParsedCell;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedFile;
import com.hmdp.ai.domain.knowledge.parsing.ParsedSection;
import com.hmdp.ai.domain.knowledge.parsing.ParsedTable;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentParseNodeExecutorTest {
    @Test
    void parsesArtifactFromReferenceVariableAndPreservesStructure() throws Exception {
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        DocumentParsingPort parsers = mock(DocumentParsingPort.class);
        ArtifactRecord artifact = new ArtifactRecord("artifact-1", "t", "w", "r", "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "objects/report.xlsx",
                4, "u", "ACTIVE");
        ParsedDocument document = new ParsedDocument("report", artifact.getContentType(),
                Collections.singletonList(new ParsedSection("Section", "Body", 2,
                        Arrays.asList("Root", "Section"), 0, 4)),
                Collections.singletonList(new ParsedTable("Sheet1", null,
                        Collections.singletonList(new ParsedCell(3, 2, "C4", "value")))),
                Collections.emptyList());
        when(artifacts.find("t", "w", "artifact-1")).thenReturn(Optional.of(artifact));
        when(storage.get("hmdp-ai", "objects/report.xlsx"))
                .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        when(parsers.parse(any(byte[].class), eq("report.xlsx"), eq(artifact.getContentType())))
                .thenReturn(new ParsedFile(document, "sha", artifact.getContentType()));
        Map<String, Object> variables = new HashMap<>();
        variables.put("documentRef", Collections.singletonMap("artifactId", "artifact-1"));
        WorkflowNodeDefinition node = NodeExecutorTestSupport.node("parse", WorkflowNodeType.DOCUMENT_PARSE,
                "{\"referenceVariable\":\"documentRef\",\"outputVariable\":\"parsed\","
                        + "\"allowedMimeTypes\":[\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"],"
                        + "\"maxPages\":10,\"maxRows\":10,\"maxCells\":10}");

        NodeExecutionResult result = new DocumentParseNodeExecutor(artifacts, storage, parsers,
                new ObjectMapper(), "hmdp-ai").execute(NodeExecutorTestSupport.context(node, variables,
                Collections.emptyList()));

        assertEquals("SUCCEEDED", result.getStatus().name());
        assertSame(document, result.getVariableUpdates().get("parsed"));
        assertEquals(2, result.getOutput().path("sections").get(0).path("page").asInt());
        assertEquals("C4", result.getOutput().path("tables").get(0).path("cells").get(0)
                .path("address").asText());
        verify(storage).get("hmdp-ai", "objects/report.xlsx");
    }
}

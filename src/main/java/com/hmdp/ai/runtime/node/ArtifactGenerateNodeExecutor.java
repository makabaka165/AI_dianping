package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ArtifactGenerateNodeExecutor implements NodeExecutor {
    private final ObjectStoragePort storage;
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public ArtifactGenerateNodeExecutor(ObjectStoragePort storage, JdbcTemplate jdbc,
                                        AiIdGenerator ids, ObjectMapper mapper) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.ids = ids;
        this.mapper = mapper;
    }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.ARTIFACT_GENERATE);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            String inputVariable = config.path("inputVariable").asText("agentOutput");
            JsonNode value = mapper.valueToTree(context.getVariables().get(inputVariable));
            String format = config.path("format").asText("JSON").toUpperCase(Locale.ROOT);
            Generated generated = generate(format, value);
            if (generated.bytes.length > context.getExecutionContext().getExecutionBudget().getMaxArtifactBytes()) {
                return NodeExecutionResult.failure("ARTIFACT_BUDGET_EXCEEDED", false);
            }
            String name = safeName(config.path("name").asText(context.getNode().getCode() + generated.extension));
            String sha = sha256(generated.bytes);
            StoredObject object = storage.put(context.getExecutionContext().getTenantId(),
                    "artifacts-" + context.getExecutionContext().getRunId(), name, generated.contentType,
                    generated.bytes, sha);
            String id = ids.nextId();
            try {
                jdbc.update("insert into ai_artifact "
                                + "(id,tenant_id,workspace_id,run_id,node_run_id,artifact_type,name,content_type,"
                                + "object_key,size_bytes,sha256,metadata_json,expires_at,status,created_by,updated_by) "
                                + "values (?,?,?,?,?,'FILE',?,?,?,?,?,'{}',?,'ACTIVE',?,?)",
                        id, context.getExecutionContext().getTenantId(),
                        context.getExecutionContext().getWorkspaceId(), context.getExecutionContext().getRunId(),
                        context.getNodeRunId(), name, generated.contentType, object.getObjectKey(),
                        generated.bytes.length, sha, Timestamp.from(Instant.now().plusSeconds(86400)),
                        context.getExecutionContext().getUserId(), context.getExecutionContext().getUserId());
            } catch (RuntimeException exception) {
                storage.delete(object.getBucket(), object.getObjectKey());
                throw exception;
            }
            ArtifactReference reference = new ArtifactReference(id, name, generated.contentType,
                    generated.bytes.length, "/api/v1/artifacts/" + id);
            String outputVariable = config.path("outputVariable").asText("artifact");
            return new NodeExecutionResult(com.hmdp.ai.domain.run.NodeRunStatus.SUCCEEDED,
                    mapper.valueToTree(reference), null, Collections.singletonMap(outputVariable, reference),
                    Collections.singletonList(reference), null, null, UsageSummary.empty(0), false, null);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(e.getMessage(), false);
        } catch (Exception e) {
            return NodeExecutionResult.failure("ARTIFACT_GENERATION_FAILED", true);
        }
    }

    private Generated generate(String format, JsonNode value) throws Exception {
        switch (format) {
            case "JSON": return new Generated(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
                    "application/json", ".json");
            case "TXT": return new Generated(value.isTextual() ? value.asText().getBytes(StandardCharsets.UTF_8)
                    : mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), "text/plain", ".txt");
            case "CSV": return new Generated(csv(value).getBytes(StandardCharsets.UTF_8), "text/csv", ".csv");
            case "XLSX": return new Generated(xlsx(value),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
            default: throw new IllegalArgumentException("ARTIFACT_FORMAT_UNSUPPORTED");
        }
    }

    private String csv(JsonNode value) {
        List<JsonNode> rows = rows(value);
        Set<String> headers = headers(rows);
        StringBuilder output = new StringBuilder();
        output.append(String.join(",", headers.stream().map(this::escape).toArray(String[]::new))).append('\n');
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (String header : headers) cells.add(escape(row.path(header).asText("")));
            output.append(String.join(",", cells)).append('\n');
        }
        return output.toString();
    }

    private byte[] xlsx(JsonNode value) throws Exception {
        List<JsonNode> values = rows(value);
        Set<String> headers = headers(values);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("data");
            Row headerRow = sheet.createRow(0);
            int column = 0;
            for (String header : headers) headerRow.createCell(column++).setCellValue(header);
            int rowNumber = 1;
            for (JsonNode valueRow : values) {
                Row row = sheet.createRow(rowNumber++);
                column = 0;
                for (String header : headers) {
                    Cell cell = row.createCell(column++);
                    JsonNode field = valueRow.path(header);
                    if (field.isNumber()) cell.setCellValue(field.asDouble());
                    else if (field.isBoolean()) cell.setCellValue(field.asBoolean());
                    else cell.setCellValue(field.asText(""));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private List<JsonNode> rows(JsonNode value) {
        List<JsonNode> rows = new ArrayList<>();
        if (value.isArray()) value.forEach(rows::add);
        else rows.add(value);
        if (rows.stream().anyMatch(row -> !row.isObject())) {
            throw new IllegalArgumentException("ARTIFACT_TABULAR_OBJECT_REQUIRED");
        }
        return rows;
    }

    private Set<String> headers(List<JsonNode> rows) {
        Set<String> headers = new LinkedHashSet<>();
        rows.forEach(row -> row.fieldNames().forEachRemaining(headers::add));
        return headers;
    }

    private String escape(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String safeName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,200}") || value.contains("..")) {
            throw new IllegalArgumentException("ARTIFACT_NAME_INVALID");
        }
        return value;
    }

    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static final class Generated {
        private final byte[] bytes;
        private final String contentType;
        private final String extension;

        private Generated(byte[] bytes, String contentType, String extension) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.extension = extension;
        }
    }
}

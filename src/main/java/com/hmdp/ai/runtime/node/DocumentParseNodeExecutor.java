package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.ArtifactRecord;
import com.hmdp.ai.domain.artifact.ArtifactRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentParsingPort;
import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;
import com.hmdp.ai.domain.knowledge.parsing.ParsedFile;
import com.hmdp.ai.domain.run.AttachmentReference;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DocumentParseNodeExecutor implements NodeExecutor {
    private final ArtifactRepository artifacts;
    private final ObjectStoragePort storage;
    private final DocumentParsingPort parsers;
    private final ObjectMapper mapper;
    private final String artifactBucket;

    public DocumentParseNodeExecutor(ArtifactRepository artifacts, ObjectStoragePort storage,
                                     DocumentParsingPort parsers, ObjectMapper mapper,
                                     @Value("${minio.bucket:hmdp-ai}") String artifactBucket) {
        this.artifacts = artifacts;
        this.storage = storage;
        this.parsers = parsers;
        this.mapper = mapper;
        this.artifactBucket = artifactBucket;
    }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.DOCUMENT_PARSE);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            Source source = source(context, config);
            Set<String> allowed = new LinkedHashSet<>();
            config.path("allowedMimeTypes").forEach(value ->
                    allowed.add(value.asText().toLowerCase(Locale.ROOT)));
            if (!allowed.isEmpty() && !allowed.contains(source.contentType.toLowerCase(Locale.ROOT))) {
                return NodeExecutionResult.failure("DOCUMENT_MIME_UNSUPPORTED", false);
            }
            byte[] bytes;
            try (InputStream input = storage.get(source.bucket, source.objectKey)) {
                long budget = context.getExecutionContext().getExecutionBudget().getMaxArtifactBytes();
                long configured = config.path("maxBytes").asLong(budget);
                long maximum = Math.min(budget, configured > 0 ? configured : budget);
                if (maximum >= Integer.MAX_VALUE) maximum = Integer.MAX_VALUE - 1L;
                bytes = input.readNBytes((int) maximum + 1);
                if (bytes.length > maximum) {
                    return NodeExecutionResult.failure("DOCUMENT_SIZE_LIMIT_EXCEEDED", false);
                }
            }
            ParsedFile parsed = parsers.parse(bytes, source.name, source.contentType);
            validateLimits(parsed.getDocument(), config);
            String outputVariable = config.path("outputVariable").asText("parsedDocument");
            return NodeExecutionResult.success(mapper.valueToTree(parsed.getDocument()), null,
                    Collections.singletonMap(outputVariable, parsed.getDocument()));
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(e.getMessage(), false);
        } catch (Exception e) {
            return NodeExecutionResult.failure("DOCUMENT_PARSE_FAILED", true);
        }
    }

    private Source source(NodeExecutionContext context, JsonNode config) {
        String variable = config.path("attachmentVariable").asText();
        if (variable.trim().isEmpty()) {
            variable = config.path("referenceVariable").asText();
        }
        Object value = variable.isEmpty() ? null : context.getVariables().get(variable);
        String artifactId = extractArtifactId(value);
        if (artifactId != null) {
            ArtifactRecord record = artifacts.find(context.getExecutionContext().getTenantId(),
                            context.getExecutionContext().getWorkspaceId(), artifactId)
                    .orElseThrow(() -> new IllegalArgumentException("DOCUMENT_ARTIFACT_NOT_FOUND"));
            if (!record.getRunId().equals(context.getExecutionContext().getRunId())
                    && !record.getCreatedBy().equals(context.getExecutionContext().getUserId())) {
                throw new IllegalArgumentException("DOCUMENT_ARTIFACT_FORBIDDEN");
            }
            return new Source(artifactBucket, record.getObjectKey(), record.getName(), record.getContentType());
        }
        AttachmentReference attachment = findAttachment(context, value, config);
        if (attachment == null || attachment.getUri() == null
                || !attachment.getUri().startsWith("minio://")) {
            throw new IllegalArgumentException("DOCUMENT_INPUT_REQUIRED");
        }
        String path = attachment.getUri().substring("minio://".length());
        int separator = path.indexOf('/');
        if (separator <= 0 || separator == path.length() - 1 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("DOCUMENT_URI_INVALID");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("DOCUMENT_URI_INVALID");
            }
        }
        String bucket = path.substring(0, separator);
        if (!artifactBucket.equals(bucket)) throw new IllegalArgumentException("DOCUMENT_BUCKET_FORBIDDEN");
        return new Source(bucket, path.substring(separator + 1), attachment.getName(), attachment.getContentType());
    }

    private String extractArtifactId(Object value) {
        if (value instanceof String && !((String) value).trim().isEmpty()
                && !((String) value).startsWith("minio://")) return (String) value;
        if (value instanceof Map) {
            Object id = ((Map<?, ?>) value).get("artifactId");
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    private AttachmentReference findAttachment(NodeExecutionContext context, Object value, JsonNode config) {
        if (value instanceof AttachmentReference) return (AttachmentReference) value;
        if (value instanceof String && ((String) value).startsWith("minio://")) {
            return new AttachmentReference("workflow-reference", config.path("fileName").asText("document"),
                    config.path("declaredMimeType").asText("application/octet-stream"), 0, (String) value);
        }
        if (value instanceof Map) {
            Map<?, ?> reference = (Map<?, ?>) value;
            Object uri = reference.get("uri");
            if (uri != null) {
                return new AttachmentReference(text(reference, "attachmentId", "reference"),
                        text(reference, "name", "document"),
                        text(reference, "contentType", "application/octet-stream"),
                        number(reference.get("sizeBytes")), String.valueOf(uri));
            }
        }
        String key = value == null ? null : String.valueOf(value);
        return context.getExecutionContext().getAttachments().stream()
                .filter(item -> key == null || key.equals(item.getAttachmentId()) || key.equals(item.getName()))
                .findFirst().orElse(null);
    }

    private long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String text(Map<?, ?> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private void validateLimits(ParsedDocument document, JsonNode config) {
        int maxPages = Math.max(1, config.path("maxPages").asInt(500));
        int maxRows = Math.max(1, config.path("maxRows").asInt(200_000));
        int maxCells = Math.max(1, config.path("maxCells").asInt(1_000_000));
        int sectionPages = document.getSections().stream()
                .map(section -> section.getPage() == null ? 0 : section.getPage())
                .max(Integer::compareTo).orElse(0);
        int tablePages = document.getTables().stream()
                .map(table -> table.getPage() == null ? 0 : table.getPage())
                .max(Integer::compareTo).orElse(0);
        int pages = Math.max(sectionPages, tablePages);
        int cells = document.getTables().stream().mapToInt(table -> table.getCells().size()).sum();
        long rows = document.getTables().stream().mapToLong(table -> table.getCells().stream()
                .map(cell -> cell.getRow()).distinct().count()).sum();
        if (pages > maxPages) throw new IllegalArgumentException("DOCUMENT_PAGE_LIMIT_EXCEEDED");
        if (rows > maxRows) throw new IllegalArgumentException("DOCUMENT_ROW_LIMIT_EXCEEDED");
        if (cells > maxCells) throw new IllegalArgumentException("DOCUMENT_CELL_LIMIT_EXCEEDED");
    }

    private static final class Source {
        private final String bucket;
        private final String objectKey;
        private final String name;
        private final String contentType;

        private Source(String bucket, String objectKey, String name, String contentType) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.name = name;
            this.contentType = contentType;
        }
    }
}

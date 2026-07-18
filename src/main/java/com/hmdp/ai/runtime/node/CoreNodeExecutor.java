package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CoreNodeExecutor implements NodeExecutor {
    private static final Set<WorkflowNodeType> TYPES = EnumSet.of(
            WorkflowNodeType.START, WorkflowNodeType.END, WorkflowNodeType.QUERY_REWRITE,
            WorkflowNodeType.KNOWLEDGE_RETRIEVE, WorkflowNodeType.SEMANTIC_SEARCH,
            WorkflowNodeType.EXTERNAL_SEARCH, WorkflowNodeType.MCP_TOOL, WorkflowNodeType.DIFY_WORKFLOW,
            WorkflowNodeType.DOCUMENT_PARSE, WorkflowNodeType.TEXT_TRANSFORM,
            WorkflowNodeType.LONG_TEXT_MAP_REDUCE, WorkflowNodeType.DATA_TRANSFORM,
            WorkflowNodeType.JOIN, WorkflowNodeType.ARTIFACT_GENERATE);

    private final ObjectMapper mapper;

    public CoreNodeExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return TYPES;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        WorkflowNodeType type = context.getNode().getType();
        ObjectNode output = mapper.createObjectNode();
        output.put("nodeType", type.name());
        Map<String, Object> updates = new LinkedHashMap<>();
        if (type == WorkflowNodeType.QUERY_REWRITE) {
            String query = String.valueOf(context.getVariables().getOrDefault("text", ""))
                    .trim().replaceAll("\\s+", " ");
            updates.put("query", query);
            output.put("query", query);
        } else if (type == WorkflowNodeType.TEXT_TRANSFORM) {
            String text = String.valueOf(context.getVariables().getOrDefault("text", "")).trim();
            updates.put("text", text);
            output.put("text", text);
        } else if (type == WorkflowNodeType.LONG_TEXT_MAP_REDUCE) {
            String text = String.valueOf(context.getVariables().getOrDefault("text", ""));
            List<String> chunks = new ArrayList<>();
            for (int offset = 0; offset < text.length(); offset += 2000) {
                chunks.add(text.substring(offset, Math.min(text.length(), offset + 2000)));
            }
            updates.put("longTextChunks", chunks);
            output.put("chunkCount", chunks.size());
        } else if (type == WorkflowNodeType.KNOWLEDGE_RETRIEVE
                || type == WorkflowNodeType.SEMANTIC_SEARCH) {
            return NodeExecutionResult.failure("KNOWLEDGE_PROVIDER_NOT_CONFIGURED", false);
        } else if (type == WorkflowNodeType.EXTERNAL_SEARCH) {
            return NodeExecutionResult.failure("EXTERNAL_SEARCH_PROVIDER_NOT_CONFIGURED", false);
        } else if (type == WorkflowNodeType.MCP_TOOL) {
            return NodeExecutionResult.failure("MCP_PROVIDER_NOT_CONFIGURED", false);
        } else if (type == WorkflowNodeType.DIFY_WORKFLOW) {
            return NodeExecutionResult.failure("DIFY_PROVIDER_NOT_CONFIGURED", false);
        } else if (type == WorkflowNodeType.DOCUMENT_PARSE) {
            return NodeExecutionResult.failure("DOCUMENT_INPUT_REQUIRED", false);
        }
        return NodeExecutionResult.success(output, null, updates);
    }
}

package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.memory.MemoryRecallPipeline;
import com.hmdp.ai.runtime.memory.MemoryRecallResult;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class MemoryRecallNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;
    private final MemoryRecallPipeline pipeline;

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryRecallNodeExecutor(ObjectMapper mapper, MemoryRecallPipeline pipeline) {
        this.mapper = mapper;
        this.pipeline = pipeline;
    }

    public MemoryRecallNodeExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
        this.pipeline = null;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.MEMORY_RECALL);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Map<String, Object> recall;
        if (pipeline != null) {
            MemoryRecallResult result = pipeline.recall(context.getExecutionContext());
            recall = mapper.convertValue(result, Map.class);
        } else {
            recall = new LinkedHashMap<>();
        }
        if (pipeline == null) {
            recall.put("facts", context.getVariables().getOrDefault("memoryFacts", Collections.emptyList()));
            recall.put("episodes", context.getVariables().getOrDefault("memoryEpisodes", Collections.emptyList()));
            recall.put("conversationSummary", context.getVariables().getOrDefault("conversationSummary", ""));
            recall.put("profile", context.getVariables().getOrDefault("userProfile", Collections.emptyMap()));
            recall.put("warnings", Collections.emptyList());
            recall.put("provenance", Collections.singletonMap("runId", context.getExecutionContext().getRunId()));
        }
        String variable = "memoryRecall";
        try {
            com.fasterxml.jackson.databind.JsonNode configuration = mapper.readTree(
                    context.getNode().getConfigurationJson());
            variable = configuration.path("outputVariable").asText(variable);
        } catch (Exception ignored) {
            // Empty configuration uses the stable default variable.
        }
        return NodeExecutionResult.success(mapper.valueToTree(recall), null,
                Collections.singletonMap(variable, recall));
    }
}

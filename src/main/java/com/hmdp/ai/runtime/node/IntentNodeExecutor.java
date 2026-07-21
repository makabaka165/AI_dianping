package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.intent.EntityExtractionService;
import com.hmdp.ai.runtime.intent.IntentClassification;
import com.hmdp.ai.runtime.intent.IntentFusionService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class IntentNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;
    private final EntityExtractionService entities;
    private final IntentFusionService fusion;

    public IntentNodeExecutor(ObjectMapper mapper, EntityExtractionService entities, IntentFusionService fusion) {
        this.mapper = mapper;
        this.entities = entities;
        this.fusion = fusion;
    }

    @Override
    public Set<WorkflowNodeType> supportedTypes() {
        return EnumSet.of(WorkflowNodeType.INTENT_CLASSIFY, WorkflowNodeType.ENTITY_EXTRACT);
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        String text = String.valueOf(context.getVariables().getOrDefault("text", ""));
        Map<String, Object> extracted = entities.extract(text, context.getVariables());
        if (context.getNode().getType() == WorkflowNodeType.ENTITY_EXTRACT) {
            Map<String, Object> updates = new LinkedHashMap<>(extracted);
            updates.put("entities", extracted);
            return NodeExecutionResult.success(mapper.valueToTree(extracted), null, updates);
        }

        IntentClassification classification = fusion.classify(text, extracted, context);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("intent", classification.getPrimaryIntent());
        updates.put("primaryIntent", classification.getPrimaryIntent());
        updates.put("secondaryIntents", classification.getSecondaryIntents());
        updates.put("intentConfidence", classification.getConfidence());
        updates.put("entities", classification.getEntities());
        updates.put("missingSlots", classification.getMissingSlots());
        updates.put("requiresClarification", classification.isRequiresClarification());
        updates.putAll(classification.getEntities());
        return NodeExecutionResult.success(mapper.valueToTree(classification), null, updates);
    }
}

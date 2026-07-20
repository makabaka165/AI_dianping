package com.hmdp.ai.runtime.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class LongTextMapReduceNodeExecutor implements NodeExecutor {
    private final ObjectMapper mapper;

    public LongTextMapReduceNodeExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    public Set<WorkflowNodeType> supportedTypes() {
        return Collections.singleton(WorkflowNodeType.LONG_TEXT_MAP_REDUCE);
    }

    public NodeExecutionResult execute(NodeExecutionContext context) {
        try {
            JsonNode config = mapper.readTree(context.getNode().getConfigurationJson());
            String inputVariable = config.path("inputVariable").asText("text");
            String outputVariable = config.path("outputVariable").asText("mapReduceResult");
            String text = String.valueOf(context.getVariables().getOrDefault(inputVariable, ""));
            if (text.trim().isEmpty()) return NodeExecutionResult.failure("LONG_TEXT_INPUT_REQUIRED", false);
            int maxChars = Math.max(256, config.path("chunkChars").asInt(4000));
            List<Chunk> chunks = split(text, maxChars);
            ArrayNode maps = mapper.createArrayNode();
            ArrayNode ledger = mapper.createArrayNode();
            Map<String, ObjectNode> uniqueClaims = new LinkedHashMap<>();
            Set<String> conflicts = new LinkedHashSet<>();
            for (Chunk chunk : chunks) {
                String[] sentences = Pattern.compile("(?<=[.!?。！？])\\s*")
                        .split(chunk.text);
                int sentenceOffset = 0;
                for (String sentence : sentences) {
                    String claim = sentence.trim();
                    if (claim.isEmpty()) continue;
                    String normalizedClaim = claim.replaceAll("\\s+", " ").trim();
                    String claimId = "claim-" + Integer.toHexString(normalizedClaim.toLowerCase(Locale.ROOT).hashCode());
                    int localStart = chunk.text.indexOf(sentence, sentenceOffset);
                    if (localStart < 0) localStart = sentenceOffset;
                    sentenceOffset = Math.max(localStart + sentence.length(), sentenceOffset);
                    ObjectNode evidence = uniqueClaims.get(claimId);
                    if (evidence == null) {
                        evidence = mapper.createObjectNode().put("claimId", claimId)
                                .put("claim", normalizedClaim).put("confidence", 0.7);
                        evidence.putArray("evidenceIds");
                        evidence.putArray("conflicts");
                        evidence.putArray("sourceLocations");
                        uniqueClaims.put(claimId, evidence);
                    } else if (!normalizedClaim.equals(evidence.path("claim").asText())) {
                        conflicts.add(claimId);
                        addUnique(evidence.withArray("conflicts"), claimId);
                    }
                    String evidenceId = "chunk-" + chunk.index;
                    addUnique(evidence.withArray("evidenceIds"), evidenceId);
                    ObjectNode location = mapper.createObjectNode()
                            .put("chunkId", evidenceId)
                            .put("start", chunk.start + localStart)
                            .put("end", chunk.start + localStart + sentence.length());
                    evidence.withArray("sourceLocations").add(location);
                }
                maps.add(mapper.createObjectNode().put("chunkId", "chunk-" + chunk.index)
                        .put("text", chunk.text).put("schemaValid", true));
            }
            ObjectNode reduced = mapper.createObjectNode();
            reduced.set("maps", maps);
            uniqueClaims.values().forEach(ledger::add);
            reduced.set("evidenceLedger", ledger);
            ArrayNode conflictNodes = reduced.putArray("conflicts");
            conflicts.forEach(conflictNodes::add);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(outputVariable, mapper.convertValue(reduced, Object.class));
            updates.put("evidenceLedger", mapper.convertValue(ledger, Object.class));
            return NodeExecutionResult.success(reduced, null, updates);
        } catch (Exception e) {
            return NodeExecutionResult.failure("LONG_TEXT_MAP_REDUCE_FAILED", false);
        }
    }

    private List<Chunk> split(String text, int maxChars) {
        List<Chunk> result = new ArrayList<>();
        int offset = 0;
        int index = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + maxChars);
            if (end < text.length()) {
                int boundary = Math.max(offset, text.lastIndexOf('\n', end));
                if (boundary > offset + maxChars / 3) end = boundary;
            }
            result.add(new Chunk(index++, offset, text.substring(offset, end)));
            offset = end;
        }
        return result;
    }

    private void addUnique(ArrayNode values, String value) {
        for (JsonNode existing : values) {
            if (value.equals(existing.asText())) return;
        }
        values.add(value);
    }

    private static final class Chunk {
        private final int index;
        private final int start;
        private final String text;

        private Chunk(int index, int start, String text) {
            this.index = index;
            this.start = start;
            this.text = text;
        }
    }
}

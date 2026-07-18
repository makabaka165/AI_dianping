package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.artifact.ResponseBlock;
import com.hmdp.ai.domain.artifact.ResponseBlockType;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentOutputAssembler {
    private final ObjectMapper objectMapper;

    public AgentOutputAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentRunOutput fromShopResponse(ShopAIResponse response, long durationMs) {
        String answer = responseText(response);
        Map<String, Object> structured = objectMapper.convertValue(response, Map.class);
        List<ResponseBlock> blocks = Collections.singletonList(
                new ResponseBlock(ResponseBlockType.MARKDOWN, answer, structured));
        List<Citation> citations = citations(response == null ? null : response.getEvidence());
        List<String> warnings = new ArrayList<>();
        if (response != null && Boolean.TRUE.equals(response.getDegraded())) {
            warnings.add("DEGRADED_RESPONSE");
        }
        if (response != null && response.getFallbackReason() != null) {
            warnings.add("FALLBACK:" + response.getFallbackReason());
        }
        return new AgentRunOutput(answer, blocks, citations, Collections.emptyList(),
                UsageSummary.empty(durationMs), warnings, RunStatus.COMPLETED);
    }

    private List<Citation> citations(List<EvidenceItem> evidence) {
        if (evidence == null) return Collections.emptyList();
        return evidence.stream().map(item -> new Citation(item.getId(), null,
                        item.getSourceId() == null ? null : String.valueOf(item.getSourceId()), 1, item.getId(),
                        item.getTitle(), item.getType() == null ? null : item.getType().name(), null,
                        item.getMatchedReason(), null, null, item.getScore() == null ? 0 : item.getScore(),
                        item.getSnippet()))
                .collect(Collectors.toList());
    }

    private String responseText(ShopAIResponse response) {
        if (response == null) return "";
        if (response.getSummary() != null) return safe(response.getSummary().getCoreSummary());
        if (response.getQa() != null) return safe(response.getQa().getAnswer());
        if (response.getCompare() != null) return safe(response.getCompare().getConclusion());
        if (response.getRecommend() != null) {
            if (!blank(response.getRecommend().getMessage())) return response.getRecommend().getMessage();
            return response.getRecommend().safeItems().stream()
                    .map(item -> item.getRank() + ". " + item.getShopName() + ": " + item.getReason())
                    .collect(Collectors.joining("\n"));
        }
        if (response.getChat() != null) return safe(response.getChat().getMessage());
        return "";
    }

    private String safe(String value) { return value == null ? "" : value; }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}

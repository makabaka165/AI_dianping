package com.hmdp.ai.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.EvidenceItem;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EvidencePromptSerializer {

    private static final int SNIPPET_LIMIT = 300;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String serialize(List<EvidenceItem> evidence) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (evidence != null) {
            for (EvidenceItem item : evidence) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("evidenceId", item.getId());
                map.put("type", item.getType() == null ? null : item.getType().name());
                map.put("shopId", item.getShopId());
                map.put("sourceId", item.getSourceId());
                map.put("liked", item.getLiked());
                map.put("matchedReason", item.getMatchedReason());
                map.put("score", item.getScore());
                map.put("createdAt", item.getCreatedAt() == null ? null : item.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                map.put("untrustedText", true);
                map.put("snippet", sanitizeSnippet(item.getSnippet()));
                items.add(map);
            }
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String sanitizeSnippet(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String trimmed = value.trim();
        int count = 0;
        for (int i = 0; i < trimmed.length() && count < SNIPPET_LIMIT; i++) {
            char c = trimmed.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || !Character.isISOControl(c)) {
                builder.append(c);
                count++;
            }
        }
        if (trimmed.length() > count) {
            builder.append("...[truncated]");
        }
        return builder.toString();
    }
}

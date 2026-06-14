package com.hmdp.ai.retrieval;

import com.hmdp.dto.ai.EvidenceItem;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class EvidenceReranker {

    private static final int MAX_LIMIT = 10;

    public List<EvidenceItem> rerank(List<EvidenceItem> evidence, String query, String aspect, Integer limit) {
        if (evidence == null || evidence.isEmpty()) {
            return new ArrayList<>();
        }
        int safeLimit = normalizeLimit(limit);
        return evidence.stream()
                .filter(item -> item != null && item.getId() != null)
                .peek(item -> item.setScore(score(item, query, aspect)))
                .sorted(Comparator.comparing(EvidenceItem::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(EvidenceItem::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .collect(Collectors.toList());
    }

    private double score(EvidenceItem item, String query, String aspect) {
        double score = item.getScore() == null ? 0.0 : item.getScore();
        int liked = item.getLiked() == null ? 0 : item.getLiked();
        score += Math.min(0.20, liked / 200.0);
        if (isRecent(item.getCreatedAt())) {
            score += 0.05;
        }
        String text = (safe(item.getTitle()) + " " + safe(item.getSnippet())).toLowerCase(Locale.ROOT);
        if (containsAny(text, query, aspect)) {
            score += 0.20;
        }
        if (containsNegative(text)) {
            score += 0.03;
        }
        return Math.min(1.0, score);
    }

    private boolean containsAny(String text, String query, String aspect) {
        return containsText(text, query) || containsText(text, aspect);
    }

    private boolean containsText(String text, String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.replaceAll("[，。！？、,.;:：\\s]+", " ").trim();
        for (String term : normalized.split(" ")) {
            String safeTerm = term.trim().toLowerCase(Locale.ROOT);
            if (safeTerm.length() >= 2 && text.contains(safeTerm)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNegative(String text) {
        return text.contains("差") || text.contains("失望") || text.contains("不好")
                || text.contains("一般") || text.contains("贵") || text.contains("慢")
                || text.contains("坑");
    }

    private boolean isRecent(LocalDateTime createdAt) {
        if (createdAt == null) {
            return false;
        }
        return Duration.between(createdAt, LocalDateTime.now()).toDays() <= 30;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return MAX_LIMIT;
        }
        return Math.min(MAX_LIMIT, limit);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

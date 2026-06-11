package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAIAnalysisResult {
    private String summary;
    private String sentiment;
    private List<String> keywords;
    private List<String> pros;
    private List<String> cons;
    private Double confidence;
    private List<Long> evidenceIds;
    private Boolean degraded;

    public List<String> safeKeywords() {
        return keywords == null ? new ArrayList<>() : keywords;
    }

    public List<Long> safeEvidenceIds() {
        return evidenceIds == null ? new ArrayList<>() : evidenceIds;
    }
}

package com.hmdp.ai.workflow;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamWorkflowPlan {
    private String analysisType;
    private String memoryId;
    private String prompt;
    private String promptVersion;
    private String directText;
    private List<EvidenceItem> evidence;
    private Double confidence;
    private Boolean degraded;
    private Boolean cacheHit;
    private Boolean structuredOutput;
    private Long expectedShopId;
    private Long expectedShopId1;
    private Long expectedShopId2;
    private String expectedQuestion;
    private String expectedAspect;
    private String expectedUserPreference;
    private String expectedCategory;
    private Integer requestedLimit;
    private Set<Long> candidateShopIds;
    private List<ShopView> candidateShops;

    public List<EvidenceItem> safeEvidence() {
        return evidence == null ? Collections.emptyList() : evidence;
    }

    public boolean hasDirectText() {
        return directText != null && !directText.trim().isEmpty();
    }

    public boolean isStructuredOutput() {
        return Boolean.TRUE.equals(structuredOutput);
    }
}

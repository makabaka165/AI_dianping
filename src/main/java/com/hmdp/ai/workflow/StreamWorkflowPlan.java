package com.hmdp.ai.workflow;

import com.hmdp.dto.ai.EvidenceItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

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

    public List<EvidenceItem> safeEvidence() {
        return evidence == null ? Collections.emptyList() : evidence;
    }

    public boolean hasDirectText() {
        return directText != null && !directText.trim().isEmpty();
    }
}

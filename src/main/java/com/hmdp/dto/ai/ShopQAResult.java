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
public class ShopQAResult {
    private Long shopId;
    private String question;
    private String answer;
    private List<String> evidenceIds;
    private Boolean insufficientEvidence;

    public List<String> safeEvidenceIds() {
        return evidenceIds == null ? new ArrayList<>() : evidenceIds;
    }
}

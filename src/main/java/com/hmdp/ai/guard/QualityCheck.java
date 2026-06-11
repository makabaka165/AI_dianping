package com.hmdp.ai.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheck {
    private QualityDecision decision;
    private String reason;

    public boolean pass() {
        return decision == QualityDecision.PASS;
    }
}

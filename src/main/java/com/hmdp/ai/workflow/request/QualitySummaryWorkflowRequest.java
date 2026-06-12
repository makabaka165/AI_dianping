package com.hmdp.ai.workflow.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualitySummaryWorkflowRequest {
    private Long shopId;
    private Integer minLiked;
    private Integer limit;
    private boolean writeMemory;
}

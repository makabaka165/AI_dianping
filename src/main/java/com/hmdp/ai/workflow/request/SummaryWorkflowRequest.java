package com.hmdp.ai.workflow.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryWorkflowRequest {
    private Long shopId;
    private boolean writeMemory;
}

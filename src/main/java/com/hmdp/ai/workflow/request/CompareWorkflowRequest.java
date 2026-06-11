package com.hmdp.ai.workflow.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareWorkflowRequest {
    private Long shopId1;
    private Long shopId2;
    private String aspect;
}

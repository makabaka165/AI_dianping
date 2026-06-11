package com.hmdp.ai.workflow.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendWorkflowRequest {
    private String userPreference;
    private String category;
    private Integer limit;
}

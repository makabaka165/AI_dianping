package com.hmdp.ai.application.dto.approval;

import javax.validation.constraints.Size;

public class ApprovalDecisionRequest {
    @Size(max = 1000)
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

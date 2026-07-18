package com.hmdp.ai.domain.workflow;

public enum WorkflowStateStatus {
    RUNNING,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

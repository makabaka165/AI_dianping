package com.hmdp.ai.domain.run;

public enum NodeRunStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    TIMED_OUT
}

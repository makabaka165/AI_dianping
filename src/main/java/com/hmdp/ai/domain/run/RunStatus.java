package com.hmdp.ai.domain.run;

public enum RunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}

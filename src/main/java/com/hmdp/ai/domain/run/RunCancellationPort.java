package com.hmdp.ai.domain.run;

public interface RunCancellationPort {
    void cancel(String runId);
}

package com.hmdp.ai.domain.run;

public interface RunCompletionObserver {
    void onCompleted(AgentRunRecord run, String outputJson);
}

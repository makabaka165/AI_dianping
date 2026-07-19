package com.hmdp.ai.runtime.model;

import com.hmdp.ai.domain.model.ModelProfileVersion;

public interface ModelCallRecorder {
    void success(ModelProfileVersion profile, ModelInvocation invocation, ModelInvocationResult result,
                 long startedAtMillis);

    void failure(ModelProfileVersion profile, ModelInvocation invocation, String errorCode,
                 long startedAtMillis, long latencyMs);
}

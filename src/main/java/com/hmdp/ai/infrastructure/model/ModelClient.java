package com.hmdp.ai.infrastructure.model;

import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;

public interface ModelClient {
    ModelInvocationResult invoke(ModelInvocation invocation);
}

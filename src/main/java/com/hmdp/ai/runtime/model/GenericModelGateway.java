package com.hmdp.ai.runtime.model;

public interface GenericModelGateway {
    ModelInvocationResult invoke(ModelInvocation invocation);
}

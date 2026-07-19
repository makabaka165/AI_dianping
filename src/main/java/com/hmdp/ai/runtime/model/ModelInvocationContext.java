package com.hmdp.ai.runtime.model;

import com.hmdp.ai.domain.observability.InvocationContext;

import java.util.Objects;

public final class ModelInvocationContext {
    private final InvocationContext invocationContext;

    public ModelInvocationContext(InvocationContext invocationContext) {
        this.invocationContext = Objects.requireNonNull(invocationContext, "invocationContext");
    }

    public InvocationContext getInvocationContext() { return invocationContext; }
}

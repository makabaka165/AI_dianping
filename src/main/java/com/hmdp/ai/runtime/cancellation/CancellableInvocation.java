package com.hmdp.ai.runtime.cancellation;

@FunctionalInterface
public interface CancellableInvocation {
    void cancel();
}

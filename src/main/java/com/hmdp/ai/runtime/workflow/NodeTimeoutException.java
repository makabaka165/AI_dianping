package com.hmdp.ai.runtime.workflow;

public final class NodeTimeoutException extends RuntimeException {
    public NodeTimeoutException(String errorCode) {
        super(errorCode);
    }
}

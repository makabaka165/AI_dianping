package com.hmdp.ai.runtime.cancellation;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("RUN_CANCELLED");
        }
    }
}

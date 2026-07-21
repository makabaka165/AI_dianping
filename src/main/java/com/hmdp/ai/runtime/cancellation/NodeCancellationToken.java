package com.hmdp.ai.runtime.cancellation;

import com.hmdp.ai.runtime.workflow.NodeTimeoutException;

import java.time.Instant;
import java.util.concurrent.CancellationException;

/**
 * Node-scoped view of run cancellation plus the immutable run deadline.
 */
public final class NodeCancellationToken {
    private final CancellationToken runToken;
    private final Instant deadline;

    public NodeCancellationToken(CancellationToken runToken, Instant deadline) {
        this.runToken = runToken;
        this.deadline = deadline;
    }

    public boolean isCancellationRequested() {
        return Thread.currentThread().isInterrupted() || (runToken != null && runToken.isCancelled());
    }

    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("RUN_CANCELLED");
        }
        if (!deadline.isAfter(Instant.now())) {
            throw new NodeTimeoutException("NODE_DEADLINE_EXCEEDED");
        }
    }

    public long remainingMillis() {
        return Math.max(0, java.time.Duration.between(Instant.now(), deadline).toMillis());
    }

    public void awaitBackoff(long delayMs) {
        long remaining = Math.max(0, delayMs);
        while (remaining > 0) {
            throwIfCancellationRequested();
            long slice = Math.min(remaining, 50);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("RUN_CANCELLED");
            }
            remaining -= slice;
        }
    }
}

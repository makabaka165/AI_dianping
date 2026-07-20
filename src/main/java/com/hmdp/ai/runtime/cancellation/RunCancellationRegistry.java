package com.hmdp.ai.runtime.cancellation;

import com.hmdp.ai.domain.run.RunCancellationPort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Component
public class RunCancellationRegistry implements RunCancellationPort {
    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Set<Future<?>>> active = new ConcurrentHashMap<>();
    private final Map<String, Set<CancellableInvocation>> invocations = new ConcurrentHashMap<>();

    public CancellationToken begin(String runId) {
        return tokens.computeIfAbsent(runId, ignored -> new CancellationToken());
    }

    public CancellationToken token(String runId) {
        return tokens.get(runId);
    }

    public void track(String runId, Future<?> future) {
        if (runId == null || future == null) {
            return;
        }
        active.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet()).add(future);
        CancellationToken token = token(runId);
        if (token != null && token.isCancelled()) {
            future.cancel(true);
        }
    }

    public void untrack(String runId, Future<?> future) {
        Set<Future<?>> futures = active.get(runId);
        if (futures == null) {
            return;
        }
        futures.remove(future);
        if (futures.isEmpty()) {
            active.remove(runId, futures);
        }
    }

    public void track(String runId, CancellableInvocation invocation) {
        if (runId == null || invocation == null) {
            return;
        }
        invocations.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet()).add(invocation);
        CancellationToken token = token(runId);
        if (token != null && token.isCancelled()) {
            invocation.cancel();
        }
    }

    public void untrack(String runId, CancellableInvocation invocation) {
        Set<CancellableInvocation> values = invocations.get(runId);
        if (values == null) {
            return;
        }
        values.remove(invocation);
        if (values.isEmpty()) {
            invocations.remove(runId, values);
        }
    }

    @Override
    public void cancel(String runId) {
        CancellationToken token = begin(runId);
        token.cancel();
        Set<Future<?>> futures = active.get(runId);
        if (futures != null) {
            futures.forEach(future -> future.cancel(true));
        }
        Set<CancellableInvocation> values = invocations.get(runId);
        if (values != null) {
            values.forEach(CancellableInvocation::cancel);
        }
    }

    public void end(String runId) {
        tokens.remove(runId);
        active.remove(runId);
        invocations.remove(runId);
    }
}

package com.hmdp.ai.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class AiMetricsService {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public AiMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    public void increment(String metric, String analysisType, boolean degraded) {
        increment(metric, tags(analysisType, null, null, null, degraded, null, null, null));
    }

    public void recordDuration(String analysisType, long durationMillis, boolean degraded) {
        increment("ai.request.count", tags(analysisType, null, null, null, degraded, null, null, null));
        recordTimer("ai.request.duration", durationMillis,
                tags(analysisType, null, null, null, degraded, null, null, null));
        log.debug("AI request duration analysisType={}, degraded={}, durationMs={}",
                analysisType, degraded, durationMillis);
    }

    public void recordRequestDuration(String analysisType,
                                      String intent,
                                      String modelName,
                                      long durationMillis,
                                      boolean degraded,
                                      boolean cacheHit,
                                      String result) {
        recordTimer("ai.request.duration", durationMillis,
                tags(analysisType, intent, modelName, null, degraded, cacheHit, null, result));
    }

    public void recordModelCall(String analysisType,
                                String operation,
                                String modelName,
                                long durationMillis,
                                boolean success,
                                int inputTokens,
                                int outputTokens) {
        Tags tags = tags(analysisType, null, modelName, operation, false, null, "model",
                success ? "success" : "failure");
        recordTimer("ai.model.duration", durationMillis, tags);
        recordAmount("ai.model.tokens.estimated", inputTokens, tags.and("direction", "input"));
        recordAmount("ai.model.tokens.estimated", outputTokens, tags.and("direction", "output"));
    }

    public void recordEvidenceCount(String analysisType, int count, String source) {
        recordAmount("ai.evidence.count", count,
                tags(analysisType, null, null, null, false, null, source, "success"));
    }

    public void recordRagSearch(String analysisType, long durationMillis, int count, boolean success) {
        Tags tags = tags(analysisType, null, null, "rag.search", false, null, "rag",
                success ? "success" : "failure");
        recordTimer("ai.rag.search.duration", durationMillis, tags);
        recordAmount("ai.evidence.count", count, tags);
    }

    public void recordRagIndex(String operation, int indexed, int skipped, int failed, long durationMillis) {
        Tags base = tags("rag_index", null, null, operation, failed > 0, null, "rag",
                failed > 0 ? "partial_or_failure" : "success");
        recordTimer("ai.rag.index.duration", durationMillis, base);
        recordAmount("ai.rag.index.count", indexed, base.and("status", "indexed"));
        recordAmount("ai.rag.index.count", skipped, base.and("status", "skipped"));
        recordAmount("ai.rag.index.count", failed, base.and("status", "failed"));
    }

    public Map<String, AtomicLong> snapshot() {
        return counters;
    }

    public void recordAgentRunStarted(String agentId) {
        increment("agent_run_total", Tags.of("agentId", safe(agentId), "status", "started"));
    }

    public void recordAgentRunCompleted(String agentId, long durationMillis) {
        increment("agent_run_success_total", Tags.of("agentId", safe(agentId)));
        recordTimer("agent_run_duration", durationMillis, Tags.of("agentId", safe(agentId), "status", "success"));
    }

    public void recordAgentRunFailed(String agentId, String errorCode, long durationMillis) {
        increment("agent_run_failure_total", Tags.of("agentId", safe(agentId), "errorCode", safe(errorCode)));
        recordTimer("agent_run_duration", durationMillis, Tags.of("agentId", safe(agentId), "status", "failure"));
    }

    private void increment(String metric, Tags tags) {
        String key = key(metric, tags);
        long value = counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        if (meterRegistry != null) {
            Counter.builder(metric).tags(tags).register(meterRegistry).increment();
        }
        log.debug("AI metric {} -> {}", key, value);
    }

    private void recordAmount(String metric, long amount, Tags tags) {
        if (amount <= 0) {
            return;
        }
        String key = key(metric, tags);
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(amount);
        if (meterRegistry != null) {
            Counter.builder(metric).tags(tags).register(meterRegistry).increment(amount);
        }
    }

    private void recordTimer(String metric, long durationMillis, Tags tags) {
        if (durationMillis < 0) {
            durationMillis = 0;
        }
        if (meterRegistry != null) {
            Timer.builder(metric).tags(tags).register(meterRegistry)
                    .record(Duration.ofMillis(durationMillis));
        }
    }

    private Tags tags(String analysisType,
                      String intent,
                      String modelName,
                      String operation,
                      boolean degraded,
                      Boolean cacheHit,
                      String source,
                      String result) {
        return Tags.of(
                "analysisType", safe(analysisType),
                "intent", safe(intent),
                "modelName", safe(modelName),
                "operation", safe(operation),
                "degraded", String.valueOf(degraded),
                "cacheHit", cacheHit == null ? "unknown" : String.valueOf(cacheHit),
                "source", safe(source),
                "result", safe(result)
        );
    }

    private String key(String metric, Tags tags) {
        StringBuilder builder = new StringBuilder(metric).append(":");
        boolean first = true;
        for (io.micrometer.core.instrument.Tag tag : tags) {
            if (!first) {
                builder.append(",");
            }
            builder.append(tag.getKey()).append("=").append(tag.getValue());
            first = false;
        }
        return builder.toString();
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }
}

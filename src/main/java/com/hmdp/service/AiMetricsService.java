package com.hmdp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class AiMetricsService {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void increment(String metric, String analysisType, boolean degraded) {
        String key = metric + ":" + analysisType + ":degraded=" + degraded;
        long value = counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        log.debug("AI metric {} -> {}", key, value);
    }

    public void recordDuration(String analysisType, long durationMillis, boolean degraded) {
        increment("ai.request.count", analysisType, degraded);
        log.debug("AI request duration analysisType={}, degraded={}, durationMs={}",
                analysisType, degraded, durationMillis);
    }

    public Map<String, AtomicLong> snapshot() {
        return counters;
    }
}

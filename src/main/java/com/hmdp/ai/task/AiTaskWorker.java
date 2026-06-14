package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class AiTaskWorker {

    @Resource
    private AiTaskQueue queue;

    @Resource
    private AiTaskRepository repository;

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @Resource
    private AiMetricsService aiMetricsService;

    @Value("${hmdp.ai.task.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.ai.task.worker-threads:2}")
    private int workerThreads;

    private volatile boolean running;
    private ExecutorService executorService;

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("AI task worker disabled");
            return;
        }
        int threads = Math.max(1, workerThreads);
        running = true;
        executorService = Executors.newFixedThreadPool(threads, daemonThreadFactory());
        for (int i = 0; i < threads; i++) {
            executorService.submit(this::consumeLoop);
        }
        log.info("AI task worker started, threads={}", threads);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    void process(String taskId) {
        AiTask task = repository.find(taskId).orElse(null);
        if (task == null) {
            return;
        }
        long start = System.currentTimeMillis();
        boolean failed = false;
        try {
            task.setStatus(AiTaskStatus.RUNNING);
            task.setErrorMessage(null);
            repository.update(task);

            ShopRagRebuildResult result = dispatch(task);
            task.setResult(result);
            task.setStatus(AiTaskStatus.SUCCESS);
        } catch (RuntimeException e) {
            failed = true;
            task.setStatus(AiTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            log.warn("AI task failed, taskId={}", taskId, e);
        } finally {
            repository.update(task);
            repository.clearInflight(task.getDedupKey());
            recordMetrics(System.currentTimeMillis() - start, failed);
        }
    }

    private void consumeLoop() {
        while (running) {
            try {
                process(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("AI task consume loop failed", e);
            }
        }
    }

    private ShopRagRebuildResult dispatch(AiTask task) {
        Map<String, Object> params = task.getParams();
        if (task.getType() == AiTaskType.RAG_REBUILD_ALL) {
            return shopReviewVectorIndexService.rebuildAll(
                    integerParam(params, "shopLimit"),
                    integerParam(params, "perShopLimit"));
        }
        if (task.getType() == AiTaskType.RAG_REBUILD_SHOP) {
            return shopReviewVectorIndexService.rebuildShop(
                    longParam(params, "shopId"),
                    integerParam(params, "limit"));
        }
        throw new IllegalArgumentException("Unsupported AI task type: " + task.getType());
    }

    private Integer integerParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void recordMetrics(long durationMillis, boolean failed) {
        if (aiMetricsService != null) {
            aiMetricsService.recordDuration("ai_task", durationMillis, failed);
            aiMetricsService.increment("ai.task.count", "ai_task", failed);
        }
    }

    private ThreadFactory daemonThreadFactory() {
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "hmdp-ai-task-worker-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}

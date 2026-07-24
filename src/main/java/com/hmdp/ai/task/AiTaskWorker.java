package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class AiTaskWorker {

    private static final long EXECUTION_LOCK_WAIT_SECONDS = 1L;

    @Resource
    private AiTaskQueue queue;

    @Resource
    private AiTaskRepository repository;

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private AiTaskEventPublisher eventPublisher;

    private final EnumMap<AiTaskType, AiTaskHandler> handlers;

    @Value("${hmdp.ai.task.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.ai.task.worker-threads:2}")
    private int workerThreads;

    private volatile boolean running;
    private ExecutorService executorService;

    public AiTaskWorker(List<AiTaskHandler> handlers) {
        this.handlers = new EnumMap<>(AiTaskType.class);
        if (handlers != null) {
            for (AiTaskHandler handler : handlers) {
                if (handler == null || handler.type() == null) {
                    continue;
                }
                AiTaskHandler previous = this.handlers.putIfAbsent(handler.type(), handler);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate AI task handler type: " + handler.type());
                }
            }
        }
    }

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
        RLock lock;
        try {
            lock = repository.executionLock(taskId);
        } catch (RuntimeException e) {
            requeueTask(taskId);
            throw e;
        }
        boolean locked;
        try {
            locked = lock.tryLock(EXECUTION_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            requeueTask(taskId);
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            requeueTask(taskId);
            throw e;
        }
        if (!locked) {
            requeueTask(taskId);
            return;
        }
        try {
            AiTask task;
            try {
                task = repository.find(taskId).orElse(null);
            } catch (RuntimeException e) {
                requeueTask(taskId);
                throw e;
            }
            if (task == null || task.getStatus() != AiTaskStatus.PENDING) {
                return;
            }
            executeTask(taskId, task);
        } finally {
            unlockQuietly(lock);
        }
    }

    private void executeTask(String taskId, AiTask task) {
        long start = System.currentTimeMillis();
        boolean failed = false;
        try {
            long now = System.currentTimeMillis();
            task.setStatus(AiTaskStatus.RUNNING);
            task.setErrorMessage(null);
            task.setStartedAtEpochMillis(now);
            task.setHeartbeatAtEpochMillis(now);
            task.setFinishedAtEpochMillis(null);
            repository.update(task);
            publishEvent(task);

            Object result = dispatch(task);
            task.setResult(result);
            task.setStatus(AiTaskStatus.SUCCESS);
            task.setFinishedAtEpochMillis(System.currentTimeMillis());
        } catch (Exception e) {
            failed = true;
            task.setStatus(AiTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setFinishedAtEpochMillis(System.currentTimeMillis());
            log.warn("AI task failed, taskId={}", taskId, e);
        } finally {
            repository.update(task);
            // Keep the inflight marker while a non-Exception failure leaves the task RUNNING.
            // The recovery scanner must be the only path that decides whether to retry or fail it.
            if (terminal(task.getStatus())) {
                repository.clearInflight(task.getDedupKey(), task.getTaskId());
            }
            publishEvent(task);
            recordMetrics(System.currentTimeMillis() - start, failed);
        }
    }

    private void requeueTask(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return;
        }
        try {
            queue.enqueue(taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Requeue AI task interrupted, taskId={}", taskId, e);
        } catch (RuntimeException e) {
            log.warn("Requeue AI task failed, taskId={}", taskId, e);
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("Release AI task execution lock failed", e);
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

    private Object dispatch(AiTask task) throws Exception {
        AiTaskHandler handler = handlers.get(task.getType());
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported AI task type: " + task.getType());
        }
        return handler.handle(task, (current, total) -> {
            task.setProgressCurrent(current);
            task.setProgressTotal(total);
            task.setHeartbeatAtEpochMillis(System.currentTimeMillis());
            repository.update(task);
            publishEvent(task);
        });
    }

    private void recordMetrics(long durationMillis, boolean failed) {
        if (aiMetricsService == null) {
            return;
        }
        try {
            aiMetricsService.recordDuration("ai_task", durationMillis, failed);
        } catch (RuntimeException e) {
            log.warn("Record AI task duration metric failed", e);
        }
        try {
            aiMetricsService.increment("ai.task.count", "ai_task", failed);
        } catch (RuntimeException e) {
            log.warn("Record AI task count metric failed", e);
        }
    }

    private void publishEvent(AiTask task) {
        if (eventPublisher == null || task == null) {
            return;
        }
        try {
            eventPublisher.publish(AiTaskEvent.builder()
                    .taskId(task.getTaskId())
                    .status(task.getStatus())
                    .progressCurrent(task.getProgressCurrent())
                    .progressTotal(task.getProgressTotal())
                    .errorMessage(task.getErrorMessage())
                    .timestampEpochMillis(System.currentTimeMillis())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Publish AI task event failed, taskId={}", task.getTaskId(), e);
        }
    }

    private boolean terminal(AiTaskStatus status) {
        return status == AiTaskStatus.SUCCESS
                || status == AiTaskStatus.FAILED
                || status == AiTaskStatus.CANCELLED;
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

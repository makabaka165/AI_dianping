package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 1 仅接入管理端 RAG 重建任务。未来新增用户级任务类型时，再在这里接入 quota 与单用户在途上限。
 */
@Service
@Slf4j
public class AiTaskService {

    @Resource
    private AiTaskRepository repository;

    @Resource
    private AiTaskQueue queue;

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private AiTaskEventPublisher eventPublisher;

    @Value("${hmdp.ai.task.running-timeout-minutes:30}")
    private long runningTimeoutMinutes = 30L;

    @Value("${hmdp.ai.task.max-retry-count:3}")
    private int maxRetryCount = 3;

    @Value("${hmdp.ai.task.stuck-scan-enabled:true}")
    private boolean stuckScanEnabled = true;

    @Value("${hmdp.ai.task.stuck-scan-limit:100}")
    private int stuckScanLimit = 100;

    public String submit(AiTaskType type, Map<String, Object> params, String ownerUserId) {
        String dedupKey = dedupKey(type, params, ownerUserId);
        String taskId = UUID.randomUUID().toString().replace("-", "");
        Optional<String> existingTaskId = repository.tryRegisterInflight(dedupKey, taskId);
        if (existingTaskId.isPresent()) {
            recordIncrement("ai.task.dedup", false);
            return existingTaskId.get();
        }

        long now = System.currentTimeMillis();
        AiTask task = AiTask.builder()
                .taskId(taskId)
                .type(type)
                .status(AiTaskStatus.PENDING)
                .ownerUserId(ownerUserId)
                .dedupKey(dedupKey)
                .params(params)
                .retryCount(0)
                .createdAtEpochMillis(now)
                .updatedAtEpochMillis(now)
                .build();
        try {
            repository.save(task);
            queue.enqueue(taskId);
            recordIncrement("ai.task.submitted", false);
            return taskId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.clearInflight(dedupKey);
            throw new IllegalStateException("Submit AI task interrupted", e);
        } catch (RuntimeException e) {
            repository.clearInflight(dedupKey);
            throw e;
        }
    }

    public Optional<AiTask> get(String taskId) {
        return repository.find(taskId);
    }

    @Scheduled(fixedDelayString = "${hmdp.ai.task.stuck-scan-fixed-delay-millis:60000}",
            initialDelayString = "${hmdp.ai.task.stuck-scan-fixed-delay-millis:60000}")
    public void recoverStuckRunningTasks() {
        if (!stuckScanEnabled) {
            return;
        }
        recoverStuckRunningTasks(System.currentTimeMillis());
    }

    int recoverStuckRunningTasks(long nowMillis) {
        List<AiTask> runningTasks = repository.findByStatus(AiTaskStatus.RUNNING, effectiveStuckScanLimit());
        int recovered = 0;
        for (AiTask task : runningTasks) {
            if (task == null || !isHeartbeatTimeout(task, nowMillis)) {
                continue;
            }
            recoverOneTask(task, nowMillis);
            recovered++;
        }
        return recovered;
    }

    private void recoverOneTask(AiTask task, long nowMillis) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        repository.clearInflight(task.getDedupKey());
        if (retryCount < effectiveMaxRetryCount()) {
            task.setRetryCount(retryCount + 1);
            task.setStatus(AiTaskStatus.PENDING);
            task.setErrorMessage("task heartbeat timeout, requeued");
            task.setStartedAtEpochMillis(null);
            task.setHeartbeatAtEpochMillis(null);
            task.setFinishedAtEpochMillis(null);
            repository.update(task);
            try {
                queue.enqueue(task.getTaskId());
                publishEvent(task);
                recordIncrement("ai.task.requeued", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.setStatus(AiTaskStatus.FAILED);
                task.setFinishedAtEpochMillis(nowMillis);
                task.setErrorMessage("task heartbeat timeout, requeue interrupted");
                repository.update(task);
                publishEvent(task);
                recordIncrement("ai.task.requeue.failed", true);
            }
            return;
        }
        task.setStatus(AiTaskStatus.FAILED);
        task.setFinishedAtEpochMillis(nowMillis);
        task.setErrorMessage("task heartbeat timeout, max retry exceeded");
        repository.update(task);
        publishEvent(task);
        recordIncrement("ai.task.timeout.failed", true);
    }

    private boolean isHeartbeatTimeout(AiTask task, long nowMillis) {
        long heartbeatAt = task.getHeartbeatAtEpochMillis() == null
                ? task.getStartedAtEpochMillis() == null ? task.getUpdatedAtEpochMillis() : task.getStartedAtEpochMillis()
                : task.getHeartbeatAtEpochMillis();
        long timeoutMillis = Math.max(1L, runningTimeoutMinutes) * 60_000L;
        return heartbeatAt > 0 && nowMillis - heartbeatAt >= timeoutMillis;
    }

    static String dedupKey(AiTaskType type, Map<String, Object> params, String ownerUserId) {
        String normalizedParams = normalizeParams(params);
        return String.valueOf(type) + ":" + normalizedParams + ":" + safe(ownerUserId);
    }

    private static String normalizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        return new TreeMap<>(params).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String safe(String value) {
        return value == null ? "anonymous" : value;
    }

    private void recordIncrement(String metric, boolean failed) {
        if (aiMetricsService != null) {
            aiMetricsService.increment(metric, "ai_task", failed);
        }
    }

    private int effectiveStuckScanLimit() {
        return stuckScanLimit <= 0 ? 100 : stuckScanLimit;
    }

    private int effectiveMaxRetryCount() {
        return Math.max(0, maxRetryCount);
    }

    private void publishEvent(AiTask task) {
        if (eventPublisher != null && task != null) {
            eventPublisher.publish(AiTaskEvent.builder()
                    .taskId(task.getTaskId())
                    .status(task.getStatus())
                    .progressCurrent(task.getProgressCurrent())
                    .progressTotal(task.getProgressTotal())
                    .errorMessage(task.getErrorMessage())
                    .timestampEpochMillis(System.currentTimeMillis())
                    .build());
        }
    }
}

package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
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

    @Value("${hmdp.ai.task.pending-timeout-minutes:30}")
    private long pendingTimeoutMinutes = 30L;

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
        boolean saved = false;
        try {
            repository.save(task);
            saved = true;
            queue.enqueue(taskId);
            recordIncrement("ai.task.submitted", false);
            return taskId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markSubmissionFailed(task, saved, "AI task enqueue interrupted");
            clearInflightQuietly(dedupKey, taskId);
            throw new IllegalStateException("Submit AI task interrupted", e);
        } catch (RuntimeException e) {
            markSubmissionFailed(task, saved, "AI task enqueue failed");
            clearInflightQuietly(dedupKey, taskId);
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
        long nowMillis = System.currentTimeMillis();
        try {
            recoverStuckPendingTasks(nowMillis);
        } catch (RuntimeException e) {
            log.warn("Recover stuck PENDING AI tasks failed", e);
        }
        try {
            recoverStuckRunningTasks(nowMillis);
        } catch (RuntimeException e) {
            log.warn("Recover stuck RUNNING AI tasks failed", e);
        }
    }

    int recoverStuckPendingTasks(long nowMillis) {
        List<AiTask> pendingTasks = repository.findByStatus(AiTaskStatus.PENDING, effectiveStuckScanLimit());
        int recovered = 0;
        for (AiTask task : pendingTasks) {
            if (task == null || !isPendingTimeout(task, nowMillis)) {
                continue;
            }
            if (recoverOnePendingTask(task, nowMillis)) {
                recovered++;
            }
        }
        return recovered;
    }

    int recoverStuckRunningTasks(long nowMillis) {
        List<AiTask> runningTasks = repository.findByStatus(AiTaskStatus.RUNNING, effectiveStuckScanLimit());
        int recovered = 0;
        for (AiTask task : runningTasks) {
            if (task == null || !isHeartbeatTimeout(task, nowMillis)) {
                continue;
            }
            if (recoverOneTask(task, nowMillis)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean recoverOneTask(AiTask candidate, long nowMillis) {
        RLock lock = repository.executionLock(candidate.getTaskId());
        if (!lock.tryLock()) {
            return false;
        }
        try {
            AiTask task = repository.find(candidate.getTaskId()).orElse(null);
            if (task == null || task.getStatus() != AiTaskStatus.RUNNING || !isHeartbeatTimeout(task, nowMillis)) {
                return false;
            }
            recoverLockedTask(task, nowMillis);
            return true;
        } catch (RuntimeException e) {
            log.warn("Recover AI task failed, taskId={}", candidate.getTaskId(), e);
            return false;
        } finally {
            unlockQuietly(lock);
        }
    }

    private boolean recoverOnePendingTask(AiTask candidate, long nowMillis) {
        RLock lock = repository.executionLock(candidate.getTaskId());
        if (!lock.tryLock()) {
            return false;
        }
        try {
            AiTask task = repository.find(candidate.getTaskId()).orElse(null);
            if (task == null || task.getStatus() != AiTaskStatus.PENDING || !isPendingTimeout(task, nowMillis)) {
                return false;
            }
            task.setErrorMessage("task pending timeout, requeued");
            repository.update(task);
            try {
                queue.enqueue(task.getTaskId());
                publishEvent(task);
                recordIncrement("ai.task.pending.requeued", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failRequeue(task, nowMillis, "task pending timeout, requeue interrupted");
            } catch (RuntimeException e) {
                log.warn("Requeue pending AI task failed, taskId={}", task.getTaskId(), e);
                failRequeue(task, nowMillis, "task pending timeout, requeue failed");
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Recover pending AI task failed, taskId={}", candidate.getTaskId(), e);
            return false;
        } finally {
            unlockQuietly(lock);
        }
    }

    private void recoverLockedTask(AiTask task, long nowMillis) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
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
                failRequeue(task, nowMillis, "task heartbeat timeout, requeue interrupted");
            } catch (RuntimeException e) {
                log.warn("Requeue timed-out AI task failed, taskId={}", task.getTaskId(), e);
                failRequeue(task, nowMillis, "task heartbeat timeout, requeue failed");
            }
            return;
        }
        task.setStatus(AiTaskStatus.FAILED);
        task.setFinishedAtEpochMillis(nowMillis);
        task.setErrorMessage("task heartbeat timeout, max retry exceeded");
        repository.update(task);
        clearInflightQuietly(task.getDedupKey(), task.getTaskId());
        publishEvent(task);
        recordIncrement("ai.task.timeout.failed", true);
    }

    private void failRequeue(AiTask task, long nowMillis, String message) {
        task.setStatus(AiTaskStatus.FAILED);
        task.setFinishedAtEpochMillis(nowMillis);
        task.setErrorMessage(message);
        repository.update(task);
        clearInflightQuietly(task.getDedupKey(), task.getTaskId());
        publishEvent(task);
        recordIncrement("ai.task.requeue.failed", true);
    }

    private void markSubmissionFailed(AiTask task, boolean saved, String message) {
        if (!saved || task == null) {
            return;
        }
        task.setStatus(AiTaskStatus.FAILED);
        task.setErrorMessage(message);
        task.setFinishedAtEpochMillis(System.currentTimeMillis());
        try {
            repository.update(task);
            publishEvent(task);
            recordIncrement("ai.task.submit.failed", true);
        } catch (RuntimeException e) {
            log.warn("Persist failed AI task submission state failed, taskId={}", task.getTaskId(), e);
        }
    }

    private void clearInflightQuietly(String dedupKey, String taskId) {
        try {
            repository.clearInflight(dedupKey, taskId);
        } catch (RuntimeException e) {
            log.warn("Clear failed AI task inflight key failed", e);
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("Release AI task recovery lock failed", e);
        }
    }

    private boolean isHeartbeatTimeout(AiTask task, long nowMillis) {
        long heartbeatAt = task.getHeartbeatAtEpochMillis() == null
                ? task.getStartedAtEpochMillis() == null ? task.getUpdatedAtEpochMillis() : task.getStartedAtEpochMillis()
                : task.getHeartbeatAtEpochMillis();
        long timeoutMillis = Math.max(1L, runningTimeoutMinutes) * 60_000L;
        return heartbeatAt > 0 && nowMillis - heartbeatAt >= timeoutMillis;
    }

    private boolean isPendingTimeout(AiTask task, long nowMillis) {
        Long updatedAt = task.getUpdatedAtEpochMillis();
        Long createdAt = task.getCreatedAtEpochMillis();
        long pendingAt = updatedAt == null ? createdAt == null ? 0L : createdAt : updatedAt;
        long timeoutMillis = Math.max(1L, pendingTimeoutMinutes) * 60_000L;
        return pendingAt > 0 && nowMillis - pendingAt >= timeoutMillis;
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
        if (aiMetricsService == null) {
            return;
        }
        try {
            aiMetricsService.increment(metric, "ai_task", failed);
        } catch (RuntimeException e) {
            log.warn("Record AI task metric failed, metric={}", metric, e);
        }
    }

    private int effectiveStuckScanLimit() {
        return stuckScanLimit <= 0 ? 100 : stuckScanLimit;
    }

    private int effectiveMaxRetryCount() {
        return Math.max(0, maxRetryCount);
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
}

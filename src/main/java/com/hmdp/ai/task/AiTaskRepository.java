package com.hmdp.ai.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@Slf4j
public class AiTaskRepository {

    private static final int MAX_INFLIGHT_REGISTRATION_ATTEMPTS = 3;

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    @Value("${hmdp.ai.task.result-ttl-hours:24}")
    private long resultTtlHours;

    @Value("${hmdp.ai.task.bucket-prefix:hmdp:ai:task:}")
    private String bucketPrefix;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void save(AiTask task) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        try {
            Optional<AiTask> oldTask = find(task.getTaskId());
            persistTaskAndStatusIndex(
                    objectMapper.writeValueAsString(task),
                    oldTask.map(AiTask::getStatus).orElse(null),
                    task.getStatus(),
                    task.getTaskId());
        } catch (Exception e) {
            throw new IllegalStateException("Save AI task failed", e);
        }
    }

    public Optional<AiTask> find(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return Optional.empty();
        }
        String json;
        try {
            RBucket<String> bucket = redissonClient.getBucket(taskKey(taskId));
            json = bucket.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Read AI task failed", e);
        }
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, AiTask.class));
        } catch (JsonProcessingException e) {
            log.error("AI task data is corrupt, taskId={}, error={}",
                    AiLogSanitizer.safe(taskId, 64), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public RLock executionLock(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return redissonClient.getLock(bucketPrefix + "lock:" + taskId);
    }

    public void update(AiTask task) {
        if (task == null) {
            return;
        }
        task.setUpdatedAtEpochMillis(System.currentTimeMillis());
        save(task);
    }

    public Optional<String> tryRegisterInflight(String dedupKey, String taskId) {
        if (dedupKey == null || dedupKey.trim().isEmpty() || taskId == null || taskId.trim().isEmpty()) {
            return Optional.empty();
        }
        RBucket<String> bucket = redissonClient.getBucket(inflightKey(dedupKey));
        for (int attempt = 0; attempt < MAX_INFLIGHT_REGISTRATION_ATTEMPTS; attempt++) {
            if (bucket.trySet(taskId, effectiveResultTtlHours(), TimeUnit.HOURS)) {
                return Optional.empty();
            }
            String existingTaskId = bucket.get();
            if (existingTaskId != null && !existingTaskId.trim().isEmpty()) {
                return Optional.of(existingTaskId);
            }
        }
        throw new IllegalStateException("Register AI task inflight marker failed due to concurrent updates");
    }

    public boolean clearInflight(String dedupKey, String taskId) {
        if (dedupKey == null || dedupKey.trim().isEmpty()
                || taskId == null || taskId.trim().isEmpty()) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(inflightKey(dedupKey));
        return bucket.compareAndSet(taskId, null);
    }

    public List<AiTask> findByStatus(AiTaskStatus status, int limit) {
        if (status == null || limit <= 0) {
            return List.of();
        }
        RSet<String> index = redissonClient.getSet(statusIndexKey(status));
        List<AiTask> tasks = new ArrayList<>();
        List<String> staleIds = new ArrayList<>();
        for (String taskId : index) {
            Optional<AiTask> task = find(taskId);
            if (task.isEmpty() || task.get().getStatus() != status) {
                staleIds.add(taskId);
                continue;
            }
            tasks.add(task.get());
            if (tasks.size() >= limit) {
                break;
            }
        }
        staleIds.forEach(index::remove);
        return tasks;
    }

    private String taskKey(String taskId) {
        return bucketPrefix + taskId;
    }

    private String inflightKey(String dedupKey) {
        return bucketPrefix + "inflight:" + dedupKey;
    }

    private String statusIndexKey(AiTaskStatus status) {
        return bucketPrefix + "index:status:" + status.name();
    }

    private void persistTaskAndStatusIndex(
            String taskJson, AiTaskStatus oldStatus, AiTaskStatus newStatus, String taskId) {
        BatchOptions options = BatchOptions.defaults()
                .executionMode(BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC);
        RBatch batch = redissonClient.createBatch(options);
        batch.<String>getBucket(taskKey(taskId))
                .setAsync(taskJson, effectiveResultTtlHours(), TimeUnit.HOURS);
        if (oldStatus != null && oldStatus != newStatus) {
            batch.<String>getSet(statusIndexKey(oldStatus)).removeAsync(taskId);
        }
        if (newStatus == AiTaskStatus.PENDING || newStatus == AiTaskStatus.RUNNING) {
            batch.<String>getSet(statusIndexKey(newStatus)).addAsync(taskId);
        }
        batch.execute();
    }

    private long effectiveResultTtlHours() {
        return Math.max(1L, resultTtlHours);
    }
}

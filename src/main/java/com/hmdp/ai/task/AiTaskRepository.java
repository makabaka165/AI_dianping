package com.hmdp.ai.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import org.redisson.api.RBucket;
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
public class AiTaskRepository {

    @Resource
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
            redissonClient.getBucket(taskKey(task.getTaskId()))
                    .set(objectMapper.writeValueAsString(task), resultTtlHours, TimeUnit.HOURS);
            updateStatusIndex(oldTask.map(AiTask::getStatus).orElse(null), task.getStatus(), task.getTaskId());
        } catch (Exception e) {
            throw new IllegalStateException("Save AI task failed", e);
        }
    }

    public Optional<AiTask> find(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(taskKey(taskId));
            String json = bucket.get();
            if (json == null || json.trim().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AiTask.class));
        } catch (Exception e) {
            return Optional.empty();
        }
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
        boolean registered = bucket.trySet(taskId, resultTtlHours, TimeUnit.HOURS);
        return registered ? Optional.empty() : Optional.ofNullable(bucket.get());
    }

    public void clearInflight(String dedupKey) {
        if (dedupKey == null || dedupKey.trim().isEmpty()) {
            return;
        }
        redissonClient.getBucket(inflightKey(dedupKey)).delete();
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

    private void updateStatusIndex(AiTaskStatus oldStatus, AiTaskStatus newStatus, String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return;
        }
        if (oldStatus != null && oldStatus != newStatus) {
            redissonClient.getSet(statusIndexKey(oldStatus)).remove(taskId);
        }
        if (newStatus != null) {
            redissonClient.getSet(statusIndexKey(newStatus)).add(taskId);
        }
    }
}

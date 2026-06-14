package com.hmdp.ai.task;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
}

package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.task.AiTaskService;
import com.hmdp.ai.task.AiTaskStreamService;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.service.CurrentUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/shop-summary/admin/ai/tasks")
@Slf4j
public class AiTaskAdminController {

    @Resource
    private AiTaskService aiTaskService;

    @Resource
    private AiTaskStreamService aiTaskStreamService;

    @Resource
    private CurrentUserService currentUserService;

    @PostMapping("/batch-summary")
    @SaCheckPermission("ai:rag:manage")
    public Result submitBatchSummaryTask(@RequestParam(value = "shopLimit", required = false) Integer shopLimit) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("shopLimit", shopLimit);
            String taskId = aiTaskService.submit(AiTaskType.BATCH_SHOP_SUMMARY, params, ownerUserId());
            return Result.ok(taskIdData(taskId));
        } catch (RuntimeException e) {
            log.error("Submit batch shop summary task failed", e);
            return Result.fail("提交批量店铺总结任务失败");
        }
    }

    @GetMapping("/{taskId}")
    @SaCheckPermission("ai:rag:manage")
    public Result getTask(@PathVariable String taskId) {
        Optional<AiTask> task = aiTaskService.get(taskId);
        return task.map(this::publicTask)
                .map(Result::ok)
                .orElseGet(() -> Result.fail("任务不存在"));
    }

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckPermission("ai:rag:manage")
    public Flux<ServerSentEvent<AiTaskEvent>> streamTask(@PathVariable String taskId) {
        if (aiTaskService.get(taskId).isEmpty()) {
            return Flux.just(toSse("error", AiTaskEvent.builder()
                    .taskId(taskId)
                    .status(AiTaskStatus.FAILED)
                    .errorMessage("任务不存在")
                    .timestampEpochMillis(System.currentTimeMillis())
                    .build()));
        }
        return aiTaskStreamService.stream(taskId)
                .map(event -> toSse(event.getStatus().name(), event));
    }

    private String ownerUserId() {
        Long userId = currentUserService == null ? null : currentUserService.getCurrentUserId();
        return userId == null ? null : String.valueOf(userId);
    }

    private Map<String, Object> taskIdData(String taskId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        return data;
    }

    private AiTask publicTask(AiTask task) {
        return AiTask.builder()
                .taskId(task.getTaskId())
                .type(task.getType())
                .status(task.getStatus())
                .ownerUserId(task.getOwnerUserId())
                .params(task.getParams())
                .progressCurrent(task.getProgressCurrent())
                .progressTotal(task.getProgressTotal())
                .result(task.getResult())
                .errorMessage(task.getErrorMessage())
                .retryCount(task.getRetryCount())
                .startedAtEpochMillis(task.getStartedAtEpochMillis())
                .heartbeatAtEpochMillis(task.getHeartbeatAtEpochMillis())
                .finishedAtEpochMillis(task.getFinishedAtEpochMillis())
                .createdAtEpochMillis(task.getCreatedAtEpochMillis())
                .updatedAtEpochMillis(task.getUpdatedAtEpochMillis())
                .build();
    }

    private ServerSentEvent<AiTaskEvent> toSse(String eventName, AiTaskEvent event) {
        return ServerSentEvent.<AiTaskEvent>builder()
                .event(eventName)
                .data(event)
                .build();
    }
}

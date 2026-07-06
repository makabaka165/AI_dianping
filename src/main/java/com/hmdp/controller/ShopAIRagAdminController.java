package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.task.AiTaskService;
import com.hmdp.ai.task.AiTaskStreamService;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.ai.application.ShopAICacheInvalidationService;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.ShopStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/shop-summary/admin/rag")
@Slf4j
public class ShopAIRagAdminController {

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @Resource
    private ShopAICacheInvalidationService shopAICacheInvalidationService;

    @Resource
    private ShopStatsService shopStatsService;

    @Resource
    private AiTaskService aiTaskService;

    @Resource
    private AiTaskStreamService aiTaskStreamService;

    @Resource
    private CurrentUserService currentUserService;

    @PostMapping("/shops/{shopId}/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result rebuildShop(@PathVariable Long shopId,
                              @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            ShopRagRebuildResult result = shopReviewVectorIndexService.rebuildShop(shopId, limit);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.error("重建店铺评价 RAG 索引失败, shopId={}", shopId, e);
            return Result.fail("重建店铺评价 RAG 索引失败");
        }
    }

    @PostMapping("/shops/{shopId}/compact")
    @SaCheckPermission("ai:rag:manage")
    public Result compactShop(@PathVariable Long shopId,
                              @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            if (shopAICacheInvalidationService != null) {
                shopAICacheInvalidationService.clearShopRelatedCaches(shopId);
            }
            if (shopStatsService != null) {
                shopStatsService.evictShopStatsCache(shopId);
            }
            ShopRagRebuildResult result = shopReviewVectorIndexService.compactShop(shopId, limit);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.error("压缩店铺评价 RAG 索引失败, shopId={}", shopId, e);
            return Result.ok(ShopRagRebuildResult.empty(shopId, 0,
                    "RAG review compact unavailable: " + e.getMessage()));
        }
    }

    @PostMapping("/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result rebuildAll(@RequestParam(value = "shopLimit", required = false) Integer shopLimit,
                             @RequestParam(value = "perShopLimit", required = false) Integer perShopLimit) {
        try {
            ShopRagRebuildResult result = shopReviewVectorIndexService.rebuildAll(shopLimit, perShopLimit);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.error("全量重建评价 RAG 索引失败", e);
            return Result.fail("全量重建评价 RAG 索引失败");
        }
    }

    @PostMapping("/tasks/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result submitRebuildAllTask(@RequestParam(value = "shopLimit", required = false) Integer shopLimit,
                                       @RequestParam(value = "perShopLimit", required = false) Integer perShopLimit) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("shopLimit", shopLimit);
            params.put("perShopLimit", perShopLimit);
            String taskId = aiTaskService.submit(AiTaskType.RAG_REBUILD_ALL, params, ownerUserId());
            return Result.ok(taskIdData(taskId));
        } catch (RuntimeException e) {
            log.error("Submit full RAG rebuild task failed", e);
            return Result.fail("提交全量 RAG 重建任务失败");
        }
    }

    @PostMapping("/tasks/shops/{shopId}/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result submitRebuildShopTask(@PathVariable Long shopId,
                                        @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("shopId", shopId);
            params.put("limit", limit);
            String taskId = aiTaskService.submit(AiTaskType.RAG_REBUILD_SHOP, params, ownerUserId());
            return Result.ok(taskIdData(taskId));
        } catch (RuntimeException e) {
            log.error("Submit shop RAG rebuild task failed, shopId={}", shopId, e);
            return Result.fail("提交店铺 RAG 重建任务失败");
        }
    }

    @GetMapping("/tasks/{taskId}")
    @SaCheckPermission("ai:rag:manage")
    public Result getTask(@PathVariable String taskId) {
        Optional<AiTask> task = aiTaskService.get(taskId);
        return task.map(this::publicTask)
                .map(Result::ok)
                .orElseGet(() -> Result.fail("任务不存在"));
    }

    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
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

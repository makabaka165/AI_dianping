package com.hmdp.ai.task.handler;

import com.hmdp.ai.orchestration.ShopAIOrchestrator;
import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.ai.task.AiTaskHandler;
import com.hmdp.ai.task.AiTaskParams;
import com.hmdp.ai.task.AiTaskProgressReporter;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskType;
import com.hmdp.dto.ai.BatchSummaryResult;
import com.hmdp.dto.ai.ShopAIRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class BatchShopSummaryHandler implements AiTaskHandler {

    @Resource
    private ReviewDataPort reviewDataPort;

    @Resource
    private ShopAIOrchestrator orchestrator;

    @Value("${hmdp.ai.task.batch-summary.default-shop-limit:200}")
    private int defaultShopLimit;

    @Override
    public AiTaskType type() {
        return AiTaskType.BATCH_SHOP_SUMMARY;
    }

    @Override
    public Object handle(AiTask task, AiTaskProgressReporter reporter) {
        long start = System.currentTimeMillis();
        int shopLimit = AiTaskParams.integerParam(task, "shopLimit", defaultShopLimit);
        List<Long> shopIds = reviewDataPort.findActiveShopIdsForRag(shopLimit);
        int total = shopIds == null ? 0 : shopIds.size();
        List<Long> failedShopIds = new ArrayList<>();
        int success = 0;
        int failed = 0;
        reporter.report(0, total);
        if (shopIds != null) {
            for (int i = 0; i < shopIds.size(); i++) {
                Long shopId = shopIds.get(i);
                try {
                    orchestrator.summary(context(shopId), SummaryWorkflowRequest.builder()
                            .shopId(shopId)
                            .writeMemory(false)
                            .build());
                    success++;
                } catch (RuntimeException e) {
                    failed++;
                    failedShopIds.add(shopId);
                    log.warn("Batch shop summary failed, shopId={}", shopId, e);
                }
                reporter.report(i + 1, total);
            }
        }
        return BatchSummaryResult.builder()
                .total(total)
                .success(success)
                .failed(failed)
                .failedShopIds(failedShopIds)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ShopAIRequestContext context(Long shopId) {
        return ShopAIRequestContext.builder()
                .userId("system-batch")
                .sessionId("batch_summary_" + shopId)
                .traceId(UUID.randomUUID().toString().replace("-", ""))
                .sourceEndpoint("ai-task:batch-summary")
                .build();
    }
}

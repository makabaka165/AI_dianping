package com.hmdp.ai.task.handler;

import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.ai.task.AiTaskHandler;
import com.hmdp.ai.task.AiTaskParams;
import com.hmdp.ai.task.AiTaskProgressReporter;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskType;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RagRebuildShopHandler implements AiTaskHandler {

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @Override
    public AiTaskType type() {
        return AiTaskType.RAG_REBUILD_SHOP;
    }

    @Override
    public Object handle(AiTask task, AiTaskProgressReporter reporter) {
        return shopReviewVectorIndexService.rebuildShop(
                AiTaskParams.longParam(task, "shopId"),
                AiTaskParams.integerParam(task, "limit"));
    }
}

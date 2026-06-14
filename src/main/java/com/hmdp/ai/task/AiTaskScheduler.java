package com.hmdp.ai.task;

import com.hmdp.dto.ai.AiTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class AiTaskScheduler {

    @Resource
    private AiTaskService aiTaskService;

    @Value("${hmdp.ai.task.schedule.batch-summary.enabled:false}")
    private boolean batchSummaryEnabled;

    @Value("${hmdp.ai.task.schedule.batch-summary.shop-limit:200}")
    private int scheduleShopLimit;

    @Scheduled(cron = "${hmdp.ai.task.schedule.batch-summary.cron:0 0 3 * * *}")
    public void scheduleBatchSummary() {
        if (!batchSummaryEnabled) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shopLimit", scheduleShopLimit);
        String taskId = aiTaskService.submit(AiTaskType.BATCH_SHOP_SUMMARY, params, "system-scheduler");
        log.info("Scheduled batch shop summary task submitted, taskId={}", taskId);
    }
}

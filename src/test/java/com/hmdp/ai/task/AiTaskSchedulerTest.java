package com.hmdp.ai.task;

import com.hmdp.dto.ai.AiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskSchedulerTest {

    @Mock
    private AiTaskService aiTaskService;

    private AiTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AiTaskScheduler();
        ReflectionTestUtils.setField(scheduler, "aiTaskService", aiTaskService);
        ReflectionTestUtils.setField(scheduler, "batchSummaryEnabled", false);
        ReflectionTestUtils.setField(scheduler, "scheduleShopLimit", 200);
    }

    @Test
    void scheduleBatchSummaryShouldDoNothingWhenDisabled() {
        scheduler.scheduleBatchSummary();

        verify(aiTaskService, never()).submit(eq(AiTaskType.BATCH_SHOP_SUMMARY), anyMap(), eq("system-scheduler"));
    }

    @Test
    void scheduleBatchSummaryShouldSubmitWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "batchSummaryEnabled", true);
        ReflectionTestUtils.setField(scheduler, "scheduleShopLimit", 50);
        when(aiTaskService.submit(eq(AiTaskType.BATCH_SHOP_SUMMARY), anyMap(), eq("system-scheduler")))
                .thenReturn("task-1");

        scheduler.scheduleBatchSummary();

        verify(aiTaskService).submit(eq(AiTaskType.BATCH_SHOP_SUMMARY), anyMap(), eq("system-scheduler"));
    }
}

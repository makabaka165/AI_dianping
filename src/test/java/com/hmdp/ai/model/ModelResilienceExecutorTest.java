package com.hmdp.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResilienceExecutorTest {

    @Test
    void boundedExecutorShouldRejectWhenCapacityExceeded() throws Exception {
        ModelResilienceExecutor executor = new ModelResilienceExecutor();
        ReflectionTestUtils.setField(executor, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(executor, "maxConcurrentCalls", 2);
        ReflectionTestUtils.setField(executor, "rateLimitPeriodSeconds", 1L);
        ReflectionTestUtils.setField(executor, "rateLimitPermits", 100);
        ReflectionTestUtils.setField(executor, "executorCoreSize", 1);
        ReflectionTestUtils.setField(executor, "executorMaxSize", 1);
        ReflectionTestUtils.setField(executor, "executorQueueCapacity", 0);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                executor.execute("hold", () -> {
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return "ok";
                });
            } catch (Exception ignored) {
                // test cleanup
            }
        });
        holder.start();
        started.await(1, TimeUnit.SECONDS);

        assertThatThrownBy(() -> executor.execute("second", () -> "ok"))
                .isInstanceOf(Exception.class);

        release.countDown();
        holder.join(1000);
    }
}

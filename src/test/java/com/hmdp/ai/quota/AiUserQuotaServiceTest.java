package com.hmdp.ai.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUserQuotaServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RAtomicLong minuteCounter;

    @Mock
    private RAtomicLong dayCounter;

    private AiUserQuotaService service;

    @BeforeEach
    void setUp() {
        service = new AiUserQuotaService();
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minutePermits", 1L);
        ReflectionTestUtils.setField(service, "dailyPermits", 2L);
        ReflectionTestUtils.setField(service, "failOpen", false);
        when(redissonClient.getAtomicLong(anyString()))
                .thenReturn(minuteCounter)
                .thenReturn(dayCounter);
    }

    @Test
    void firstRequestShouldConsumeMinuteAndDailyQuotaWithTtl() {
        when(minuteCounter.incrementAndGet()).thenReturn(1L);
        when(dayCounter.incrementAndGet()).thenReturn(1L);

        service.checkAndConsume("u1", "chat");

        verify(minuteCounter).expire(eq(90L), eq(TimeUnit.SECONDS));
        verify(dayCounter).expire(eq(93600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldRejectWhenMinuteQuotaExceeded() {
        when(minuteCounter.incrementAndGet()).thenReturn(2L);

        assertThatThrownBy(() -> service.checkAndConsume("u1", "chat"))
                .isInstanceOf(AiQuotaExceededException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    void shouldMarkFailClosedQuotaInfraError() {
        when(minuteCounter.incrementAndGet()).thenReturn(1L);
        when(dayCounter.incrementAndGet()).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.checkAndConsume("u1", "chat"))
                .isInstanceOfSatisfying(AiQuotaExceededException.class, e -> {
                    assertThat(e.isInfraError()).isTrue();
                    assertThat(e).hasMessage("AI 配额校验暂不可用");
                });
    }
}

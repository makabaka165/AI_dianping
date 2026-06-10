package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillVoucherServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SeckillVoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void prewarmSeckillVoucherShouldSetActivityWindowAndNotOverwriteStock() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 6, 10, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 6, 10, 11, 0);
        SeckillVoucher voucher = new SeckillVoucher()
                .setVoucherId(12L)
                .setStock(3)
                .setBeginTime(beginTime)
                .setEndTime(endTime);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.prewarmSeckillVoucher(voucher);

        verify(valueOperations).setIfAbsent(SECKILL_STOCK_KEY + 12L, "3");
        verify(valueOperations).set(SECKILL_BEGIN_KEY + 12L, epochSecond(beginTime));
        verify(valueOperations).set(SECKILL_END_KEY + 12L, epochSecond(endTime));
    }

    private String epochSecond(LocalDateTime time) {
        return String.valueOf(time.atZone(ZoneId.systemDefault()).toEpochSecond());
    }
}

package com.hmdp.service.impl;

import com.hmdp.entity.Voucher;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.ISeckillVoucherService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private IPermissionService permissionService;
    @Mock
    private IMerchantShopService merchantShopService;

    private TestableVoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableVoucherServiceImpl();
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "merchantShopService", merchantShopService);
    }

    @Test
    void addSeckillVoucherShouldPrewarmStockAndActivityWindow() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 6, 10, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 6, 10, 11, 0);
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setStock(3);
        voucher.setBeginTime(beginTime);
        voucher.setEndTime(endTime);

        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(true);
        when(seckillVoucherService.save(any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.addSeckillVoucher(voucher);

        verify(valueOperations).set(SECKILL_STOCK_KEY + 12L, "3");
        verify(valueOperations).set(SECKILL_BEGIN_KEY + 12L, epochSecond(beginTime));
        verify(valueOperations).set(SECKILL_END_KEY + 12L, epochSecond(endTime));
    }

    private String epochSecond(LocalDateTime time) {
        return String.valueOf(time.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    private static class TestableVoucherServiceImpl extends VoucherServiceImpl {
        @Override
        public boolean save(Voucher entity) {
            entity.setId(12L);
            return true;
        }
    }
}

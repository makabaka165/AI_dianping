package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherCreateDTO;
import com.hmdp.dto.VoucherCreateDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private ShopMapper shopMapper;

    private TestableVoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableVoucherServiceImpl();
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "merchantShopService", merchantShopService);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void addVoucherWhenShopNotExistsShouldReturnShopNotFoundAndNotSave() {
        VoucherCreateDTO request = normalRequest();
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(0);

        Result result = service.addVoucher(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SHOP_NOT_FOUND.getCode());
        assertThat(service.savedVouchers).isEmpty();
        verifyNoInteractions(currentUserService, permissionService, merchantShopService);
    }

    @Test
    void addVoucherWhenNotOwnerOrAdminShouldReturnForbidden() {
        VoucherCreateDTO request = normalRequest();
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(false);
        when(merchantShopService.isShopOwner(7L, 1L)).thenReturn(false);

        Result result = service.addVoucher(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(service.savedVouchers).isEmpty();
    }

    @Test
    void addVoucherWhenShopOwnerShouldSaveNormalVoucherAndReturnId() {
        VoucherCreateDTO request = normalRequest();
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(false);
        when(merchantShopService.isShopOwner(7L, 1L)).thenReturn(true);

        Result result = service.addVoucher(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(12L);
        assertThat(service.savedVouchers).hasSize(1);
        Voucher saved = service.savedVouchers.get(0);
        assertThat(saved.getShopId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(1);
    }

    @Test
    void addSeckillVoucherShouldPrewarmStockAndActivityWindow() {
        SeckillVoucherCreateDTO request = seckillRequest();

        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(true);
        when(seckillVoucherService.save(any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Result result = service.addSeckillVoucher(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(12L);
        ArgumentCaptor<SeckillVoucher> seckillCaptor = ArgumentCaptor.forClass(SeckillVoucher.class);
        verify(seckillVoucherService).save(seckillCaptor.capture());
        assertThat(seckillCaptor.getValue().getVoucherId()).isEqualTo(12L);
        assertThat(seckillCaptor.getValue().getStock()).isEqualTo(3);
        verify(valueOperations).set(SECKILL_STOCK_KEY + 12L, "3");
        verify(valueOperations).set(SECKILL_BEGIN_KEY + 12L, epochSecond(request.getBeginTime()));
        verify(valueOperations).set(SECKILL_END_KEY + 12L, epochSecond(request.getEndTime()));
    }

    @Test
    void addSeckillVoucherWhenDbVoucherSaveFailsShouldNotWriteRedis() {
        SeckillVoucherCreateDTO request = seckillRequest();
        service.saveResult = false;

        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(true);

        Result result = service.addSeckillVoucher(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.BUSINESS_ERROR.getCode());
        verify(seckillVoucherService, never()).save(any());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void addSeckillVoucherInsideTransactionShouldPrewarmOnlyAfterCommit() {
        SeckillVoucherCreateDTO request = seckillRequest();
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(permissionService.hasRole(7L, "admin")).thenReturn(true);
        when(seckillVoucherService.save(any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        TransactionSynchronizationManager.initSynchronization();

        Result result = service.addSeckillVoucher(request);

        assertThat(result.getSuccess()).isTrue();
        verify(stringRedisTemplate, never()).opsForValue();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        verify(valueOperations).set(SECKILL_STOCK_KEY + 12L, "3");
    }

    private String epochSecond(LocalDateTime time) {
        return String.valueOf(time.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    private VoucherCreateDTO normalRequest() {
        VoucherCreateDTO request = new VoucherCreateDTO();
        request.setShopId(1L);
        request.setTitle("50 yuan coupon");
        request.setSubTitle("weekday");
        request.setRules("rules");
        request.setPayValue(4750L);
        request.setActualValue(5000L);
        return request;
    }

    private SeckillVoucherCreateDTO seckillRequest() {
        SeckillVoucherCreateDTO request = new SeckillVoucherCreateDTO();
        request.setShopId(1L);
        request.setTitle("flash sale coupon");
        request.setSubTitle("limited");
        request.setRules("rules");
        request.setPayValue(4750L);
        request.setActualValue(5000L);
        request.setStock(3);
        request.setBeginTime(LocalDateTime.of(2099, 6, 10, 10, 0));
        request.setEndTime(LocalDateTime.of(2099, 6, 10, 11, 0));
        return request;
    }

    private static class TestableVoucherServiceImpl extends VoucherServiceImpl {
        private final java.util.List<Voucher> savedVouchers = new java.util.ArrayList<>();
        private boolean saveResult = true;

        @Override
        protected boolean saveVoucherEntity(Voucher entity) {
            if (!saveResult) {
                return false;
            }
            entity.setId(12L);
            savedVouchers.add(entity);
            return true;
        }
    }
}

package com.hmdp.service.impl;

import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.utils.RedisIdWorker;
import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private SeckillVoucherMapper seckillVoucherMapper;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private TestableVoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableVoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", seckillVoucherMapper);
        ReflectionTestUtils.setField(service, "streamReady", true);
        ReflectionTestUtils.setField(service, "running", true);
        ReflectionTestUtils.setField(service, "consumersStarted", true);
        ReflectionTestUtils.setField(service, "executor", java.util.concurrent.Executors.newSingleThreadExecutor());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seckillVoucherShouldExecuteLuaAndReturnOrderId() {
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(redisIdWorker.nextId("voucher_order")).thenReturn(1001L);
        doReturn(0L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), eq(Collections.emptyList()), eq("12"), eq("7"), eq("1001"));

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1001L);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), eq(Collections.emptyList()), eq("12"), eq("7"), eq("1001"));
    }

    @Test
    void seckillVoucherShouldRejectWhenStreamRequiredAndNotReady() {
        ReflectionTestUtils.setField(service, "streamReady", false);
        ReflectionTestUtils.setField(service, "streamRequired", true);

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("订单服务暂不可用");
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void seckillVoucherShouldRejectWhenConsumersAreNotRunning() {
        ReflectionTestUtils.setField(service, "streamReady", true);
        ReflectionTestUtils.setField(service, "running", false);
        ReflectionTestUtils.setField(service, "consumersStarted", false);
        ReflectionTestUtils.setField(service, "streamRequired", true);

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("订单服务暂不可用");
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void seckillVoucherShouldRejectWhenStreamRequiredAndExecutorShutdown() {
        ReflectionTestUtils.setField(service, "streamRequired", true);
        ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
        executor.shutdownNow();

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("订单服务暂不可用");
        verify(currentUserService, never()).requireCurrentUserId();
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seckillVoucherShouldContinueWhenStreamNotRequiredAndNotReady() {
        ReflectionTestUtils.setField(service, "streamReady", false);
        ReflectionTestUtils.setField(service, "streamRequired", false);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(redisIdWorker.nextId("voucher_order")).thenReturn(1001L);
        doReturn(0L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), eq(Collections.emptyList()), eq("12"), eq("7"), eq("1001"));

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1001L);
    }

    @Test
    void initializeStreamShouldSetOrderStreamReady() {
        service.streamInitResult = true;

        boolean initialized = service.initializeStreamAndGroup();

        assertThat(initialized).isTrue();
        assertThat(service.isOrderStreamReady()).isTrue();
    }

    @Test
    void initShouldSubmitConfiguredConsumerLoops() {
        prepareForConsumerStart();
        service.streamInitResult = true;
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "closeWorkerThreads", 3);

        service.init();

        assertThat(service.submittedConsumerLoops).isEqualTo(2);
        assertThat(service.submittedCloseLoops).isEqualTo(3);
        assertThat(service.getOrderConsumerHealth()).containsEntry("workerThreads", 2);
        assertThat(service.getOrderConsumerHealth()).containsEntry("closeWorkerThreads", 3);
        service.destroy();
    }

    @Test
    void healthCheckShouldStartConsumersAfterStartupRedisFailureAndOnlyStartOnce() {
        prepareForConsumerStart();
        ReflectionTestUtils.setField(service, "streamHealthCheckEnabled", true);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "closeWorkerThreads", 1);
        service.streamInitResult = false;

        service.init();

        assertThat(service.submittedConsumerLoops).isZero();
        assertThat(service.submittedCloseLoops).isZero();

        service.streamVerifyResult = true;
        service.refreshOrderStreamHealth();
        service.refreshOrderStreamHealth();

        assertThat(service.submittedConsumerLoops).isEqualTo(2);
        assertThat(service.submittedCloseLoops).isEqualTo(1);
        assertThat(service.isOrderStreamReady()).isTrue();
        service.destroy();
    }

    @Test
    void consumerStartupFailureShouldRollbackBothExecutorsAndReadiness() {
        prepareForConsumerStart();
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "closeWorkerThreads", 1);
        service.failConsumerLoopSubmissionAt = 2;

        assertThatThrownBy(service::startConsumersIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit consumer loop failed");

        assertThat(service.getOrderConsumerHealth())
                .containsEntry("running", false)
                .containsEntry("consumersStarted", false);
        assertThat(service.isOrderServiceReady()).isFalse();
        assertThat(service.createdExecutors).isNotEmpty().allMatch(ExecutorService::isShutdown);
        assertThat(ReflectionTestUtils.getField(service, "executor")).isNull();
        assertThat(ReflectionTestUtils.getField(service, "closeOrderExecutor")).isNull();
    }

    @Test
    void initShouldRemainAvailableForHealthCheckRetryAfterConsumerStartupFailure() {
        prepareForConsumerStart();
        ReflectionTestUtils.setField(service, "streamHealthCheckEnabled", true);
        ReflectionTestUtils.setField(service, "workerThreads", 1);
        ReflectionTestUtils.setField(service, "closeWorkerThreads", 1);
        service.streamInitResult = true;
        service.failConsumerLoopSubmissionAt = 1;

        service.init();

        assertThat(service.isOrderServiceReady()).isFalse();
        assertThat(service.getOrderConsumerHealth())
                .containsEntry("running", false)
                .containsEntry("consumersStarted", false);

        service.failConsumerLoopSubmissionAt = -1;
        service.streamVerifyResult = true;
        service.refreshOrderStreamHealth();

        assertThat(service.isOrderServiceReady()).isTrue();
        service.destroy();
    }

    @Test
    void acknowledgeShouldDeleteProcessedEntryAfterAck() {
        MapRecord<String, Object, Object> record = record("1-0", Map.of("id", "1001"));
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);

        service.acknowledgeUsingBase(record);

        verify(streamOperations).acknowledge(VoucherOrderServiceImpl.STREAM_KEY,
                VoucherOrderServiceImpl.GROUP_NAME, record.getId());
        verify(streamOperations).delete(VoucherOrderServiceImpl.STREAM_KEY, record.getId());
    }

    @Test
    void acknowledgeFailureShouldNotDeleteUnacknowledgedEntry() {
        MapRecord<String, Object, Object> record = record("1-0", Map.of("id", "1001"));
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge(VoucherOrderServiceImpl.STREAM_KEY,
                VoucherOrderServiceImpl.GROUP_NAME, record.getId()))
                .thenThrow(new IllegalStateException("redis ack failed"));

        assertThatThrownBy(() -> service.acknowledgeUsingBase(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis ack failed");

        verify(streamOperations, never()).delete(VoucherOrderServiceImpl.STREAM_KEY, record.getId());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seckillVoucherShouldMapLuaFailureCodes() {
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(redisIdWorker.nextId("voucher_order")).thenReturn(1001L, 1002L, 1003L, 1004L, 1005L);
        doReturn(1L, 2L, 3L, 4L, 5L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());

        Result stockResult = service.seckillVoucher(12L);
        Result duplicateResult = service.seckillVoucher(12L);
        Result notReadyResult = service.seckillVoucher(12L);
        Result notStartedResult = service.seckillVoucher(12L);
        Result endedResult = service.seckillVoucher(12L);

        assertThat(stockResult.getSuccess()).isFalse();
        assertThat(stockResult.getErrorMsg()).isEqualTo("库存不足");
        assertThat(duplicateResult.getSuccess()).isFalse();
        assertThat(duplicateResult.getErrorMsg()).isEqualTo("不能重复下单");
        assertThat(notReadyResult.getSuccess()).isFalse();
        assertThat(notReadyResult.getErrorMsg()).isEqualTo("秒杀活动未准备好");
        assertThat(notStartedResult.getSuccess()).isFalse();
        assertThat(notStartedResult.getErrorMsg()).isEqualTo("秒杀活动尚未开始");
        assertThat(endedResult.getSuccess()).isFalse();
        assertThat(endedResult.getErrorMsg()).isEqualTo("秒杀活动已结束");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seckillVoucherShouldFailWhenRedisThrows() {
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(redisIdWorker.nextId("voucher_order")).thenReturn(1001L);
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("秒杀失败，请稍后重试");
        verify(seckillVoucherMapper, never()).deductStock(any());
    }

    @Test
    void seckillVoucherShouldRejectAnonymousUser() {
        when(currentUserService.requireCurrentUserId())
                .thenThrow(new NotLoginException(NotLoginException.NOT_TOKEN, "login", "authorization"));

        Result result = service.seckillVoucher(12L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void createVoucherOrderShouldSaveOrderAndDeductStock() {
        service.saveResult = true;
        when(seckillVoucherMapper.deductStock(12L)).thenReturn(1);

        service.createVoucherOrder(order(1001L, 7L, 12L));

        assertThat(service.savedOrders).extracting(VoucherOrder::getId).containsExactly(1001L);
        assertThat(service.closeTasks).containsExactly(1001L);
        verify(seckillVoucherMapper).deductStock(12L);
    }

    @Test
    void createVoucherOrderShouldTreatDuplicateAsIdempotentSuccess() {
        service.duplicateOnSave = true;
        service.ordersById.put(1001L, order(1001L, 7L, 12L));

        service.createVoucherOrder(order(1001L, 7L, 12L));

        assertThat(service.closeTasks).isEmpty();
        verify(seckillVoucherMapper, never()).deductStock(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void createVoucherOrderShouldCompensateRedisWhenDuplicateActiveOrderExistsButOrderIdMissing() {
        service.duplicateOnSave = true;
        VoucherOrder activeOrder = order(2001L, 7L, 12L);
        activeOrder.setStatus(1);
        service.ordersById.put(2001L, activeOrder);
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));

        service.createVoucherOrder(order(1001L, 7L, 12L));

        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));
        verify(seckillVoucherMapper, never()).deductStock(any());
        assertThat(service.closeTasks).isEmpty();
    }

    @Test
    void createVoucherOrderShouldThrowWhenDbStockIsInsufficient() {
        service.saveResult = true;
        when(seckillVoucherMapper.deductStock(12L)).thenReturn(0);

        assertThatThrownBy(() -> service.createVoucherOrder(order(1001L, 7L, 12L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("秒杀券库存不足，订单落库回滚");
    }

    @Test
    void payVoucherOrderShouldMarkUnpaidOrderPaid() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        service.ordersById.put(1001L, unpaidOrder);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1001L);
        assertThat(service.paidOrders).containsExactly(1001L);
        assertThat(unpaidOrder.getStatus()).isEqualTo(2);
        assertThat(unpaidOrder.getPayRequestId()).isEqualTo("pay-1");
    }

    @Test
    void payVoucherOrderShouldReturnOkWhenConcurrentSamePayRequestAlreadyPaid() {
        VoucherOrder initiallyUnpaidOrder = order(1001L, 7L, 12L);
        initiallyUnpaidOrder.setStatus(1);
        service.ordersById.put(1001L, initiallyUnpaidOrder);
        VoucherOrder concurrentlyPaidOrder = order(1001L, 7L, 12L);
        concurrentlyPaidOrder.setStatus(2);
        concurrentlyPaidOrder.setPayRequestId("pay-1");
        service.forcePayUpdateFailure = true;
        service.orderAfterForcedPayUpdateFailure = concurrentlyPaidOrder;
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(1001L);
        assertThat(service.paidOrders).isEmpty();
        assertThat(service.orderIdsReadForUpdate).containsExactly(1001L);
        assertThat(service.ordersById.get(1001L).getStatus()).isEqualTo(1);
        assertThat(service.ordersByIdForUpdate.get(1001L).getStatus()).isEqualTo(2);
        assertThat(service.ordersByIdForUpdate.get(1001L).getPayRequestId()).isEqualTo("pay-1");
    }

    @Test
    void payVoucherOrderShouldRejectConcurrentPaidWithDifferentPayRequestId() {
        VoucherOrder initiallyUnpaidOrder = order(1001L, 7L, 12L);
        initiallyUnpaidOrder.setStatus(1);
        service.ordersById.put(1001L, initiallyUnpaidOrder);
        VoucherOrder concurrentlyPaidOrder = order(1001L, 7L, 12L);
        concurrentlyPaidOrder.setStatus(2);
        concurrentlyPaidOrder.setPayRequestId("pay-2");
        service.forcePayUpdateFailure = true;
        service.orderAfterForcedPayUpdateFailure = concurrentlyPaidOrder;
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("order already paid");
        assertThat(service.paidOrders).isEmpty();
        assertThat(service.orderIdsReadForUpdate).containsExactly(1001L);
    }

    @Test
    void payVoucherOrderShouldRejectCanceledOrder() {
        VoucherOrder canceledOrder = order(1001L, 7L, 12L);
        canceledOrder.setStatus(4);
        service.ordersById.put(1001L, canceledOrder);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("order canceled");
        assertThat(service.paidOrders).isEmpty();
    }

    @Test
    void payVoucherOrderShouldRejectOtherUserOrder() {
        VoucherOrder order = order(1001L, 8L, 12L);
        order.setStatus(1);
        service.ordersById.put(1001L, order);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("no permission to pay this order");
        assertThat(service.paidOrders).isEmpty();
    }

    @Test
    void payVoucherOrderShouldRejectMissingPayRequestIdAsParamError() {
        Result result = service.payVoucherOrder(1001L, "  ");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("payRequestId is required");
    }

    @Test
    void payVoucherOrderShouldFailExpiredPaymentWhenStockRestoreFails() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        unpaidOrder.setCreateTime(java.time.LocalDateTime.now().minusMinutes(30));
        service.ordersById.put(1001L, unpaidOrder);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(seckillVoucherMapper.restoreStock(12L)).thenReturn(0);

        Result result = service.payVoucherOrder(1001L, "pay-1");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("payment failed, please retry later");
        assertThat(service.canceledOrders).containsExactly(1001L);
        assertThat(service.paidOrders).isEmpty();
    }

    @Test
    void closeUnpaidVoucherOrderShouldCancelAndRestoreStockAndRedis() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        service.ordersById.put(1001L, unpaidOrder);
        when(seckillVoucherMapper.restoreStock(12L)).thenReturn(1);

        boolean closed = service.closeUnpaidVoucherOrder(1001L);

        assertThat(closed).isTrue();
        assertThat(service.canceledOrders).containsExactly(1001L);
        assertThat(service.redisRestoredOrders).containsExactly(1001L);
        verify(seckillVoucherMapper).restoreStock(12L);
    }

    @Test
    void closeUnpaidVoucherOrderShouldRecordRedisRestoreFailure() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        service.ordersById.put(1001L, unpaidOrder);
        service.redisRestoreResult = false;
        when(seckillVoucherMapper.restoreStock(12L)).thenReturn(1);

        boolean closed = service.closeUnpaidVoucherOrder(1001L);

        assertThat(closed).isTrue();
        assertThat(service.redisRestoredOrders).containsExactly(1001L);
        assertThat(service.redisCompensationFailures)
                .containsExactly("seckill:compensated:1001:close-unpaid-after-commit");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryFailedRedisCompensationShouldRestoreAndClearFailureRecord() {
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "7");
        payload.put("voucherId", "12");
        payload.put("releaseUserEligibility", "1");
        when(hashOperations.entries("seckill:compensation:failed:order:1001")).thenReturn(payload);
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("1"), eq("604800"));

        service.retryFailedRedisCompensation("1001");

        assertThat(service.redisRestoredOrders).isEmpty();
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("1"), eq("604800"));
        verify(setOperations).remove("seckill:compensation:failed:orders", "1001");
        verify(stringRedisTemplate).delete("seckill:compensation:failed:order:1001");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retryFailedRedisCompensationShouldKeepEligibilityWhenPayloadSaysFalse() {
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "7");
        payload.put("voucherId", "12");
        payload.put("releaseUserEligibility", "0");
        when(hashOperations.entries("seckill:compensation:failed:order:1001")).thenReturn(payload);
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));

        service.retryFailedRedisCompensation("1001");

        assertThat(service.redisRestoredOrders).isEmpty();
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));
        verify(setOperations).remove("seckill:compensation:failed:orders", "1001");
        verify(stringRedisTemplate).delete("seckill:compensation:failed:order:1001");
    }

    @Test
    void closeUnpaidVoucherOrderShouldIgnorePaidOrder() {
        VoucherOrder paidOrder = order(1001L, 7L, 12L);
        paidOrder.setStatus(2);
        service.ordersById.put(1001L, paidOrder);

        boolean closed = service.closeUnpaidVoucherOrder(1001L);

        assertThat(closed).isFalse();
        assertThat(service.canceledOrders).isEmpty();
        assertThat(service.redisRestoredOrders).isEmpty();
        verify(seckillVoucherMapper, never()).restoreStock(any());
    }

    @Test
    void closeUnpaidVoucherOrderShouldThrowWhenStockRestoreFails() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        service.ordersById.put(1001L, unpaidOrder);
        when(seckillVoucherMapper.restoreStock(12L)).thenReturn(0);

        assertThatThrownBy(() -> service.closeUnpaidVoucherOrder(1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("秒杀券库存回补失败");
    }

    @Test
    void closeExpiredUnpaidVoucherOrdersShouldCloseOnlyStillUnpaidOrders() {
        VoucherOrder unpaidOrder = order(1001L, 7L, 12L);
        unpaidOrder.setStatus(1);
        VoucherOrder paidOrder = order(1002L, 8L, 13L);
        paidOrder.setStatus(2);
        service.ordersById.put(1001L, unpaidOrder);
        service.ordersById.put(1002L, paidOrder);
        service.expiredOrders.add(unpaidOrder);
        service.expiredOrders.add(paidOrder);
        when(seckillVoucherMapper.restoreStock(12L)).thenReturn(1);

        int closed = service.closeExpiredUnpaidVoucherOrders(50);

        assertThat(closed).isEqualTo(1);
        assertThat(service.canceledOrders).containsExactly(1001L);
        assertThat(service.redisRestoredOrders).containsExactly(1001L);
        verify(seckillVoucherMapper).restoreStock(12L);
        verify(seckillVoucherMapper, never()).restoreStock(13L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compensateRedisPreDeductShouldReleaseUserEligibilityWithOrderScopedMarker() {
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("1"), eq("604800"));

        boolean compensated = service.compensateRedisPreDeduct(order(1001L, 7L, 12L), true);

        assertThat(compensated).isTrue();
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("1"), eq("604800"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compensateRedisPreDeductShouldKeepEligibilityForDuplicateActiveOrder() {
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1002")),
                eq("1002"), eq("7"), eq("0"), eq("604800"));

        boolean compensated = service.compensateRedisPreDeduct(order(1002L, 7L, 12L), false);

        assertThat(compensated).isTrue();
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1002")),
                eq("1002"), eq("7"), eq("0"), eq("604800"));
    }

    @Test
    void processRecordsShouldAckAfterSuccessfulPersistence() {
        MapRecord<String, Object, Object> record = orderRecord("1-0");

        service.processRecords(List.of(record));

        assertThat(service.processedRecords).containsExactly(record);
        assertThat(service.ackedRecords).containsExactly(record);
        assertThat(service.deadLetters).isEmpty();
    }

    @Test
    void processRecordsShouldNotAckWhenPersistenceFails() {
        MapRecord<String, Object, Object> record = orderRecord("1-0");
        service.failProcessing = true;

        service.processRecords(List.of(record));

        assertThat(service.processedRecords).containsExactly(record);
        assertThat(service.ackedRecords).isEmpty();
        assertThat(service.deadLetters).isEmpty();
    }

    @Test
    void processRecordsShouldAckInvalidAndInitMessages() {
        MapRecord<String, Object, Object> initRecord = record("1-0", Map.of("init", "true"));
        MapRecord<String, Object, Object> invalidRecord = record("2-0", Map.of("voucherId", "12"));

        service.processRecords(List.of(initRecord, invalidRecord));

        assertThat(service.processedRecords).isEmpty();
        assertThat(service.ackedRecords).containsExactly(initRecord, invalidRecord);
    }

    @Test
    void processRecordsShouldDeadLetterWhenDeliveryCountExceeded() {
        MapRecord<String, Object, Object> record = orderRecord("1-0");
        service.pendingMessage = new PendingMessage(
                record.getId(),
                Consumer.from(VoucherOrderServiceImpl.GROUP_NAME, "other-consumer"),
                Duration.ofMinutes(1),
                VoucherOrderServiceImpl.MAX_DELIVERY_COUNT + 1L);

        service.processRecords(List.of(record));

        assertThat(service.processedRecords).isEmpty();
        assertThat(service.deadLetters).containsExactly(record);
        assertThat(service.deadLetterReasons.get(0)).contains("max delivery count exceeded");
        assertThat(service.ackedRecords).containsExactly(record);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void processRecordsShouldKeepRedisEligibilityWhenDeadLetteringDuplicateActiveOrder() {
        MapRecord<String, Object, Object> record = orderRecord("1-0");
        VoucherOrder activeOrder = order(2001L, 7L, 12L);
        activeOrder.setStatus(1);
        service.ordersById.put(2001L, activeOrder);
        service.pendingMessage = new PendingMessage(
                record.getId(),
                Consumer.from(VoucherOrderServiceImpl.GROUP_NAME, "other-consumer"),
                Duration.ofMinutes(1),
                VoucherOrderServiceImpl.MAX_DELIVERY_COUNT + 1L);
        doReturn(1L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));

        service.processRecords(List.of(record));

        assertThat(service.processedRecords).isEmpty();
        assertThat(service.deadLetters).containsExactly(record);
        assertThat(service.ackedRecords).containsExactly(record);
        assertThat(service.redisRestoredOrders).isEmpty();
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("seckill:stock:12", "seckill:order:12", "seckill:compensated:1001")),
                eq("1001"), eq("7"), eq("0"), eq("604800"));
    }

    @Test
    void processRecordsShouldNotAckDeadLetterWhenRedisCompensationFails() {
        MapRecord<String, Object, Object> record = orderRecord("1-0");
        service.redisRestoreResult = false;
        service.pendingMessage = new PendingMessage(
                record.getId(),
                Consumer.from(VoucherOrderServiceImpl.GROUP_NAME, "other-consumer"),
                Duration.ofMinutes(1),
                VoucherOrderServiceImpl.MAX_DELIVERY_COUNT + 1L);

        service.processRecords(List.of(record));

        assertThat(service.deadLetters).isEmpty();
        assertThat(service.ackedRecords).isEmpty();
        assertThat(service.redisRestoredOrders).containsExactly(1001L);
    }

    private VoucherOrder order(Long id, Long userId, Long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }

    private MapRecord<String, Object, Object> orderRecord(String id) {
        Map<Object, Object> value = new LinkedHashMap<>();
        value.put("id", "1001");
        value.put("userId", "7");
        value.put("voucherId", "12");
        return record(id, value);
    }

    private MapRecord<String, Object, Object> record(String id, Map<?, ?> source) {
        Map<Object, Object> value = new LinkedHashMap<>();
        source.forEach(value::put);
        return MapRecord.create(VoucherOrderServiceImpl.STREAM_KEY, value).withId(RecordId.of(id));
    }

    private void prepareForConsumerStart() {
        ExecutorService existingExecutor = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
        if (existingExecutor != null) {
            existingExecutor.shutdownNow();
        }
        ReflectionTestUtils.setField(service, "running", false);
        ReflectionTestUtils.setField(service, "consumersStarted", false);
        ReflectionTestUtils.setField(service, "shuttingDown", false);
    }

    private static class TestableVoucherOrderServiceImpl extends VoucherOrderServiceImpl {
        private final List<VoucherOrder> savedOrders = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> processedRecords = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> ackedRecords = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> deadLetters = new ArrayList<>();
        private final List<String> deadLetterReasons = new ArrayList<>();
        private final List<Long> closeTasks = new ArrayList<>();
        private final List<Long> canceledOrders = new ArrayList<>();
        private final List<Long> paidOrders = new ArrayList<>();
        private final List<Long> redisRestoredOrders = new ArrayList<>();
        private final List<String> redisCompensationFailures = new ArrayList<>();
        private final List<VoucherOrder> expiredOrders = new ArrayList<>();
        private final Map<Long, VoucherOrder> ordersById = new LinkedHashMap<>();
        private final Map<Long, VoucherOrder> ordersByIdForUpdate = new LinkedHashMap<>();
        private final List<Long> orderIdsReadForUpdate = new ArrayList<>();
        private boolean saveResult = true;
        private boolean duplicateOnSave = false;
        private boolean failProcessing = false;
        private boolean streamInitResult = true;
        private boolean streamVerifyResult = false;
        private boolean redisRestoreResult = true;
        private boolean forcePayUpdateFailure = false;
        private VoucherOrder orderAfterForcedPayUpdateFailure;
        private int submittedConsumerLoops = 0;
        private int submittedCloseLoops = 0;
        private int consumerLoopSubmissionAttempts = 0;
        private int failConsumerLoopSubmissionAt = -1;
        private final List<ExecutorService> createdExecutors = new ArrayList<>();
        private PendingMessage pendingMessage;

        @Override
        protected boolean initializeStreamAndGroup() {
            ReflectionTestUtils.setField(this, "streamReady", streamInitResult);
            return streamInitResult;
        }

        @Override
        protected boolean verifyStreamAndGroup() {
            return streamVerifyResult;
        }

        @Override
        protected ExecutorService createExecutor(String threadNamePrefix, int threads) {
            ExecutorService created = java.util.concurrent.Executors.newFixedThreadPool(threads);
            createdExecutors.add(created);
            return created;
        }

        @Override
        protected void submitConsumerLoop(ExecutorService targetExecutor, Runnable task) {
            consumerLoopSubmissionAttempts++;
            if (consumerLoopSubmissionAttempts == failConsumerLoopSubmissionAt) {
                throw new IllegalStateException("submit consumer loop failed");
            }
            String className = task.getClass().getSimpleName();
            if (className.contains("VoucherOrderCloseHandler")) {
                submittedCloseLoops++;
            } else {
                submittedConsumerLoops++;
            }
        }

        @Override
        protected void initializeCloseOrderQueue() {
            // no Redis dependency in unit tests
        }

        @Override
        public boolean save(VoucherOrder entity) {
            if (duplicateOnSave) {
                throw new DuplicateKeyException("duplicate");
            }
            savedOrders.add(entity);
            return saveResult;
        }

        @Override
        protected void processOrderRecord(MapRecord<String, Object, Object> record) {
            processedRecords.add(record);
            if (failProcessing) {
                throw new IllegalStateException("db down");
            }
        }

        @Override
        protected void acknowledgeMessage(MapRecord<String, Object, Object> record) {
            ackedRecords.add(record);
        }

        private void acknowledgeUsingBase(MapRecord<String, Object, Object> record) {
            super.acknowledgeMessage(record);
        }

        @Override
        protected PendingMessage findPendingMessage(RecordId recordId) {
            return pendingMessage;
        }

        @Override
        protected void writeDeadLetter(MapRecord<String, Object, Object> record, String reason) {
            deadLetters.add(record);
            deadLetterReasons.add(reason);
        }

        @Override
        protected void enqueueOrderCloseTask(Long orderId) {
            closeTasks.add(orderId);
        }

        @Override
        protected VoucherOrder getOrderById(Long orderId) {
            return ordersById.get(orderId);
        }

        @Override
        protected VoucherOrder getOrderByIdForUpdate(Long orderId) {
            orderIdsReadForUpdate.add(orderId);
            return ordersByIdForUpdate.getOrDefault(orderId, ordersById.get(orderId));
        }

        @Override
        protected VoucherOrder getActiveOrder(Long userId, Long voucherId) {
            return ordersById.values().stream()
                    .filter(order -> userId.equals(order.getUserId()))
                    .filter(order -> voucherId.equals(order.getVoucherId()))
                    .filter(order -> !Integer.valueOf(4).equals(order.getStatus()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        protected boolean markUnpaidOrderCanceled(Long orderId) {
            VoucherOrder order = ordersById.get(orderId);
            if (order == null || !Integer.valueOf(1).equals(order.getStatus())) {
                return false;
            }
            canceledOrders.add(orderId);
            order.setStatus(4);
            return true;
        }

        @Override
        protected boolean markUnpaidOrderPaid(Long orderId, Long userId, String payRequestId, java.time.LocalDateTime paymentCutoff) {
            if (forcePayUpdateFailure) {
                forcePayUpdateFailure = false;
                if (orderAfterForcedPayUpdateFailure != null) {
                    ordersByIdForUpdate.put(orderId, orderAfterForcedPayUpdateFailure);
                }
                return false;
            }
            VoucherOrder order = ordersById.get(orderId);
            if (order == null || !userId.equals(order.getUserId()) || !Integer.valueOf(1).equals(order.getStatus())) {
                return false;
            }
            paidOrders.add(orderId);
            order.setStatus(2);
            order.setPayRequestId(payRequestId);
            return true;
        }

        @Override
        protected List<VoucherOrder> queryExpiredUnpaidOrders(int limit) {
            return expiredOrders;
        }

        @Override
        protected boolean restoreRedisSeckillState(VoucherOrder voucherOrder) {
            redisRestoredOrders.add(voucherOrder.getId());
            return redisRestoreResult;
        }

        @Override
        protected void recordRedisCompensationFailure(VoucherOrder voucherOrder, String reason) {
            redisCompensationFailures.add(redisCompensationKey(voucherOrder.getId()) + ":" + reason);
        }
    }
}

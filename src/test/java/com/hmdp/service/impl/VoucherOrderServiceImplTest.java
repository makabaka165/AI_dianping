package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.utils.RedisIdWorker;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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

    private TestableVoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableVoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", seckillVoucherMapper);
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seckillVoucherShouldMapLuaFailureCodes() {
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(redisIdWorker.nextId("voucher_order")).thenReturn(1001L, 1002L, 1003L);
        doReturn(1L, 2L, 3L).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());

        Result stockResult = service.seckillVoucher(12L);
        Result duplicateResult = service.seckillVoucher(12L);
        Result notReadyResult = service.seckillVoucher(12L);

        assertThat(stockResult.getSuccess()).isFalse();
        assertThat(stockResult.getErrorMsg()).isEqualTo("库存不足");
        assertThat(duplicateResult.getSuccess()).isFalse();
        assertThat(duplicateResult.getErrorMsg()).isEqualTo("不能重复下单");
        assertThat(notReadyResult.getSuccess()).isFalse();
        assertThat(notReadyResult.getErrorMsg()).isEqualTo("秒杀活动未准备好");
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
    void createVoucherOrderShouldSaveOrderAndDeductStock() {
        service.saveResult = true;
        when(seckillVoucherMapper.deductStock(12L)).thenReturn(1);

        service.createVoucherOrder(order(1001L, 7L, 12L));

        assertThat(service.savedOrders).extracting(VoucherOrder::getId).containsExactly(1001L);
        verify(seckillVoucherMapper).deductStock(12L);
    }

    @Test
    void createVoucherOrderShouldTreatDuplicateAsIdempotentSuccess() {
        service.duplicateOnSave = true;

        service.createVoucherOrder(order(1001L, 7L, 12L));

        verify(seckillVoucherMapper, never()).deductStock(any());
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

    private static class TestableVoucherOrderServiceImpl extends VoucherOrderServiceImpl {
        private final List<VoucherOrder> savedOrders = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> processedRecords = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> ackedRecords = new ArrayList<>();
        private final List<MapRecord<String, Object, Object>> deadLetters = new ArrayList<>();
        private final List<String> deadLetterReasons = new ArrayList<>();
        private boolean saveResult = true;
        private boolean duplicateOnSave = false;
        private boolean failProcessing = false;
        private PendingMessage pendingMessage;

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

        @Override
        protected PendingMessage findPendingMessage(RecordId recordId) {
            return pendingMessage;
        }

        @Override
        protected void writeDeadLetter(MapRecord<String, Object, Object> record, String reason) {
            deadLetters.add(record);
            deadLetterReasons.add(reason);
        }
    }
}

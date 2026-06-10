package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    static final String STREAM_KEY = "stream.orders";
    static final String DEAD_STREAM_KEY = "stream.orders.dead";
    static final String GROUP_NAME = "g1";
    static final int STREAM_READ_BATCH_SIZE = 50;
    static final int MAX_DELIVERY_COUNT = 5;
    static final Duration STREAM_BLOCK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration PENDING_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private static final String ORDER_ID_PREFIX = "voucher_order";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    private final String consumerName = buildConsumerName();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voucher-order-handler");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID无效");
        }
        try {
            Long userId = currentUserService.requireCurrentUserId();
            long orderId = redisIdWorker.nextId(ORDER_ID_PREFIX);
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
            );
            if (result == null) {
                log.warn("秒杀Lua脚本返回空结果，voucherId={}, userId={}, orderId={}", voucherId, userId, orderId);
                return Result.fail("秒杀失败，请稍后重试");
            }
            int resultCode = result.intValue();
            if (resultCode == 0) {
                return Result.ok(orderId);
            }
            if (resultCode == 1) {
                return Result.fail("库存不足");
            }
            if (resultCode == 2) {
                return Result.fail("不能重复下单");
            }
            if (resultCode == 3) {
                return Result.fail("秒杀活动未准备好");
            }
            log.warn("秒杀Lua脚本返回未知结果，result={}, voucherId={}, userId={}", result, voucherId, userId);
            return Result.fail("秒杀失败，请稍后重试");
        } catch (Exception e) {
            log.error("秒杀处理失败，voucherId={}", voucherId, e);
            return Result.fail("秒杀失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        validateVoucherOrder(voucherOrder);
        try {
            boolean saved = save(voucherOrder);
            if (!saved) {
                throw new IllegalStateException("订单保存失败");
            }
        } catch (DuplicateKeyException e) {
            log.info("订单已存在，按幂等成功处理，orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return;
        }

        int updated = seckillVoucherMapper.deductStock(voucherOrder.getVoucherId());
        if (updated != 1) {
            throw new IllegalStateException("秒杀券库存不足，订单落库回滚");
        }
    }

    @PostConstruct
    public void init() {
        if (!initializeStreamAndGroup()) {
            log.error("优惠券订单处理服务启动失败，Redis Stream不可用");
            return;
        }
        running = true;
        executor.submit(new VoucherOrderHandler());
        log.info("优惠券订单处理服务启动成功，consumer={}", consumerName);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            log.info("优惠券订单处理服务已停止，consumer={}", consumerName);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    protected boolean initializeStreamAndGroup() {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                ensureStreamExists();
                ensureGroupExists();
                return true;
            } catch (Exception e) {
                log.error("初始化Redis Stream失败，第{}次尝试", i + 1, e);
                if (i < maxRetries - 1) {
                    sleep(Duration.ofSeconds(2));
                }
            }
        }
        return false;
    }

    protected boolean verifyStreamAndGroup() {
        return streamExists() && groupExists();
    }

    protected void handleCurrentPendingList() {
        List<MapRecord<String, Object, Object>> records = readRecords(ReadOffset.from("0"), Duration.ofMillis(200));
        processRecords(records);
    }

    protected void claimTimeoutPendingMessages() {
        try {
            PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP_NAME, Range.unbounded(), STREAM_READ_BATCH_SIZE);
            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }
            List<RecordId> claimIds = new ArrayList<>();
            for (PendingMessage pendingMessage : pendingMessages) {
                if (consumerName.equals(pendingMessage.getConsumerName())) {
                    continue;
                }
                if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(PENDING_IDLE_TIMEOUT) >= 0) {
                    claimIds.add(pendingMessage.getId());
                }
            }
            if (claimIds.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimedRecords = claimRecords(claimIds);
            if (!claimedRecords.isEmpty()) {
                log.info("抢回{}条超时pending订单消息，consumer={}", claimedRecords.size(), consumerName);
                processRecords(claimedRecords);
            }
        } catch (Exception e) {
            log.error("抢回超时pending订单消息失败", e);
        }
    }

    protected void handleNewMessages() {
        List<MapRecord<String, Object, Object>> records = readRecords(ReadOffset.lastConsumed(), STREAM_BLOCK_TIMEOUT);
        processRecords(records);
    }

    protected void processRecords(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            try {
                PendingMessage pendingMessage = findPendingMessage(record.getId());
                if (pendingMessage != null && pendingMessage.getTotalDeliveryCount() > MAX_DELIVERY_COUNT) {
                    writeDeadLetter(record, "max delivery count exceeded: " + pendingMessage.getTotalDeliveryCount());
                    acknowledgeMessage(record);
                    continue;
                }
                if (shouldSkipAndAck(record)) {
                    acknowledgeMessage(record);
                    continue;
                }
                processOrderRecord(record);
                acknowledgeMessage(record);
            } catch (Exception e) {
                log.error("处理订单消息失败，recordId={}", record.getId().getValue(), e);
            }
        }
    }

    protected void processOrderRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Long voucherId = parseLong(value.get("voucherId"), "voucherId");
        Long userId = parseLong(value.get("userId"), "userId");
        Long id = parseLong(value.get("id"), "id");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        IVoucherOrderService orderService = voucherOrderService == null ? this : voucherOrderService;
        orderService.createVoucherOrder(voucherOrder);
        log.info("订单落库成功，orderId={}, userId={}, voucherId={}", id, userId, voucherId);
    }

    protected void writeDeadLetter(MapRecord<String, Object, Object> record, String reason) {
        Map<String, String> deadLetter = new LinkedHashMap<>();
        deadLetter.put("_originalStream", STREAM_KEY);
        deadLetter.put("_originalId", record.getId().getValue());
        deadLetter.put("_reason", reason == null ? "unknown" : reason);
        deadLetter.put("_deadAt", LocalDateTime.now().toString());
        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            deadLetter.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        stringRedisTemplate.opsForStream().add(DEAD_STREAM_KEY, deadLetter);
        log.error("订单消息进入死信队列，recordId={}, reason={}", record.getId().getValue(), reason);
    }

    protected void acknowledgeMessage(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
    }

    protected PendingMessage findPendingMessage(RecordId recordId) {
        PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                .pending(STREAM_KEY, GROUP_NAME, Range.closed(recordId.getValue(), recordId.getValue()), 1);
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return null;
        }
        return pendingMessages.get(0);
    }

    private List<MapRecord<String, Object, Object>> readRecords(ReadOffset readOffset, Duration blockTimeout) {
        try {
            return stringRedisTemplate.opsForStream()
                    .read(Consumer.from(GROUP_NAME, consumerName),
                            StreamReadOptions.empty().count(STREAM_READ_BATCH_SIZE).block(blockTimeout),
                            StreamOffset.create(STREAM_KEY, readOffset));
        } catch (Exception e) {
            if (isNoGroupError(e)) {
                log.warn("读取订单Stream时发现消费者组不存在，尝试重新初始化");
                initializeStreamAndGroup();
            } else {
                log.error("读取订单Stream失败，offset={}", readOffset, e);
            }
            return Collections.emptyList();
        }
    }

    private List<MapRecord<String, Object, Object>> claimRecords(List<RecordId> claimIds) {
        List<ByteRecord> byteRecords = stringRedisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xClaim(
                        raw(STREAM_KEY),
                        GROUP_NAME,
                        consumerName,
                        RedisStreamCommands.XClaimOptions.minIdle(PENDING_IDLE_TIMEOUT)
                                .ids(claimIds.toArray(new RecordId[0]))
                )
        );
        if (byteRecords == null || byteRecords.isEmpty()) {
            return Collections.emptyList();
        }
        List<MapRecord<String, Object, Object>> records = new ArrayList<>(byteRecords.size());
        for (ByteRecord byteRecord : byteRecords) {
            records.add(toStringRecord(byteRecord));
        }
        return records;
    }

    private MapRecord<String, Object, Object> toStringRecord(ByteRecord byteRecord) {
        MapRecord<String, String, String> stringRecord = byteRecord.deserialize(StringRedisSerializer.UTF_8);
        Map<Object, Object> value = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stringRecord.getValue().entrySet()) {
            value.put(entry.getKey(), entry.getValue());
        }
        return MapRecord.create(STREAM_KEY, value).withId(byteRecord.getId());
    }

    private void ensureStreamExists() {
        if (streamExists()) {
            return;
        }
        RecordId messageId = stringRedisTemplate.opsForStream().add(STREAM_KEY, Map.of("init", "true"));
        log.info("创建Redis Stream: {}, 初始消息ID: {}", STREAM_KEY, messageId == null ? null : messageId.getValue());
    }

    private void ensureGroupExists() {
        if (groupExists()) {
            return;
        }
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("创建Redis Stream消费者组: {} for stream: {}", GROUP_NAME, STREAM_KEY);
        } catch (Exception e) {
            if (isBusyGroupError(e)) {
                log.info("Redis Stream消费者组已存在: {}", GROUP_NAME);
                return;
            }
            throw e;
        }
    }

    private boolean streamExists() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(STREAM_KEY));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean groupExists() {
        try {
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(STREAM_KEY);
            if (groups == null || groups.isEmpty()) {
                return false;
            }
            return groups.stream().anyMatch(group -> GROUP_NAME.equals(group.groupName()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldSkipAndAck(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        if (value.containsKey("init")) {
            return true;
        }
        boolean valid = value.containsKey("voucherId") && value.containsKey("userId") && value.containsKey("id");
        if (!valid) {
            log.warn("跳过无效订单消息，recordId={}, value={}", record.getId().getValue(), value);
        }
        return !valid;
    }

    private Long parseLong(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("订单消息缺少字段: " + fieldName);
        }
        return Long.valueOf(value.toString());
    }

    private void validateVoucherOrder(VoucherOrder voucherOrder) {
        if (voucherOrder == null || voucherOrder.getId() == null
                || voucherOrder.getUserId() == null || voucherOrder.getVoucherId() == null) {
            throw new IllegalArgumentException("订单信息不完整");
        }
    }

    private byte[] raw(String value) {
        return StringRedisSerializer.UTF_8.serialize(value);
    }

    private boolean isBusyGroupError(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("BUSYGROUP");
    }

    private boolean isNoGroupError(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("NOGROUP");
    }

    private static String buildConsumerName() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            log.info("订单处理线程启动，consumer={}", consumerName);
            while (running) {
                try {
                    if (!verifyStreamAndGroup() && !initializeStreamAndGroup()) {
                        sleep(Duration.ofSeconds(5));
                        continue;
                    }
                    claimTimeoutPendingMessages();
                    handleCurrentPendingList();
                    handleNewMessages();
                } catch (Exception e) {
                    log.error("订单处理线程异常", e);
                    sleep(Duration.ofSeconds(5));
                }
            }
            log.info("订单处理线程已停止，consumer={}", consumerName);
        }
    }
}

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
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

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
    static final String ORDER_CLOSE_QUEUE = "queue:voucher-order:close";
    static final long ORDER_PAY_TIMEOUT_MINUTES = 15L;
    static final Duration ORDER_CLOSE_POLL_TIMEOUT = Duration.ofSeconds(2);
    static final int EXPIRED_ORDER_SCAN_LIMIT = 100;

    private static final String ORDER_ID_PREFIX = "voucher_order";
    private static final String SECKILL_ORDER_KEY_PREFIX = "seckill:order:";
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_CANCELED = 4;
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

    @Resource
    private RedissonClient redissonClient;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    private final String consumerName = buildConsumerName();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voucher-order-handler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService closeOrderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voucher-order-close-handler");
        t.setDaemon(true);
        return t;
    });

    private RBlockingDeque<Long> orderCloseBlockingDeque;
    private RDelayedQueue<Long> orderCloseDelayedQueue;

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
        if (voucherOrder.getStatus() == null) {
            voucherOrder.setStatus(ORDER_STATUS_UNPAID);
        }
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
        registerAfterCommit(() -> enqueueOrderCloseTask(voucherOrder.getId()));
    }

    @Override
    @Transactional
    public boolean closeUnpaidVoucherOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        VoucherOrder voucherOrder = getOrderById(orderId);
        if (voucherOrder == null) {
            log.warn("待关闭订单不存在，orderId={}", orderId);
            return false;
        }
        if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(voucherOrder.getStatus())) {
            log.info("订单无需关闭，orderId={}, status={}", orderId, voucherOrder.getStatus());
            return false;
        }
        boolean canceled = markUnpaidOrderCanceled(orderId);
        if (!canceled) {
            log.info("订单状态已变化，跳过关闭，orderId={}", orderId);
            return false;
        }
        int restored = seckillVoucherMapper.restoreStock(voucherOrder.getVoucherId());
        if (restored != 1) {
            throw new IllegalStateException("秒杀券库存回补失败，orderId=" + orderId);
        }
        registerAfterCommit(() -> restoreRedisSeckillState(voucherOrder));
        log.info("超时未支付订单已关闭，orderId={}, userId={}, voucherId={}",
                orderId, voucherOrder.getUserId(), voucherOrder.getVoucherId());
        return true;
    }

    @Override
    public int closeExpiredUnpaidVoucherOrders(int limit) {
        int safeLimit = limit <= 0 ? EXPIRED_ORDER_SCAN_LIMIT : Math.min(limit, EXPIRED_ORDER_SCAN_LIMIT);
        List<VoucherOrder> expiredOrders = queryExpiredUnpaidOrders(safeLimit);
        if (expiredOrders == null || expiredOrders.isEmpty()) {
            return 0;
        }
        IVoucherOrderService orderService = voucherOrderService == null ? this : voucherOrderService;
        int closed = 0;
        for (VoucherOrder expiredOrder : expiredOrders) {
            try {
                if (orderService.closeUnpaidVoucherOrder(expiredOrder.getId())) {
                    closed++;
                }
            } catch (Exception e) {
                log.error("补偿关闭超时未支付订单失败，orderId={}", expiredOrder.getId(), e);
            }
        }
        return closed;
    }

    @PostConstruct
    public void init() {
        if (!initializeStreamAndGroup()) {
            log.error("优惠券订单处理服务启动失败，Redis Stream不可用");
            return;
        }
        initializeCloseOrderQueue();
        running = true;
        executor.submit(new VoucherOrderHandler());
        closeOrderExecutor.submit(new VoucherOrderCloseHandler());
        log.info("优惠券订单处理服务启动成功，consumer={}", consumerName);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        shutdownExecutor(closeOrderExecutor, "优惠券订单关闭服务");
        shutdownExecutor(executor, "优惠券订单处理服务");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void compensateExpiredUnpaidVoucherOrders() {
        try {
            IVoucherOrderService orderService = voucherOrderService == null ? this : voucherOrderService;
            int closed = orderService.closeExpiredUnpaidVoucherOrders(EXPIRED_ORDER_SCAN_LIMIT);
            if (closed > 0) {
                log.info("补偿关闭{}笔超时未支付订单", closed);
            }
        } catch (Exception e) {
            log.error("补偿扫描超时未支付订单失败", e);
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

    protected void initializeCloseOrderQueue() {
        if (redissonClient == null) {
            log.warn("RedissonClient未初始化，超时未支付订单关闭队列不可用");
            return;
        }
        try {
            orderCloseBlockingDeque = redissonClient.getBlockingDeque(ORDER_CLOSE_QUEUE);
            orderCloseDelayedQueue = redissonClient.getDelayedQueue(orderCloseBlockingDeque);
            log.info("超时未支付订单关闭队列初始化成功，queue={}", ORDER_CLOSE_QUEUE);
        } catch (Exception e) {
            orderCloseBlockingDeque = null;
            orderCloseDelayedQueue = null;
            log.error("超时未支付订单关闭队列初始化失败", e);
        }
    }

    protected void enqueueOrderCloseTask(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return;
        }
        if (orderCloseDelayedQueue == null) {
            initializeCloseOrderQueue();
        }
        if (orderCloseDelayedQueue == null) {
            log.warn("超时未支付订单关闭队列不可用，跳过延迟任务投递，orderId={}", orderId);
            return;
        }
        try {
            orderCloseDelayedQueue.offer(orderId, ORDER_PAY_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            log.info("已投递订单超时关闭任务，orderId={}, delay={}min", orderId, ORDER_PAY_TIMEOUT_MINUTES);
        } catch (Exception e) {
            log.error("投递订单超时关闭任务失败，orderId={}", orderId, e);
        }
    }

    protected Long pollOrderCloseTask(Duration timeout) throws InterruptedException {
        if (orderCloseBlockingDeque == null) {
            initializeCloseOrderQueue();
        }
        if (orderCloseBlockingDeque == null) {
            sleep(Duration.ofSeconds(5));
            return null;
        }
        return orderCloseBlockingDeque.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    protected VoucherOrder getOrderById(Long orderId) {
        return getById(orderId);
    }

    protected boolean markUnpaidOrderCanceled(Long orderId) {
        return update()
                .set("status", ORDER_STATUS_CANCELED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
    }

    protected List<VoucherOrder> queryExpiredUnpaidOrders(int limit) {
        return query()
                .select("id")
                .eq("status", ORDER_STATUS_UNPAID)
                .le("create_time", LocalDateTime.now().minusMinutes(ORDER_PAY_TIMEOUT_MINUTES))
                .orderByAsc("create_time")
                .last("LIMIT " + limit)
                .list();
    }

    protected void restoreRedisSeckillState(VoucherOrder voucherOrder) {
        if (voucherOrder == null || voucherOrder.getVoucherId() == null || voucherOrder.getUserId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherOrder.getVoucherId());
            stringRedisTemplate.opsForSet()
                    .remove(SECKILL_ORDER_KEY_PREFIX + voucherOrder.getVoucherId(), voucherOrder.getUserId().toString());
            log.info("已回补Redis秒杀状态，orderId={}, voucherId={}, userId={}",
                    voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
        } catch (Exception e) {
            log.error("回补Redis秒杀状态失败，orderId={}, voucherId={}, userId={}",
                    voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), e);
        }
    }

    protected void registerAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
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

    private void shutdownExecutor(ExecutorService targetExecutor, String name) {
        try {
            targetExecutor.shutdown();
            if (!targetExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                targetExecutor.shutdownNow();
            }
            log.info("{}已停止，consumer={}", name, consumerName);
        } catch (InterruptedException e) {
            targetExecutor.shutdownNow();
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

    private class VoucherOrderCloseHandler implements Runnable {
        @Override
        public void run() {
            log.info("订单超时关闭线程启动，consumer={}", consumerName);
            while (running) {
                try {
                    Long orderId = pollOrderCloseTask(ORDER_CLOSE_POLL_TIMEOUT);
                    if (orderId == null) {
                        continue;
                    }
                    IVoucherOrderService orderService = voucherOrderService == null
                            ? VoucherOrderServiceImpl.this
                            : voucherOrderService;
                    orderService.closeUnpaidVoucherOrder(orderId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (running) {
                        log.warn("订单超时关闭线程被中断", e);
                    }
                    break;
                } catch (Exception e) {
                    log.error("处理订单超时关闭任务失败", e);
                    sleep(Duration.ofSeconds(2));
                }
            }
            log.info("订单超时关闭线程已停止，consumer={}", consumerName);
        }
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.dev33.satoken.exception.NotLoginException;
import com.hmdp.common.ErrorCode;
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
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.transaction.interceptor.TransactionAspectSupport;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
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
    static final long DEFAULT_ORDER_PAY_TIMEOUT_MINUTES = 15L;
    static final Duration ORDER_CLOSE_POLL_TIMEOUT = Duration.ofSeconds(2);
    static final int DEFAULT_EXPIRED_ORDER_SCAN_LIMIT = 100;
    static final String REDIS_COMPENSATION_KEY_PREFIX = "seckill:compensated:";
    static final String REDIS_COMPENSATION_FAILED_SET_KEY = "seckill:compensation:failed:orders";
    static final String REDIS_COMPENSATION_FAILED_HASH_PREFIX = "seckill:compensation:failed:order:";

    private static final String ORDER_ID_PREFIX = "voucher_order";
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_PAID = 2;
    private static final int ORDER_STATUS_CANCELED = 4;
    private static final int ORDER_STATUS_REFUNDED = 6;
    private static final int ACTIVE_ORDER_KEY = 1;
    private static final long REDIS_COMPENSATION_MARK_TTL_SECONDS = 7 * 24 * 60 * 60L;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_REDIS_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        COMPENSATE_REDIS_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_REDIS_SCRIPT.setScriptText(
                "if redis.call('exists', KEYS[3]) == 1 then return 1; end; " +
                        "redis.call('incrby', KEYS[1], 1); " +
                        "if ARGV[3] == '1' then " +
                        "  redis.call('srem', KEYS[2], ARGV[2]); " +
                        "else " +
                        "  redis.call('sadd', KEYS[2], ARGV[2]); " +
                        "end; " +
                        "redis.call('set', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[4])); " +
                        "return 1");
        COMPENSATE_REDIS_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource(name = "businessRedissonClient")
    private RedissonClient redissonClient;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    private final String consumerName = buildConsumerName();
    private ExecutorService executor;
    private ExecutorService closeOrderExecutor;

    private RBlockingDeque<Long> orderCloseBlockingDeque;
    private RDelayedQueue<Long> orderCloseDelayedQueue;

    @Value("${hmdp.voucher.order.worker-threads:2}")
    private int workerThreads = 2;

    @Value("${hmdp.voucher.order.close-worker-threads:1}")
    private int closeWorkerThreads = 1;

    @Value("${hmdp.voucher.order.stream-required:true}")
    private boolean streamRequired = true;

    @Value("${hmdp.voucher.order.stream-health-check-enabled:true}")
    private boolean streamHealthCheckEnabled = true;

    @Value("${hmdp.voucher.order.pay-timeout-minutes:15}")
    private long orderPayTimeoutMinutes = DEFAULT_ORDER_PAY_TIMEOUT_MINUTES;

    @Value("${hmdp.voucher.order.close-scan-limit:100}")
    private int expiredOrderScanLimit = DEFAULT_EXPIRED_ORDER_SCAN_LIMIT;

    private volatile boolean running = false;
    private volatile boolean streamReady = false;
    private volatile boolean consumersStarted = false;
    private volatile boolean shuttingDown = false;

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID无效");
        }
        if (streamRequired && !isOrderServiceReady()) {
            log.warn("秒杀订单Stream未就绪，拒绝接单，voucherId={}, health={}", voucherId, getOrderConsumerHealth());
            return Result.fail("订单服务暂不可用，请稍后重试");
        }
        if (!streamRequired && !isOrderServiceReady()) {
            log.warn("秒杀订单Stream未就绪但stream-required=false，继续执行Lua存在订单消息丢失风险，voucherId={}", voucherId);
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
            if (resultCode == 4) {
                return Result.fail("秒杀活动尚未开始");
            }
            if (resultCode == 5) {
                return Result.fail("秒杀活动已结束");
            }
            log.warn("秒杀Lua脚本返回未知结果，result={}, voucherId={}, userId={}", result, voucherId, userId);
            return Result.fail("秒杀失败，请稍后重试");
        } catch (NotLoginException e) {
            return Result.fail(ErrorCode.UNAUTHORIZED, "请先登录");
        } catch (Exception e) {
            log.error("秒杀处理失败，voucherId={}", voucherId, e);
            return Result.fail("秒杀失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public Result payVoucherOrder(Long orderId, String payRequestId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "orderId is invalid");
        }
        String safePayRequestId = normalizePayRequestId(payRequestId);
        if (safePayRequestId == null) {
            return Result.fail(ErrorCode.PARAM_ERROR, "payRequestId is required");
        }
        try {
            Long userId = currentUserService.requireCurrentUserId();
            VoucherOrder voucherOrder = getOrderById(orderId);
            if (voucherOrder == null) {
                return Result.fail("order not found");
            }
            if (!userId.equals(voucherOrder.getUserId())) {
                return Result.fail("no permission to pay this order");
            }
            Integer status = voucherOrder.getStatus();
            if (Integer.valueOf(ORDER_STATUS_PAID).equals(status)) {
                if (safePayRequestId.equals(voucherOrder.getPayRequestId())) {
                    return Result.ok(orderId);
                }
                return Result.fail("order already paid");
            }
            if (Integer.valueOf(ORDER_STATUS_CANCELED).equals(status)) {
                return Result.fail("order canceled");
            }
            if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(status)) {
                return Result.fail("order status cannot be paid");
            }
            if (isPaymentExpired(voucherOrder)) {
                closeUnpaidVoucherOrder(orderId);
                return Result.fail("order payment window expired");
            }
            LocalDateTime paymentCutoff = LocalDateTime.now().minusMinutes(effectiveOrderPayTimeoutMinutes());
            boolean paid = markUnpaidOrderPaid(orderId, userId, safePayRequestId, paymentCutoff);
            if (!paid) {
                VoucherOrder latestOrder = getOrderByIdForUpdate(orderId);
                if (latestOrder != null && Integer.valueOf(ORDER_STATUS_PAID).equals(latestOrder.getStatus())) {
                    if (safePayRequestId.equals(latestOrder.getPayRequestId())) {
                        return Result.ok(orderId);
                    }
                    return Result.fail("order already paid");
                }
                return Result.fail("order status changed, please refresh and retry");
            }
            log.info("Mock voucher order payment succeeded, orderId={}, userId={}, voucherId={}, payRequestId={}",
                    orderId, userId, voucherOrder.getVoucherId(), safePayRequestId);
            return Result.ok(orderId);
        } catch (Exception e) {
            markCurrentTransactionRollbackOnly();
            log.error("Mock voucher order payment failed, orderId={}", orderId, e);
            return Result.fail("payment failed, please retry later");
        }
    }
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        validateVoucherOrder(voucherOrder);
        if (voucherOrder.getStatus() == null) {
            voucherOrder.setStatus(ORDER_STATUS_UNPAID);
        }
        if (voucherOrder.getActiveOrderKey() == null && isActiveOrderStatus(voucherOrder.getStatus())) {
            voucherOrder.setActiveOrderKey(ACTIVE_ORDER_KEY);
        }
        try {
            boolean saved = save(voucherOrder);
            if (!saved) {
                throw new IllegalStateException("订单保存失败");
            }
        } catch (DuplicateKeyException e) {
            handleDuplicateVoucherOrder(voucherOrder);
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
        registerAfterCommit(() -> {
            try {
                if (!restoreRedisSeckillState(voucherOrder)) {
                    recordRedisCompensationFailure(voucherOrder, "close-unpaid-after-commit");
                }
            } catch (Exception e) {
                log.error("Redis compensation after close failed, orderId={}", voucherOrder.getId(), e);
                recordRedisCompensationFailure(voucherOrder, "close-unpaid-after-commit-exception");
            }
        });
        log.info("超时未支付订单已关闭，orderId={}, userId={}, voucherId={}",
                orderId, voucherOrder.getUserId(), voucherOrder.getVoucherId());
        return true;
    }

    @Override
    public int closeExpiredUnpaidVoucherOrders(int limit) {
        int configuredLimit = effectiveExpiredOrderScanLimit();
        int safeLimit = limit <= 0 ? configuredLimit : Math.min(limit, configuredLimit);
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
        shuttingDown = false;
        if (!initializeStreamAndGroup()) {
            log.error("优惠券订单处理服务启动失败，Redis Stream不可用");
            return;
        }
        try {
            startConsumersIfNeeded();
        } catch (RuntimeException e) {
            log.error("优惠券订单消费者启动失败，将等待健康检查重试", e);
        }
    }

    protected synchronized void startConsumersIfNeeded() {
        if (shuttingDown || consumersStarted) {
            return;
        }
        try {
            initializeCloseOrderQueue();
            executor = createExecutor("voucher-order-handler", effectiveWorkerThreads());
            closeOrderExecutor = createExecutor("voucher-order-close-handler", effectiveCloseWorkerThreads());
            running = true;
            for (int i = 0; i < effectiveWorkerThreads(); i++) {
                submitConsumerLoop(executor, new VoucherOrderHandler(i + 1));
            }
            for (int i = 0; i < effectiveCloseWorkerThreads(); i++) {
                submitConsumerLoop(closeOrderExecutor, new VoucherOrderCloseHandler(i + 1));
            }
            consumersStarted = true;
        } catch (RuntimeException e) {
            rollbackConsumerStartup();
            throw e;
        }
        log.info("优惠券订单处理服务启动成功，consumer={}, workerThreads={}, closeWorkerThreads={}",
                consumerName, effectiveWorkerThreads(), effectiveCloseWorkerThreads());
    }

    private void rollbackConsumerStartup() {
        running = false;
        consumersStarted = false;
        shutdownExecutor(closeOrderExecutor, "优惠券订单关闭服务启动回滚");
        shutdownExecutor(executor, "优惠券订单处理服务启动回滚");
        closeOrderExecutor = null;
        executor = null;
    }

    @PreDestroy
    public synchronized void destroy() {
        shuttingDown = true;
        running = false;
        consumersStarted = false;
        shutdownExecutor(closeOrderExecutor, "优惠券订单关闭服务");
        shutdownExecutor(executor, "优惠券订单处理服务");
    }

    @Scheduled(
            fixedDelayString = "${hmdp.voucher.order.close-scan-fixed-delay-millis:60000}",
            initialDelayString = "${hmdp.voucher.order.close-scan-initial-delay-millis:60000}"
    )
    public void compensateExpiredUnpaidVoucherOrders() {
        try {
            IVoucherOrderService orderService = voucherOrderService == null ? this : voucherOrderService;
            int closed = orderService.closeExpiredUnpaidVoucherOrders(effectiveExpiredOrderScanLimit());
            if (closed > 0) {
                log.info("补偿关闭{}笔超时未支付订单", closed);
            }
        } catch (Exception e) {
            log.error("补偿扫描超时未支付订单失败", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${hmdp.voucher.order.redis-compensation-retry-fixed-delay-millis:60000}",
            initialDelayString = "${hmdp.voucher.order.redis-compensation-retry-initial-delay-millis:60000}"
    )
    public void retryFailedRedisCompensations() {
        try {
            Set<String> orderIds = stringRedisTemplate.opsForSet().members(REDIS_COMPENSATION_FAILED_SET_KEY);
            if (orderIds == null || orderIds.isEmpty()) {
                return;
            }
            for (String orderId : orderIds) {
                retryFailedRedisCompensation(orderId);
            }
        } catch (Exception e) {
            log.error("Retry Redis compensation failures failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.voucher.order.stream-health-check-fixed-delay-millis:30000}",
            initialDelayString = "${hmdp.voucher.order.stream-health-check-initial-delay-millis:30000}")
    public void refreshOrderStreamHealth() {
        if (!streamHealthCheckEnabled) {
            return;
        }
        try {
            boolean ready = verifyStreamAndGroup() || initializeStreamAndGroup();
            streamReady = ready;
            if (ready) {
                startConsumersIfNeeded();
            } else {
                log.warn("秒杀订单Stream健康检查失败，health={}", getOrderConsumerHealth());
            }
        } catch (Exception e) {
            streamReady = false;
            log.warn("秒杀订单Stream健康检查异常", e);
        }
    }

    protected boolean initializeStreamAndGroup() {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                ensureStreamExists();
                ensureGroupExists();
                streamReady = true;
                return true;
            } catch (Exception e) {
                streamReady = false;
                log.error("初始化Redis Stream失败，第{}次尝试", i + 1, e);
                if (i < maxRetries - 1) {
                    sleep(Duration.ofSeconds(2));
                }
            }
        }
        streamReady = false;
        return false;
    }

    protected boolean verifyStreamAndGroup() {
        boolean ready = streamExists() && groupExists();
        streamReady = ready;
        return ready;
    }

    public boolean isOrderStreamReady() {
        return streamReady;
    }

    public boolean isOrderServiceReady() {
        return streamReady
                && running
                && consumersStarted
                && executor != null
                && !executor.isShutdown();
    }

    public Map<String, Object> getOrderConsumerHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("streamReady", streamReady);
        health.put("streamRequired", streamRequired);
        health.put("workerThreads", effectiveWorkerThreads());
        health.put("closeWorkerThreads", effectiveCloseWorkerThreads());
        health.put("running", running);
        health.put("consumersStarted", consumersStarted);
        health.put("consumerName", consumerName);
        return health;
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
                    if (moveToDeadLetterAfterCompensation(record,
                            "max delivery count exceeded: " + pendingMessage.getTotalDeliveryCount())) {
                        acknowledgeMessage(record);
                    }
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
            long timeoutMinutes = effectiveOrderPayTimeoutMinutes();
            orderCloseDelayedQueue.offer(orderId, timeoutMinutes, TimeUnit.MINUTES);
            log.info("已投递订单超时关闭任务，orderId={}, delay={}min", orderId, timeoutMinutes);
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

    protected VoucherOrder getOrderByIdForUpdate(Long orderId) {
        return query()
                .eq("id", orderId)
                .last("FOR UPDATE")
                .one();
    }

    protected VoucherOrder getActiveOrder(Long userId, Long voucherId) {
        return query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .eq("active_order_key", ACTIVE_ORDER_KEY)
                .last("LIMIT 1")
                .one();
    }

    protected void handleDuplicateVoucherOrder(VoucherOrder voucherOrder) {
        VoucherOrder persistedOrder = getOrderById(voucherOrder.getId());
        if (persistedOrder != null) {
            log.info("订单消息重复投递，按幂等成功处理，orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return;
        }
        VoucherOrder activeOrder = getActiveOrder(voucherOrder.getUserId(), voucherOrder.getVoucherId());
        boolean compensated = compensateRedisPreDeduct(voucherOrder, false);
        log.warn("用户已有活跃秒杀订单，已回补本次预扣库存，orderId={}, activeOrderId={}, userId={}, voucherId={}, compensated={}",
                voucherOrder.getId(), activeOrder == null ? null : activeOrder.getId(),
                voucherOrder.getUserId(), voucherOrder.getVoucherId(), compensated);
        if (!compensated) {
            throw new IllegalStateException("Redis pre-deduct compensation failed");
        }
    }

    protected boolean markUnpaidOrderCanceled(Long orderId) {
        return update()
                .set("status", ORDER_STATUS_CANCELED)
                .set("active_order_key", (Object) null)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
    }

    protected boolean markUnpaidOrderPaid(Long orderId, Long userId, String payRequestId, LocalDateTime paymentCutoff) {
        return update()
                .set("status", ORDER_STATUS_PAID)
                .set("pay_time", LocalDateTime.now())
                .set("pay_request_id", payRequestId)
                .set("active_order_key", ACTIVE_ORDER_KEY)
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", ORDER_STATUS_UNPAID)
                .gt(paymentCutoff != null, "create_time", paymentCutoff)
                .update();
    }

    protected List<VoucherOrder> queryExpiredUnpaidOrders(int limit) {
        return query()
                .select("id")
                .eq("status", ORDER_STATUS_UNPAID)
                .le("create_time", LocalDateTime.now().minusMinutes(effectiveOrderPayTimeoutMinutes()))
                .orderByAsc("create_time")
                .last("LIMIT " + limit)
                .list();
    }

    protected boolean isPaymentExpired(VoucherOrder voucherOrder) {
        LocalDateTime createTime = voucherOrder == null ? null : voucherOrder.getCreateTime();
        return createTime != null && !createTime.isAfter(LocalDateTime.now().minusMinutes(effectiveOrderPayTimeoutMinutes()));
    }

    protected boolean moveToDeadLetterAfterCompensation(MapRecord<String, Object, Object> record, String reason) {
        VoucherOrder voucherOrder = toVoucherOrder(record);
        if (voucherOrder == null) {
            writeDeadLetter(record, reason);
            return true;
        }
        try {
            if (getOrderById(voucherOrder.getId()) == null) {
                VoucherOrder activeOrder = getActiveOrder(voucherOrder.getUserId(), voucherOrder.getVoucherId());
                boolean compensated = activeOrder == null
                        ? restoreRedisSeckillState(voucherOrder)
                        : compensateRedisPreDeduct(voucherOrder, false);
                if (!compensated) {
                    return false;
                }
            }
            writeDeadLetter(record, reason);
            return true;
        } catch (Exception e) {
            log.error("死信前确认订单状态失败，recordId={}", record.getId().getValue(), e);
            return false;
        }
    }

    protected VoucherOrder toVoucherOrder(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(parseLong(value.get("id"), "id"));
            voucherOrder.setUserId(parseLong(value.get("userId"), "userId"));
            voucherOrder.setVoucherId(parseLong(value.get("voucherId"), "voucherId"));
            return voucherOrder;
        } catch (Exception e) {
            log.warn("无法解析订单死信消息，recordId={}", record.getId().getValue(), e);
            return null;
        }
    }

    protected boolean restoreRedisSeckillState(VoucherOrder voucherOrder) {
        return compensateRedisPreDeduct(voucherOrder, true);
    }

    protected boolean compensateRedisPreDeduct(VoucherOrder voucherOrder, boolean releaseUserEligibility) {
        if (voucherOrder == null || voucherOrder.getId() == null
                || voucherOrder.getVoucherId() == null || voucherOrder.getUserId() == null) {
            return false;
        }
        try {
            Long result = stringRedisTemplate.execute(
                    COMPENSATE_REDIS_SCRIPT,
                    List.of(SECKILL_STOCK_KEY + voucherOrder.getVoucherId(),
                            SECKILL_ORDER_KEY + voucherOrder.getVoucherId(),
                            redisCompensationKey(voucherOrder.getId())),
                    voucherOrder.getId().toString(),
                    voucherOrder.getUserId().toString(),
                    releaseUserEligibility ? "1" : "0",
                    Long.toString(REDIS_COMPENSATION_MARK_TTL_SECONDS));
            if (!Long.valueOf(1L).equals(result)) {
                log.error("Redis秒杀状态回补结果异常，orderId={}, voucherId={}, userId={}, result={}",
                        voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), result);
                return false;
            }
            log.info("已回补Redis秒杀状态，orderId={}, voucherId={}, userId={}",
                    voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId());
            return true;
        } catch (Exception e) {
            log.error("回补Redis秒杀状态失败，orderId={}, voucherId={}, userId={}",
                    voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), e);
            return false;
        }
    }

    protected void recordRedisCompensationFailure(VoucherOrder voucherOrder, String reason) {
        if (voucherOrder == null || voucherOrder.getId() == null) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("orderId", String.valueOf(voucherOrder.getId()));
        payload.put("userId", String.valueOf(voucherOrder.getUserId()));
        payload.put("voucherId", String.valueOf(voucherOrder.getVoucherId()));
        payload.put("releaseUserEligibility", "1");
        payload.put("reason", reason == null ? "unknown" : reason);
        payload.put("failedAt", LocalDateTime.now().toString());
        try {
            String orderId = String.valueOf(voucherOrder.getId());
            stringRedisTemplate.opsForSet().add(REDIS_COMPENSATION_FAILED_SET_KEY, orderId);
            stringRedisTemplate.opsForHash().putAll(redisCompensationFailureHashKey(orderId), payload);
        } catch (Exception e) {
            log.error("Record Redis compensation failure failed, orderId={}", voucherOrder.getId(), e);
        }
    }

    protected String redisCompensationKey(Long orderId) {
        return REDIS_COMPENSATION_KEY_PREFIX + orderId;
    }

    protected void retryFailedRedisCompensation(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            return;
        }
        String safeOrderId = orderId.trim();
        Map<Object, Object> payload = stringRedisTemplate.opsForHash()
                .entries(redisCompensationFailureHashKey(safeOrderId));
        VoucherOrder voucherOrder = toFailedCompensationOrder(safeOrderId, payload);
        if (voucherOrder == null) {
            stringRedisTemplate.opsForSet().remove(REDIS_COMPENSATION_FAILED_SET_KEY, safeOrderId);
            stringRedisTemplate.delete(redisCompensationFailureHashKey(safeOrderId));
            return;
        }
        boolean releaseUserEligibility = shouldReleaseUserEligibility(payload);
        if (compensateRedisPreDeduct(voucherOrder, releaseUserEligibility)) {
            stringRedisTemplate.opsForSet().remove(REDIS_COMPENSATION_FAILED_SET_KEY, safeOrderId);
            stringRedisTemplate.delete(redisCompensationFailureHashKey(safeOrderId));
            log.info("Retried Redis compensation succeeded, orderId={}", safeOrderId);
        }
    }

    private boolean shouldReleaseUserEligibility(Map<Object, Object> payload) {
        if (payload == null) {
            return true;
        }
        Object value = payload.get("releaseUserEligibility");
        if (value == null) {
            return true;
        }
        String normalized = String.valueOf(value).trim();
        return !("0".equals(normalized) || "false".equalsIgnoreCase(normalized));
    }

    private VoucherOrder toFailedCompensationOrder(String orderId, Map<Object, Object> payload) {
        try {
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(Long.valueOf(orderId));
            voucherOrder.setUserId(Long.valueOf(String.valueOf(payload.get("userId"))));
            voucherOrder.setVoucherId(Long.valueOf(String.valueOf(payload.get("voucherId"))));
            return voucherOrder;
        } catch (Exception e) {
            log.warn("Invalid Redis compensation failure payload, orderId={}, payload={}", orderId, payload);
            return null;
        }
    }

    private String redisCompensationFailureHashKey(String orderId) {
        return REDIS_COMPENSATION_FAILED_HASH_PREFIX + orderId;
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

    private void markCurrentTransactionRollbackOnly() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException e) {
            log.warn("Unable to mark current voucher order transaction rollback-only", e);
        }
    }

    private long effectiveOrderPayTimeoutMinutes() {
        return orderPayTimeoutMinutes <= 0 ? DEFAULT_ORDER_PAY_TIMEOUT_MINUTES : orderPayTimeoutMinutes;
    }

    private int effectiveExpiredOrderScanLimit() {
        return expiredOrderScanLimit <= 0 ? DEFAULT_EXPIRED_ORDER_SCAN_LIMIT : expiredOrderScanLimit;
    }

    private int effectiveWorkerThreads() {
        return boundedThreadCount(workerThreads);
    }

    private int effectiveCloseWorkerThreads() {
        return boundedThreadCount(closeWorkerThreads);
    }

    private boolean isActiveOrderStatus(Integer status) {
        return status != null
                && !Integer.valueOf(ORDER_STATUS_CANCELED).equals(status)
                && !Integer.valueOf(ORDER_STATUS_REFUNDED).equals(status);
    }

    private String normalizePayRequestId(String payRequestId) {
        if (payRequestId == null) {
            return null;
        }
        String normalized = payRequestId.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            return null;
        }
        return normalized;
    }

    private int boundedThreadCount(int configured) {
        return Math.min(16, Math.max(1, configured));
    }

    protected ExecutorService createExecutor(String threadNamePrefix, int threads) {
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, threadNamePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
    }

    protected void submitConsumerLoop(ExecutorService targetExecutor, Runnable task) {
        targetExecutor.submit(task);
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
        stringRedisTemplate.opsForStream().delete(STREAM_KEY, record.getId());
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
        if (targetExecutor == null) {
            return;
        }
        try {
            targetExecutor.shutdown();
            if (!targetExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                targetExecutor.shutdownNow();
            }
            log.info("{}已停止，consumer={}", name, consumerName);
        } catch (InterruptedException e) {
            targetExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("关闭{}失败，consumer={}", name, consumerName, e);
        }
    }

    private class VoucherOrderHandler implements Runnable {
        private final int index;

        private VoucherOrderHandler(int index) {
            this.index = index;
        }

        @Override
        public void run() {
            log.info("订单处理线程启动，consumer={}, index={}", consumerName, index);
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
            log.info("订单处理线程已停止，consumer={}, index={}", consumerName, index);
        }
    }

    private class VoucherOrderCloseHandler implements Runnable {
        private final int index;

        private VoucherOrderCloseHandler(int index) {
            this.index = index;
        }

        @Override
        public void run() {
            log.info("订单超时关闭线程启动，consumer={}, index={}", consumerName, index);
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
            log.info("订单超时关闭线程已停止，consumer={}, index={}", consumerName, index);
        }
    }
}

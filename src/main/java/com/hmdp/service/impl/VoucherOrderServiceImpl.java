package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    private ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voucher-order-handler");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 实现秒杀逻辑
        try {
            log.info("开始处理秒杀请求，优惠券ID: {}", voucherId);

            // 这里添加你的秒杀逻辑
            // 例如：
            // 1. 检查优惠券是否存在
            // 2. 检查库存
            // 3. 检查用户是否已经购买过
            // 4. 创建订单

            // 临时返回成功，你需要根据实际业务逻辑修改
            return Result.ok("秒杀成功");

        } catch (Exception e) {
            log.error("秒杀处理失败，优惠券ID: {}", voucherId, e);
            return Result.fail("秒杀失败");
        }
    }

    @PostConstruct
    public void init() {
        try {
            // 初始化Redis Stream和消费者组
            initializeStreamAndGroup();
            // 启动订单处理线程
            running = true;
            executor.submit(new VoucherOrderHandler());
            log.info("优惠券订单处理服务启动成功");
        } catch (Exception e) {
            log.error("优惠券订单处理服务启动失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                log.info("优惠券订单处理服务已停止");
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 初始化Redis Stream和消费者组
     */
    private void initializeStreamAndGroup() {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                // 检查Stream是否存在
                if (!streamExists()) {
                    // 创建Stream
                    RecordId messageId = stringRedisTemplate.opsForStream()
                            .add(STREAM_KEY, Map.of("init", "true"));
                    log.info("创建Redis Stream: {}, 初始消息ID: {}", STREAM_KEY, messageId.getValue());
                }

                // 创建消费者组
                try {
                    stringRedisTemplate.opsForStream()
                            .createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
                    log.info("创建消费者组: {} for stream: {}", GROUP_NAME, STREAM_KEY);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                        log.info("消费者组已存在: {}", GROUP_NAME);
                    } else {
                        log.warn("创建消费者组失败，尝试删除并重新创建: {}", e.getMessage());
                        try {
                            stringRedisTemplate.opsForStream().destroyGroup(STREAM_KEY, GROUP_NAME);
                        } catch (Exception ignored) {
                        }
                        stringRedisTemplate.opsForStream()
                                .createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
                        log.info("重新创建消费者组成功: {}", GROUP_NAME);
                    }
                }

                if (verifyStreamAndGroup()) {
                    log.info("Redis Stream和消费者组验证成功");
                    return;
                } else {
                    throw new RuntimeException("验证失败");
                }

            } catch (Exception e) {
                log.error("初始化Redis Stream失败，第{}次尝试", i + 1, e);
                if (i == maxRetries - 1) {
                    log.error("初始化Redis Stream彻底失败，将禁用Stream功能");
                    running = false;
                    return;
                } else {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private boolean streamExists() {
        try {
            Long length = stringRedisTemplate.opsForStream().size(STREAM_KEY);
            return length != null && length >= 0;
        } catch (Exception e) {
            log.debug("检查Stream存在性失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean verifyStreamAndGroup() {
        try {
            if (!streamExists()) {
                log.warn("Stream验证失败: {} 不存在", STREAM_KEY);
                return false;
            }

            try {
                stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofMillis(100)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                return true;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    log.warn("消费者组验证失败: {} 不存在", GROUP_NAME);
                    return false;
                } else {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("验证Stream和消费者组失败", e);
            return false;
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            log.info("订单处理线程启动");

            while (running) {
                try {
                    if (!verifyStreamAndGroup()) {
                        log.warn("Stream或消费者组不存在，尝试重新初始化");
                        initializeStreamAndGroup();
                        if (!running) {
                            break;
                        }
                    }

                    handlePendingList();
                    handleNewMessages();
                    Thread.sleep(5000);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("订单处理线程被中断");
                    break;
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("订单处理线程已停止");
        }

        private void handlePendingList() {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                        .read(Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.from("0")));

                if (records != null && !records.isEmpty()) {
                    log.debug("处理{}条pending消息", records.size());
                    processRecords(records);
                }
            } catch (Exception e) {
                handleStreamError("处理pending消息", e);
            }
        }

        private void handleNewMessages() {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                        .read(Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

                if (records != null && !records.isEmpty()) {
                    log.debug("处理{}条新消息", records.size());
                    processRecords(records);
                }
            } catch (Exception e) {
                handleStreamError("处理新消息", e);
            }
        }

        private void handleStreamError(String operation, Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                log.warn("{}时发现消费者组不存在，标记需要重新初始化", operation);
            } else if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                log.debug("{}超时，这是正常的", operation);
            } else {
                log.error("{}失败", operation, e);
            }
        }

        private void processRecords(List<MapRecord<String, Object, Object>> records) {
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    Map<Object, Object> value = record.getValue();

                    if (value.containsKey("init")) {
                        acknowledgeMessage(record);
                        continue;
                    }

                    if (isValidOrderRecord(record)) {
                        processOrderRecord(record);
                    } else {
                        log.warn("跳过无效订单记录: {}", record.getId().getValue());
                    }

                    acknowledgeMessage(record);

                } catch (Exception e) {
                    log.error("处理订单记录失败: {}", record.getId().getValue(), e);
                }
            }
        }

        private boolean isValidOrderRecord(MapRecord<String, Object, Object> record) {
            Map<Object, Object> value = record.getValue();
            return value.containsKey("voucherId") &&
                    value.containsKey("userId") &&
                    value.containsKey("id");
        }

        private void processOrderRecord(MapRecord<String, Object, Object> record) {
            Map<Object, Object> value = record.getValue();
            log.info("处理订单: {}", value);

            // 这里可以调用创建订单的逻辑
            try {
                Long voucherId = Long.valueOf(value.get("voucherId").toString());
                Long userId = Long.valueOf(value.get("userId").toString());
                Long id = Long.valueOf(value.get("id").toString());

                // 创建VoucherOrder对象
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(id);
                voucherOrder.setUserId(userId);
                voucherOrder.setVoucherId(voucherId);

                // 保存到数据库
                save(voucherOrder);
                log.info("订单保存成功: {}", id);

            } catch (Exception e) {
                log.error("处理订单数据失败: {}", value, e);
            }
        }

        private void acknowledgeMessage(MapRecord<String, Object, Object> record) {
            try {
                RecordId recordId = record.getId();
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, recordId);
                log.debug("确认消息: {}", recordId.getValue());
            } catch (Exception e) {
                log.error("确认消息失败: {}", record.getId().getValue(), e);
            }
        }
    }
}
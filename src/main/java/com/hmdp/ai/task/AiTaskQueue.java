package com.hmdp.ai.task;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Redisson RBlockingQueue 基于 Redis 持久化队列，重启不丢队列项，多实例 take 可自然分担。
 * RAG 重建幂等，Phase 1 允许崩溃时丢失单次处理中任务；如需 at-least-once 与重投递，可在 Phase 2 升级为 RStream 消费者组。
 */
@Component
public class AiTaskQueue {

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    @Value("${hmdp.ai.task.queue-key:hmdp:ai:task:queue}")
    private String queueKey;

    public void enqueue(String taskId) throws InterruptedException {
        queue().put(taskId);
    }

    public String take() throws InterruptedException {
        return queue().take();
    }

    private RBlockingQueue<String> queue() {
        return redissonClient.getBlockingQueue(queueKey);
    }
}

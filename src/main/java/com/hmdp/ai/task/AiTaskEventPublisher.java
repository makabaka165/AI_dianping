package com.hmdp.ai.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.AiTaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class AiTaskEventPublisher {

    static final String TOPIC_PREFIX = "hmdp:ai:task:events:";

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publish(AiTaskEvent event) {
        if (event == null || event.getTaskId() == null || event.getTaskId().trim().isEmpty()) {
            return;
        }
        try {
            redissonClient.getTopic(topicName(event.getTaskId()))
                    .publish(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("Publish AI task event failed, taskId={}", event.getTaskId(), e);
        }
    }

    static String topicName(String taskId) {
        return TOPIC_PREFIX + taskId;
    }
}

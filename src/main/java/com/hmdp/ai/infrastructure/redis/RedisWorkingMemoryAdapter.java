package com.hmdp.ai.infrastructure.redis;

import com.hmdp.ai.domain.memory.WorkingMemoryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisWorkingMemoryAdapter implements WorkingMemoryPort {
    private final StringRedisTemplate redis;
    public RedisWorkingMemoryAdapter(@Qualifier("stringRedisTemplate") StringRedisTemplate redis){this.redis=redis;}
    @Override public void put(String tenant,String workspace,String runId,String snapshot,Duration ttl){
        redis.opsForValue().set(key(tenant,workspace,runId),snapshot,ttl);}
    @Override public void delete(String tenant,String workspace,String runId){redis.delete(key(tenant,workspace,runId));}
    private String key(String tenant,String workspace,String runId){return "hmdp:ai:working:"+tenant+":"+workspace+":"+runId;}
}

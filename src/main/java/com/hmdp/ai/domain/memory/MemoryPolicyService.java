package com.hmdp.ai.domain.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

@Service
public class MemoryPolicyService {
    private final EnumMap<MemoryType, MemoryPolicy> policies = new EnumMap<>(MemoryType.class);
    @Autowired public MemoryPolicyService(Environment environment) { this(defaultTtls(environment)); }
    public MemoryPolicyService(Map<MemoryType, Long> ttlSeconds) {
        for (MemoryType type : MemoryType.values()) {
            long ttl = ttlSeconds.getOrDefault(type, 7200L);
            boolean persistent = type == MemoryType.CONVERSATION || type == MemoryType.EPISODIC_MEMORY || type == MemoryType.LONG_TERM_FACT;
            policies.put(type, new MemoryPolicy(type, ttl, persistent));
        }
    }
    public MemoryPolicy policyFor(MemoryScope scope) { return policies.get(scope.getMemoryType()); }
    public long ttlSeconds(MemoryScope scope) { return policyFor(scope).getTtlSeconds(); }
    private static Map<MemoryType, Long> defaultTtls(Environment env) {
        EnumMap<MemoryType, Long> values = new EnumMap<>(MemoryType.class);
        values.put(MemoryType.CONVERSATION, prop(env,"chat.memory.ttl.conversation",7200));
        values.put(MemoryType.SHOP_SUMMARY, prop(env,"chat.memory.ttl.shop-summary",3600));
        values.put(MemoryType.SHOP_QA, prop(env,"chat.memory.ttl.shop-qa",7200));
        values.put(MemoryType.SHOP_COMPARE, prop(env,"chat.memory.ttl.shop-compare",1800));
        values.put(MemoryType.SHOP_RECOMMEND, prop(env,"chat.memory.ttl.shop-recommend",86400));
        values.put(MemoryType.WORKING_MEMORY, prop(env,"chat.memory.ttl.working-memory",86400));
        values.put(MemoryType.EPISODIC_MEMORY, prop(env,"chat.memory.ttl.episodic-memory",2592000));
        values.put(MemoryType.LONG_TERM_FACT, prop(env,"chat.memory.ttl.long-term-fact",7776000));
        return values;
    }
    private static long prop(Environment env,String key,long fallback){Long value=env.getProperty(key,Long.class);return value==null||value<=0?fallback:value;}
}

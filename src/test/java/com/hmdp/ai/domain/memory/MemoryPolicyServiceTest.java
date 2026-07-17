package com.hmdp.ai.domain.memory;

import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryPolicyServiceTest {
    @Test void appliesDifferentTtlPerMemoryType() {
        EnumMap<MemoryType,Long> ttl=new EnumMap<>(MemoryType.class);
        ttl.put(MemoryType.SHOP_SUMMARY,3600L); ttl.put(MemoryType.SHOP_QA,7200L); ttl.put(MemoryType.LONG_TERM_FACT,90000L);
        MemoryPolicyService service=new MemoryPolicyService(ttl);
        assertThat(service.ttlSeconds(scope(MemoryType.SHOP_SUMMARY))).isEqualTo(3600L);
        assertThat(service.ttlSeconds(scope(MemoryType.SHOP_QA))).isEqualTo(7200L);
        assertThat(service.ttlSeconds(scope(MemoryType.LONG_TERM_FACT))).isEqualTo(90000L);
    }
    private MemoryScope scope(MemoryType type){return new MemoryScope("t","w","a","u","s",type,"r");}
}

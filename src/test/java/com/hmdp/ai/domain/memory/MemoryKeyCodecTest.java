package com.hmdp.ai.domain.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryKeyCodecTest {
    private final MemoryKeyCodec codec = new MemoryKeyCodec();

    @Test
    void roundTripsAllScopeFieldsWithoutDelimiterAmbiguity() {
        MemoryScope source = new MemoryScope("tenant:a", "workspace/一", "shop-agent", "user:42",
                "session:a:b", MemoryType.SHOP_QA, "shop:2");
        MemoryScope decoded = codec.decode(codec.encode(source)).orElseThrow(AssertionError::new);
        assertThat(decoded.getTenantId()).isEqualTo("tenant:a");
        assertThat(decoded.getWorkspaceId()).isEqualTo("workspace/一");
        assertThat(decoded.getUserId()).isEqualTo("user:42");
        assertThat(decoded.getSessionId()).isEqualTo("session:a:b");
        assertThat(decoded.getMemoryType()).isEqualTo(MemoryType.SHOP_QA);
        assertThat(decoded.getResourceId()).isEqualTo("shop:2");
    }

    @Test
    void decodesLegacyCompoundShopTypes() {
        MemoryScope summary = codec.decode("hmdp:memory:shop:summary:2:user7").orElseThrow(AssertionError::new);
        MemoryScope qa = codec.decode("hmdp:memory:shop:qa:2:user7").orElseThrow(AssertionError::new);
        assertThat(summary.getMemoryType()).isEqualTo(MemoryType.SHOP_SUMMARY);
        assertThat(qa.getMemoryType()).isEqualTo(MemoryType.SHOP_QA);
        assertThat(summary.getResourceId()).isEqualTo("2");
    }
}

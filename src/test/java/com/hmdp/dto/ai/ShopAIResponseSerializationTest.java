package com.hmdp.dto.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopAIResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNotSerializeInternalMemoryId() throws Exception {
        ShopAIResponse response = ShopAIResponse.builder()
                .sessionId("session-1")
                .memoryId("hmdp:memory:shop:qa:1:u1")
                .traceId("trace-1")
                .build();
        ShopSummaryResult summary = ShopSummaryResult.builder()
                .shopId(1L)
                .memoryId("hmdp:memory:shop:summary:1:u1")
                .traceId("trace-1")
                .build();
        ShopAIStreamEvent event = ShopAIStreamEvent.builder()
                .type("metadata")
                .memoryId("hmdp:memory:ai:chat:u1:s1")
                .traceId("trace-1")
                .build();

        assertThat(response.getMemoryId()).isEqualTo("hmdp:memory:shop:qa:1:u1");
        assertThat(summary.getMemoryId()).isEqualTo("hmdp:memory:shop:summary:1:u1");
        assertThat(event.getMemoryId()).isEqualTo("hmdp:memory:ai:chat:u1:s1");
        assertNoInternalMemoryKeys(objectMapper.writeValueAsString(response));
        assertNoInternalMemoryKeys(objectMapper.writeValueAsString(summary));
        assertNoInternalMemoryKeys(objectMapper.writeValueAsString(event));
    }

    private void assertNoInternalMemoryKeys(String json) {
        assertThat(json)
                .doesNotContain("memoryId")
                .doesNotContain("memoryKey")
                .doesNotContain("hmdp:memory");
    }
}

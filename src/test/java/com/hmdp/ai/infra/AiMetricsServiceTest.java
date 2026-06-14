package com.hmdp.ai.infra;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiMetricsServiceTest {

    @Test
    void shouldRecordCounterInSnapshotAndMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        AiMetricsService service = new AiMetricsService(provider);

        service.increment("ai.fallback.count", "ask", true);

        assertThat(service.snapshot()).isNotEmpty();
        assertThat(registry.find("ai.fallback.count").counter()).isNotNull();
        assertThat(registry.find("ai.fallback.count").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordModelTokenCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        AiMetricsService service = new AiMetricsService(provider);

        service.recordModelCall("ask", "ask:analyzeShopData", "qwen-plus", 12, true, 10, 6);

        assertThat(registry.find("ai.model.duration").timer()).isNotNull();
        assertThat(registry.find("ai.model.tokens.estimated").tag("direction", "input").counter().count())
                .isEqualTo(10.0);
        assertThat(registry.find("ai.model.tokens.estimated").tag("direction", "output").counter().count())
                .isEqualTo(6.0);
    }
}

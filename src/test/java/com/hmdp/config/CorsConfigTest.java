package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void corsShouldUseConfiguredOriginPatternsWithoutWildcardOrigin() throws Exception {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOriginPatterns",
                "https://example.com, *, http://localhost:*");

        CorsFilter filter = corsConfig.corsFilter();
        CorsConfiguration configuration = firstConfiguration(filter);

        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getAllowedOrigins()).isNullOrEmpty();
        assertThat(configuration.getAllowedOriginPatterns())
                .containsExactly("https://example.com", "http://localhost:*");
    }

    @SuppressWarnings("unchecked")
    private CorsConfiguration firstConfiguration(CorsFilter filter) throws Exception {
        Object source = ReflectionTestUtils.getField(filter, "configSource");
        Field field = source.getClass().getDeclaredField("corsConfigurations");
        field.setAccessible(true);
        Map<String, CorsConfiguration> configurations = (Map<String, CorsConfiguration>) field.get(source);
        return configurations.values().iterator().next();
    }
}

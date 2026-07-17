package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * @deprecated AI infrastructure is split into focused configuration modules under {@code com.hmdp.ai}.
 */
@Deprecated
@Configuration
public class CommonAIConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

package com.hmdp.ai.api.security;

import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.config.MvcConfig;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionBootstrapInterceptorExclusionTest {

    @Test
    void bootstrapPathIsExcludedFromAiPermissionInterceptor() throws Exception {
        AiPermissionInterceptor interceptor = new AiPermissionInterceptor(mock(AiAuthorizationService.class));
        MvcConfig config = new MvcConfig(mock(IUserService.class), interceptor);
        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);

        Field registrationsField = InterceptorRegistry.class.getDeclaredField("registrations");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<InterceptorRegistration> registrations =
                (List<InterceptorRegistration>) registrationsField.get(registry);

        boolean foundExclusion = false;
        for (InterceptorRegistration registration : registrations) {
            Field interceptorField = InterceptorRegistration.class.getDeclaredField("interceptor");
            interceptorField.setAccessible(true);
            if (interceptorField.get(registration) != interceptor) {
                continue;
            }
            Field excludeField = InterceptorRegistration.class.getDeclaredField("excludePatterns");
            excludeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> excludes = (List<String>) excludeField.get(registration);
            foundExclusion = excludes != null && excludes.contains("/api/v1/session/bootstrap");
        }

        assertThat(foundExclusion)
                .as("bootstrap must not require tenant/workspace headers")
                .isTrue();
    }
}

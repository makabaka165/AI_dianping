package com.hmdp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.hmdp.ai.api.security.AiPermissionInterceptor;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SaTokenUserHolderInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    private final IUserService userService;
    private final AiPermissionInterceptor aiPermissionInterceptor;

    public MvcConfig(IUserService userService, AiPermissionInterceptor aiPermissionInterceptor) {
        this.userService = userService;
        this.aiPermissionInterceptor = aiPermissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**").order(-2);
        registry.addInterceptor(new SaTokenUserHolderInterceptor(userService)).addPathPatterns("/**").order(-1);
        registry.addInterceptor(aiPermissionInterceptor)
                .addPathPatterns("/api/v1/**", "/api/shop-summary/**", "/document/**")
                // bootstrap must work with only a login token; scope is selected after this call
                .excludePathPatterns("/api/v1/session/bootstrap")
                .order(0);
    }
}

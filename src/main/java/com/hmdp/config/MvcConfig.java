package com.hmdp.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SaTokenUserHolderInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private IUserService userService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**").order(-2);
        registry.addInterceptor(new SaTokenUserHolderInterceptor(userService)).addPathPatterns("/**").order(-1);
    }
}

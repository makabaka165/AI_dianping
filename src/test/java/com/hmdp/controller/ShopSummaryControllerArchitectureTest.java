package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.hmdp.ai.application.ShopAIAdminApplicationService;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.application.ShopAIMemoryApplicationService;
import com.hmdp.service.CurrentUserService;
import org.springframework.context.annotation.Profile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ShopSummaryControllerArchitectureTest {

    @Test
    void shouldRemoveLegacySmartEndpointMethods() {
        Set<String> methodNames = Arrays.stream(ShopSummaryController.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methodNames).doesNotContain(
                "smartAnalyzeShop",
                "smartAskAboutShop",
                "smartCompareShops",
                "smartRecommendShops");
    }

    @Test
    void shouldDependOnSplitApplicationServices() {
        Set<Class<?>> fieldTypes = Arrays.stream(ShopSummaryController.class.getDeclaredFields())
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(fieldTypes).contains(
                ShopAIApplicationService.class,
                ShopAIMemoryApplicationService.class,
                ShopAIAdminApplicationService.class,
                CurrentUserService.class);
    }

    @Test
    void publicSummaryEndpointShouldRequireLogin() throws Exception {
        Method method = ShopSummaryController.class.getDeclaredMethod("getShopSummary", Long.class);

        assertThat(method.getAnnotation(SaCheckLogin.class)).isNotNull();
    }

    @Test
    void shouldKeepAiTestControllerOutOfDefaultProfile() {
        Profile profile = AITestController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "test");
    }
}

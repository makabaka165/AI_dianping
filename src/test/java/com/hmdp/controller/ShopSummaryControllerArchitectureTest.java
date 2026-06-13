package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.hmdp.ai.application.ShopAIAdminApplicationService;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.application.ShopAIMemoryApplicationService;
import com.hmdp.ai.workflow.CompareWorkflow;
import com.hmdp.ai.workflow.QAWorkflow;
import com.hmdp.ai.workflow.RecommendWorkflow;
import com.hmdp.ai.workflow.SummaryWorkflow;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.service.CurrentUserService;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.context.annotation.Profile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void shopAIResponseShouldNotExposeLegacyTextFields() {
        Set<String> fieldNames = Arrays.stream(ShopAIResponse.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fieldNames).doesNotContain(
                "response",
                "answer",
                "comparison",
                "recommendations",
                "usedTools",
                "winnerByAspect",
                "analysis");
        assertThat(fieldNames).contains("summary", "qa", "compare", "recommend", "chat", "evidence");
    }

    @Test
    void removedLegacyToolAndMetadataClassesShouldStayDeleted() {
        assertThatThrownBy(() -> Class.forName("com.hmdp.dto.ai.ReviewEvidence"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.ai.orchestration.AIExecutionMetadata"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.tools.DocumentManagementTool"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.tools.ShopTool"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.config.AiRequestContext"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void coreWorkflowsShouldNotDependOnEmbeddingStoreDirectly() {
        Class<?>[] workflows = {
                SummaryWorkflow.class,
                QAWorkflow.class,
                CompareWorkflow.class,
                RecommendWorkflow.class
        };

        for (Class<?> workflow : workflows) {
            Set<Class<?>> fieldTypes = Arrays.stream(workflow.getDeclaredFields())
                    .map(Field::getType)
                    .collect(Collectors.toSet());
            assertThat(fieldTypes).doesNotContain(EmbeddingStore.class);
        }
    }
}

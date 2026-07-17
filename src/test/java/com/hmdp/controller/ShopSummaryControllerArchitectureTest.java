package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.workflow.CompareWorkflow;
import com.hmdp.ai.workflow.QAWorkflow;
import com.hmdp.ai.workflow.RecommendWorkflow;
import com.hmdp.ai.workflow.SummaryWorkflow;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.service.CurrentUserService;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.context.annotation.Profile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
    void shouldDependOnSingleApplicationService() {
        Set<Class<?>> fieldTypes = Arrays.stream(ShopSummaryController.class.getDeclaredFields())
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(fieldTypes).contains(
                ShopAIApplicationService.class,
                CurrentUserService.class);
        Set<String> fieldTypeNames = fieldTypes.stream()
                .map(Class::getName)
                .collect(Collectors.toSet());
        assertThat(fieldTypeNames).doesNotContain(
                "com.hmdp.ai.application.ShopAIMemoryApplicationService",
                "com.hmdp.ai.application.ShopAIAdminApplicationService");
    }

    @Test
    void paidAiEndpointsShouldRequireAiChatPermission() throws Exception {
        Set<String> aiChatMethods = Set.of(
                "getShopSummary",
                "getShopSummaryWithMemory",
                "getQualitySummary",
                "getQualitySummaryWithMemory",
                "askAboutShop",
                "compareShops",
                "recommendShops",
                "smartChat",
                "smartChatStream",
                "clearShopQAMemory",
                "clearShopSummaryMemory",
                "clearRecommendMemory",
                "clearAllMemory",
                "getMemoryStatus",
                "refreshMemory");
        Set<String> secured = Arrays.stream(ShopSummaryController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(SaCheckPermission.class) != null)
                .filter(method -> "ai:chat".equals(method.getAnnotation(SaCheckPermission.class).value()[0]))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(secured).containsAll(aiChatMethods);
    }

    @Test
    void adminMemoryEndpointsShouldKeepMemoryManagePermission() {
        Set<String> memoryManageMethods = Set.of(
                "getMemoryStats",
                "adminCleanupMemory");
        Set<String> secured = Arrays.stream(ShopSummaryController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(SaCheckPermission.class) != null)
                .filter(method -> "ai:memory:manage".equals(method.getAnnotation(SaCheckPermission.class).value()[0]))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(secured).containsAll(memoryManageMethods);
    }

    @Test
    void publicSummaryEndpointShouldNoLongerOnlyRequireLogin() throws Exception {
        Method method = ShopSummaryController.class.getDeclaredMethod("getShopSummary", Long.class);

        assertThat(method.getAnnotation(SaCheckLogin.class)).isNull();
        assertThat(method.getAnnotation(SaCheckPermission.class)).isNotNull();
    }

    @Test
    void shouldKeepAiTestControllerOutOfDefaultProfile() {
        Profile profile = AITestController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("local", "dev", "test");
    }

    @Test
    void aiTestControllerShouldRequireDedicatedPermission() {
        SaCheckPermission permission = AITestController.class.getAnnotation(SaCheckPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("ai:test");
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
        assertThatThrownBy(() -> Class.forName("com.hmdp.ai.workflow.ShopAIWorkflow"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.ai.application.ShopAIMemoryApplicationService"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.hmdp.ai.application.ShopAIAdminApplicationService"))
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

    @Test
    void implicitContentRetrieverShouldOnlyBeAttachedToFreeChat() {
        assertThat(ShopAIService.class.getAnnotation(AiService.class).contentRetriever()).isEmpty();
        assertThat(ShopRepairAIService.class.getAnnotation(AiService.class).contentRetriever()).isEmpty();
        assertThat(ShopFreeChatAIService.class.getAnnotation(AiService.class).contentRetriever())
                .isEqualTo("platformPolicyContentRetriever");
    }

    @Test
    void shopReviewVectorIndexShouldUseDedicatedEmbeddingStoreQualifier() {
        Constructor<?> constructor = ShopReviewVectorIndexService.class.getDeclaredConstructors()[0];
        Parameter firstParameter = constructor.getParameters()[0];
        Qualifier qualifier = firstParameter.getAnnotation(Qualifier.class);

        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo("shopReviewEmbeddingStore");
    }
}

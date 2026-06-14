package com.hmdp.ai.model;

import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.ai.port.AiModelServicePort;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopView;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.infra.AiTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelGatewayTest {

    @Mock
    private AiModelServicePort aiModelServicePort;

    @Mock
    private AiMetricsService aiMetricsService;

    private ModelGateway modelGateway;

    @BeforeEach
    void setUp() {
        modelGateway = new ModelGateway();
        ReflectionTestUtils.setField(modelGateway, "aiModelServicePort", aiModelServicePort);
        ModelResilienceExecutor executor = new ModelResilienceExecutor();
        ReflectionTestUtils.setField(executor, "timeoutSeconds", 30L);
        ReflectionTestUtils.setField(executor, "maxConcurrentCalls", 8);
        ReflectionTestUtils.setField(executor, "rateLimitPeriodSeconds", 1L);
        ReflectionTestUtils.setField(executor, "rateLimitPermits", 100);
        ReflectionTestUtils.setField(modelGateway, "resilienceExecutor", executor);
        ReflectionTestUtils.setField(modelGateway, "structuredOutputParser", new StructuredOutputParser());
        ReflectionTestUtils.setField(modelGateway, "repairPromptFactory", new RepairPromptFactory());
        ModelMetricsRecorder metricsRecorder = new ModelMetricsRecorder();
        ReflectionTestUtils.setField(metricsRecorder, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(metricsRecorder, "aiTokenEstimator", new AiTokenEstimator());
        ReflectionTestUtils.setField(modelGateway, "modelMetricsRecorder", metricsRecorder);
    }

    @Test
    void modelNameShouldReflectConfiguredModelAndFallbackToDefault() {
        assertThat(modelGateway.modelName()).isEqualTo(ModelGateway.DEFAULT_MODEL_NAME);

        ReflectionTestUtils.setField(modelGateway, "configuredModelName", " qwen-plus ");
        assertThat(modelGateway.modelName()).isEqualTo("qwen-plus");

        ReflectionTestUtils.setField(modelGateway, "configuredModelName", " ");
        assertThat(modelGateway.modelName()).isEqualTo(ModelGateway.DEFAULT_MODEL_NAME);
    }

    @Test
    void repairAnswerShouldUseLowTemperatureRepairService() {
        when(aiModelServicePort.repairAnalyzeShopData(eq("m1"), anyString())).thenReturn(
                "{\"shopId\":1,\"question\":\"q\",\"answer\":\"fixed\",\"evidenceIds\":[],\"insufficientEvidence\":false}");

        ShopQAResult result = modelGateway.repairStructuredAnswer("m1", "original prompt", 1L, "q", "too generic");

        assertThat(result.getAnswer()).isEqualTo("fixed");
        verify(aiModelServicePort).repairAnalyzeShopData(eq("m1"), anyString());
        verify(aiModelServicePort, never()).analyzeShopData(eq("m1"), anyString());
    }

    @Test
    void shouldParseStructuredComparisonAndRecommendation() {
        when(aiModelServicePort.analyzeShopData("m1", "compare prompt")).thenReturn(
                "{\"shopId1\":1,\"shopId2\":2,\"aspect\":\"服务\",\"conclusion\":\"A 更稳\",\"winnerByAspect\":\"SHOP_1\",\"shop1Score\":80,\"shop2Score\":60,\"shop1Pros\":[\"响应快\"],\"shop2Pros\":[],\"riskNotes\":[],\"evidenceIds\":[\"review:1\"]}");
        ShopCompareResult compare = modelGateway.generateStructuredComparison("m1", "compare prompt", 1L, 2L,
                "服务", Collections.emptyList());
        assertThat(compare.getWinnerByAspect()).isEqualTo(ShopCompareResult.SHOP_1);
        assertThat(compare.getEvidenceIds()).containsExactly("review:1");

        ShopView shop = ShopView.builder().id(1L).name("店铺A").build();
        when(aiModelServicePort.analyzeShopData("m1", "recommend prompt")).thenReturn(
                "{\"userPreference\":\"约会\",\"category\":\"餐厅\",\"message\":\"推荐如下\",\"items\":[{\"rank\":1,\"shopId\":1,\"reason\":\"安静\",\"evidenceIds\":[\"shop_profile:1\"],\"confidence\":0.7}]}");
        ShopRecommendResult recommend = modelGateway.generateStructuredRecommendation("m1", "recommend prompt",
                "约会", "餐厅", List.of(shop), Collections.emptyList());
        assertThat(recommend.getItems()).hasSize(1);
        assertThat(recommend.getItems().get(0).getShopName()).isEqualTo("店铺A");
    }

    @Test
    void shouldParseLowercaseIntentAndSingleMissingParam() throws Exception {
        when(aiModelServicePort.classifyIntent("prompt")).thenReturn(
                "{\"intent\":\"qa\",\"confidence\":0.88,\"missingParams\":\"shopId\"}");

        IntentRouteCandidate result = modelGateway.classifyIntent("prompt");

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.safeMissingParams()).containsExactly("shopId");
        assertThat(result.getConfidence()).isEqualTo(0.88);
    }

    @Test
    void shouldRecordModelMetricsForStructuredAnswer() {
        when(aiModelServicePort.analyzeShopData("m1", "服务怎么样")).thenReturn(
                "{\"shopId\":1,\"question\":\"q\",\"answer\":\"服务不错\",\"evidenceIds\":[],\"insufficientEvidence\":false}");

        modelGateway.generateStructuredAnswer("m1", "服务怎么样", 1L, "q", Collections.emptyList());

        verify(aiMetricsService).recordModelCall(eq("ask"), eq("ask:analyzeShopData"), eq(ModelGateway.DEFAULT_MODEL_NAME),
                org.mockito.ArgumentMatchers.anyLong(), eq(true),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldTimeoutSlowModelCall() {
        ModelResilienceExecutor executor = new ModelResilienceExecutor();
        ReflectionTestUtils.setField(executor, "timeoutSeconds", 1L);
        ReflectionTestUtils.setField(executor, "maxConcurrentCalls", 8);
        ReflectionTestUtils.setField(executor, "rateLimitPeriodSeconds", 1L);
        ReflectionTestUtils.setField(executor, "rateLimitPermits", 100);
        ReflectionTestUtils.setField(modelGateway, "resilienceExecutor", executor);
        when(aiModelServicePort.analyzeShopData("m1", "prompt")).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return "{\"shopId\":1,\"question\":\"q\",\"answer\":\"late\",\"evidenceIds\":[],\"insufficientEvidence\":false}";
        });

        assertThatThrownBy(() -> modelGateway.generateStructuredAnswer("m1", "prompt", 1L, "q", Collections.emptyList()))
                .isInstanceOf(Exception.class);
    }
}

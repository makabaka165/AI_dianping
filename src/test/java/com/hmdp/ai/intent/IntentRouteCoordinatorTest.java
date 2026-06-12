package com.hmdp.ai.intent;

import com.hmdp.ai.orchestration.ShopAIRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentRouteCoordinatorTest {

    @Mock
    private LLMIntentClassifier llmIntentClassifier;

    @Mock
    private IntentSlotMemoryService intentSlotMemoryService;

    private IntentRouteCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new IntentRouteCoordinator();
        ReflectionTestUtils.setField(coordinator, "ruleIntentParser", new RuleIntentParser());
        ReflectionTestUtils.setField(coordinator, "llmIntentClassifier", llmIntentClassifier);
        ReflectionTestUtils.setField(coordinator, "intentSlotMemoryService", intentSlotMemoryService);
    }

    @Test
    void highConfidenceRuleShouldNotCallLlm() {
        ShopAIRequestContext context = context();
        when(intentSlotMemoryService.load("u1", "s1")).thenReturn(null);

        IntentRoutingResult result = coordinator.route(context, "对比店铺1和店铺2的服务", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.COMPARE);
        assertThat(result.getSource()).isEqualTo(IntentRouteSource.RULE);
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
        assertThat(result.getShopId1()).isEqualTo(1L);
        assertThat(result.getShopId2()).isEqualTo(2L);
        verifyNoInteractions(llmIntentClassifier);
    }

    @Test
    void mediumConfidenceRuleShouldRejectLlmInventedShopId() {
        ShopAIRequestContext context = context();
        IntentRouteCandidate rule = new RuleIntentParser().parse("这家店服务怎么样", null);
        when(intentSlotMemoryService.load("u1", "s1")).thenReturn(null);
        when(llmIntentClassifier.classify("这家店服务怎么样", rule, null)).thenReturn(IntentRouteCandidate.builder()
                .intent(ShopAIIntent.QA)
                .shopId(9L)
                .confidence(0.9)
                .source(IntentRouteSource.LLM)
                .build());

        IntentRoutingResult result = coordinator.route(context, "这家店服务怎么样", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.getSource()).isEqualTo(IntentRouteSource.CLARIFICATION);
        assertThat(result.getShopId()).isNull();
        assertThat(result.getMissingParams()).contains("shopId");
        assertThat(result.getClarification()).isNotBlank();
        verify(llmIntentClassifier).classify("这家店服务怎么样", rule, null);
    }

    @Test
    void shouldFillCompareSlotsFromMemoryForFollowUpAspect() {
        ShopAIRequestContext context = context();
        IntentSlotState slotState = IntentSlotState.builder()
                .intent(ShopAIIntent.COMPARE)
                .shopId1(1L)
                .shopId2(2L)
                .aspect("服务")
                .build();
        IntentRouteCandidate rule = new RuleIntentParser().parse("那环境呢", null);
        when(intentSlotMemoryService.load("u1", "s1")).thenReturn(slotState);
        when(llmIntentClassifier.classify("那环境呢", rule, slotState)).thenReturn(IntentRouteCandidate.builder()
                .intent(ShopAIIntent.UNSUPPORTED)
                .confidence(0.0)
                .source(IntentRouteSource.LLM)
                .build());

        IntentRoutingResult result = coordinator.route(context, "那环境呢", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.COMPARE);
        assertThat(result.getSource()).isEqualTo(IntentRouteSource.MEMORY);
        assertThat(result.getShopId1()).isEqualTo(1L);
        assertThat(result.getShopId2()).isEqualTo(2L);
        assertThat(result.getAspect()).isEqualTo("环境");
    }

    @Test
    void shouldAcceptLlmShopIdFromExplicitRequest() {
        ShopAIRequestContext context = context();
        IntentRouteCandidate rule = new RuleIntentParser().parse("service?", 9L);
        when(intentSlotMemoryService.load("u1", "s1")).thenReturn(null);
        when(llmIntentClassifier.classify("service?", rule, null)).thenReturn(IntentRouteCandidate.builder()
                .intent(ShopAIIntent.QA)
                .shopId(9L)
                .confidence(0.9)
                .source(IntentRouteSource.LLM)
                .build());

        IntentRoutingResult result = coordinator.route(context, "service?", 9L);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.getSource()).isEqualTo(IntentRouteSource.LLM);
        assertThat(result.getShopId()).isEqualTo(9L);
    }

    @Test
    void shouldNotTrustRecommendationLimitAsShopId() {
        ShopAIRequestContext context = context();
        String message = "recommend 3 restaurants for date";
        IntentRouteCandidate rule = new RuleIntentParser().parse(message, null);
        when(intentSlotMemoryService.load("u1", "s1")).thenReturn(null);
        when(llmIntentClassifier.classify(message, rule, null)).thenReturn(IntentRouteCandidate.builder()
                .intent(ShopAIIntent.QA)
                .shopId(3L)
                .confidence(0.9)
                .source(IntentRouteSource.LLM)
                .build());

        IntentRoutingResult result = coordinator.route(context, message, null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.getSource()).isEqualTo(IntentRouteSource.CLARIFICATION);
        assertThat(result.getShopId()).isNull();
        assertThat(result.getMissingParams()).contains("shopId");
    }

    private ShopAIRequestContext context() {
        return ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .build();
    }
}

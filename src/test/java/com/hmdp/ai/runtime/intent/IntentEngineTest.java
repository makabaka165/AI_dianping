package com.hmdp.ai.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEngineTest {
    private final IntentRuleClassifier rules = new IntentRuleClassifier();
    private final EntityExtractionService entities = new EntityExtractionService();
    private final SlotFillingService slots = new SlotFillingService(new IntentConfidencePolicy());

    @Test
    void classifiesChineseCompareWithStructuredEntities() {
        String text = "\u6bd4\u8f83\u5e97 12 \u548c\u5e97 34 \u7684\u670d\u52a1";
        Map<String, Object> extracted = entities.extract(text, Collections.emptyMap());
        IntentClassification classification = rules.classify(text, extracted);
        IntentClassification filled = slots.fill(classification, extracted, Collections.emptyMap());

        assertThat(filled.getPrimaryIntent()).isEqualTo("SHOP_COMPARE");
        assertThat(filled.getConfidence()).isGreaterThan(0.85);
        assertThat(filled.getEntities().get("shopIds")).asList().containsExactly(12L, 34L);
        assertThat(filled.getEntities().get("aspect")).isEqualTo("service");
        assertThat(filled.getMissingSlots()).isEmpty();
        assertThat(filled.isRequiresClarification()).isFalse();
    }

    @Test
    void reportsMissingSlotsAndUsesContinuationVariables() {
        String text = "\u6bd4\u8f83\u4e24\u5bb6\u5e97";
        Map<String, Object> extracted = entities.extract(text, Collections.emptyMap());
        IntentClassification classification = rules.classify(text, extracted);
        IntentClassification missing = slots.fill(classification, extracted, Collections.emptyMap());
        assertThat(missing.getMissingSlots()).containsExactly("shopIds");
        assertThat(missing.isRequiresClarification()).isTrue();

        IntentClassification resumed = slots.fill(classification,
                Collections.singletonMap("shopIds", java.util.Arrays.asList(7L, 8L)), Collections.emptyMap());
        assertThat(resumed.getMissingSlots()).isEmpty();
    }
}

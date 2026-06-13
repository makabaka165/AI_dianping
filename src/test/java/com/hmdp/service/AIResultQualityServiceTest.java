package com.hmdp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AIResultQualityServiceTest {

    private final AIResultQualityService service = new AIResultQualityService();

    @Test
    void shouldNotRejectNormalBusinessTextContainingForbiddenWord() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("这家店整体环境安静，店内禁止吸烟，适合家庭聚餐。");

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldRejectModelSelfReference() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("作为AI模型，我无法查看真实店铺信息。");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("自我引用");
    }

    @Test
    void postProcessShouldNotRemoveEvidenceBoundBusinessSentence() {
        String processed = service.postProcessContent("根据证据提供的信息，这家店服务稳定，适合聚餐。");

        assertThat(processed).contains("服务稳定");
    }
}

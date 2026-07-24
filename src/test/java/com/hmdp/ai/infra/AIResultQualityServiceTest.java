package com.hmdp.ai.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AIResultQualityServiceTest {

    private final AIResultQualityService service = new AIResultQualityService();

    @Test
    void shouldNotRejectNormalBusinessTextContainingForbiddenWord() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("店内禁止吸烟，环境整体不错，服务响应较快。");

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldNotRejectBusinessAnswerContainingTemplateTailPhrase() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("这家店服务比较稳定。如有需要请继续提问。");

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void postProcessShouldRemoveStandaloneTemplateTailLine() {
        String processed = service.postProcessContent("这家店服务比较稳定。\n如有需要请继续提问。");

        assertThat(processed).isEqualTo("这家店服务比较稳定。");
    }

    @Test
    void shouldRejectClearlyUnsafeText() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("违法犯罪指导");

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void shouldRejectOverclaimText() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("我保证这家店绝对最好");

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void shouldRejectModelSelfReference() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContent("作为AI模型，我无法查看真实店铺信息。");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("自我引用");
    }

    @Test
    void shouldAllowShortSafeContentFragment() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContentFragment("适合约会");

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void shouldRejectUnsafeShortContentFragment() {
        AIResultQualityService.QualityCheckResult result =
                service.validateContentFragment("绝对最好");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("过度确定");
    }

    @Test
    void postProcessShouldNotRemoveEvidenceBoundBusinessSentence() {
        String processed = service.postProcessContent("根据证据提供的信息，这家店服务稳定，适合聚餐。");

        assertThat(processed).contains("服务稳定");
    }
}

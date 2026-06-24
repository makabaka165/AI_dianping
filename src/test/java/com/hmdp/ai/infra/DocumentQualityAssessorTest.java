package com.hmdp.ai.infra;

import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQualityAssessorTest {

    private final DocumentQualityAssessor assessor = new DocumentQualityAssessor();

    @Test
    void platformPolicyAssessmentShouldRewardCompleteChinesePolicyContent() {
        String content = "# 退款与投诉处理规则\n"
                + "一、适用范围：用户在平台下单后，如商家未履约、商品与描述不符或支付异常，可以申请退款。\n"
                + "二、处理流程：用户在订单详情提交退款申请，上传订单号、截图等凭证，平台客服会在24小时内审核。\n"
                + "三、责任划分：商家原因导致的问题由商家承担，用户原因导致的取消需遵守商家公示规则。\n"
                + "四、限制条件：已核销订单、超过7天的订单或缺少有效凭证的申请，平台可能不支持退款。\n"
                + "五、投诉渠道：用户可以通过客服入口投诉商家，平台会根据证据进行处理并反馈结果。";

        DocumentQualityAssessment assessment = assessor.assess(Document.from(content), DocumentQualityProfile.PLATFORM_POLICY);

        assertThat(assessment.getProfile()).isEqualTo(DocumentQualityProfile.PLATFORM_POLICY);
        assertThat(assessment.getScore()).isGreaterThanOrEqualTo(0.75);
        assertThat(assessment.getDimensionScores())
                .containsKeys("topicRelevance", "policyCompleteness", "structure", "retrievability", "readability");
        assertThat(assessment.getKeywords()).contains("退款", "投诉", "商家", "平台");
        assertThat(assessment.getIssues()).doesNotContain("CHINESE_COUNTED_AS_NOISE");
    }

    @Test
    void platformPolicyAssessmentShouldExplainLowQualityUnrelatedContent() {
        DocumentQualityAssessment assessment = assessor.assess(
                Document.from("%%%%%%% 火星旅行随笔 %%%%%%%\n今天风景很好很好很好很好很好，没有任何规则、流程或平台处理说明。"),
                DocumentQualityProfile.PLATFORM_POLICY);

        assertThat(assessment.getScore()).isLessThan(0.55);
        assertThat(assessment.getIssues())
                .contains("LOW_PLATFORM_POLICY_RELEVANCE", "MISSING_POLICY_PROCESS_OR_BOUNDARIES");
        assertThat(assessment.getSuggestions()).isNotEmpty();
    }

    @Test
    void platformPolicyAssessmentShouldKeepPositiveTopicWhenSameTermAlsoAppearsInNegatedBoundary() {
        String content = "# 退款规则\n"
                + "适用范围：用户在平台订单发生重复支付或商家无法履约时，可以申请退款。\n"
                + "处理流程：用户提交订单号、支付凭证和商家沟通记录，平台客服审核后处理。\n"
                + "限制条件：已核销订单或缺少凭证时，平台不支持退款。\n"
                + "投诉渠道：如商家拒绝沟通，用户可以继续投诉。";

        DocumentQualityAssessment assessment = assessor.assess(content, DocumentQualityProfile.PLATFORM_POLICY);

        assertThat(assessment.getDimensionScores().get("topicRelevance")).isGreaterThanOrEqualTo(0.75);
        assertThat(assessment.getIssues()).doesNotContain("LOW_PLATFORM_POLICY_RELEVANCE");
    }

    @Test
    void shopReviewAssessmentShouldUseReviewEvidenceProfile() {
        String content = "周末和朋友来吃晚餐，排队等了20分钟。服务态度不错，店员会主动加水，"
                + "招牌菜味道稳定，人均88元，环境有点吵但整体适合聚餐，性价比还可以。";

        DocumentQualityAssessment assessment = assessor.assess(content, DocumentQualityProfile.SHOP_REVIEW);

        assertThat(assessment.getProfile()).isEqualTo(DocumentQualityProfile.SHOP_REVIEW);
        assertThat(assessment.getScore()).isGreaterThanOrEqualTo(0.70);
        assertThat(assessment.getDimensionScores())
                .containsKeys("reviewEvidence", "aspectCoverage", "sentimentSignal", "spamSafety");
        assertThat(assessment.getKeywords()).contains("服务", "环境", "味道", "价格");
    }

}

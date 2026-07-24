package com.hmdp.ai.model;

import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser();

    @Test
    void shouldExtractJsonFromMarkdownFenceAndNormalizeEvidenceIds() throws Exception {
        ShopAIAnalysisResult result = parser.parseStructuredAnalysis("```json\n"
                + "{\"summary\":\"这家店服务稳定，环境也比较干净。\",\"sentiment\":\"great\","
                + "\"keywords\":[\"服务\"],\"pros\":[],\"cons\":[],\"confidence\":0.8,\"evidenceIds\":[88]}\n```");

        assertThat(result.getSentiment()).isEqualTo("neutral");
        assertThat(result.getEvidenceIds()).containsExactly("review:88");
    }

    @Test
    void shouldParseCompareAndRecommendFields() {
        ShopCompareResult compare = parser.parseCompare(
                "{\"shopId1\":1,\"shopId2\":2,\"aspect\":\"模型改写\",\"conclusion\":\"A 稳定\","
                        + "\"winnerByAspect\":\"SHOP_1\",\"shop1Score\":80,\"shop2Score\":70,"
                        + "\"shop1Pros\":[\"快\"],\"shop2Pros\":[],\"riskNotes\":[],\"evidenceIds\":[\"review:1\"]}",
                1L, 2L, "服务");
        assertThat(compare.getWinnerByAspect()).isEqualTo("SHOP_1");
        assertThat(compare.getAspect()).isEqualTo("服务");
        assertThat(compare.getShop1Pros()).containsExactly("快");

        ShopRecommendResult recommend = parser.parseRecommend(
                "{\"userPreference\":\"约会\",\"category\":\"餐厅\",\"message\":\"推荐\","
                        + "\"items\":[{\"rank\":1,\"shopId\":3,\"reason\":\"安静\",\"evidenceIds\":[\"shop_profile:3\"]}]}",
                "约会", "餐厅", List.of(ShopView.builder().id(3L).name("店铺C").build()));
        assertThat(recommend.getItems()).hasSize(1);
        assertThat(recommend.getItems().get(0).getShopName()).isEqualTo("店铺C");
    }

    @Test
    void shouldDistinguishCompleteTruncatedAndInvalidJson() {
        assertThat(parser.classifyJson("```json\n{\"answer\":\"ok\"}\n```"))
                .isEqualTo(StructuredOutputParser.JsonStatus.COMPLETE);
        assertThat(parser.classifyJson("{\"answer\":\"partial\""))
                .isEqualTo(StructuredOutputParser.JsonStatus.INCOMPLETE);
        assertThat(parser.classifyJson("{\"answer\":}"))
                .isEqualTo(StructuredOutputParser.JsonStatus.INVALID);

        assertThatThrownBy(() -> parser.parseQA("{\"answer\":\"partial\"", 1L, "question"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INCOMPLETE_JSON");
    }

    @Test
    void shouldIgnoreProseBracesAndKeepBracesInsideJsonStrings() {
        String response = "example {placeholder} before payload\n```json\n"
                + "{\"shopId\":1,\"question\":\"model question\",\"answer\":\"use } literally\","
                + "\"evidenceIds\":[],\"insufficientEvidence\":false}\n```";

        ShopQAResult result = parser.parseQA(response, 1L, "request question");

        assertThat(result.getQuestion()).isEqualTo("request question");
        assertThat(result.getAnswer()).isEqualTo("use } literally");
    }

    @Test
    void shouldRejectArrayWhenStructuredContractRequiresObject() {
        assertThatThrownBy(() -> parser.parseQA("[]", 1L, "question"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON_OBJECT_REQUIRED");
    }
}

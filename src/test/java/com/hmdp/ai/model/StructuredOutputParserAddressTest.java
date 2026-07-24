package com.hmdp.ai.model;

import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputParserAddressTest {

    private final StructuredOutputParser parser = new StructuredOutputParser();

    @Test
    void shouldUseRequestAndCandidateMetadataForRecommendationItems() {
        ShopRecommendResult recommend = parser.parseRecommend(
                "{\"userPreference\":\"model preference\",\"category\":\"model category\","
                        + "\"items\":[{\"rank\":1,\"shopId\":3,\"shopName\":\"Model Shop\","
                        + "\"address\":\"model address\"}]}",
                "quiet", "food",
                List.of(ShopView.builder().id(3L).name("Candidate Shop").address("candidate address").build()));

        assertThat(recommend.getItems()).hasSize(1);
        assertThat(recommend.getUserPreference()).isEqualTo("quiet");
        assertThat(recommend.getCategory()).isEqualTo("food");
        assertThat(recommend.getItems().get(0).getShopName()).isEqualTo("Candidate Shop");
        assertThat(recommend.getItems().get(0).getAddress()).isEqualTo("candidate address");
    }

    @Test
    void shouldNotTrustRecommendationAddressWhenShopIsNotCandidate() {
        ShopRecommendResult recommend = parser.parseRecommend(
                "{\"items\":[{\"rank\":1,\"shopId\":99,\"shopName\":\"Unknown\",\"address\":\"model address\"}]}",
                "quiet", "food",
                List.of(ShopView.builder().id(3L).name("Candidate Shop").address("candidate address").build()));

        assertThat(recommend.getItems()).hasSize(1);
        assertThat(recommend.getItems().get(0).getAddress()).isNull();
    }
}

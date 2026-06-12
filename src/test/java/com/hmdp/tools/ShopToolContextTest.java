package com.hmdp.tools;

import com.hmdp.config.AiRequestContext;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.ShopContextAssembler;
import com.hmdp.service.ShopReviewEvidenceRetriever;
import com.hmdp.service.ShopStatsService;
import com.hmdp.utils.LocalCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopToolContextTest {

    @Mock
    private ShopStatsService shopStatsService;

    @Mock
    private ShopReviewEvidenceRetriever evidenceRetriever;

    @Mock
    private ShopContextAssembler contextAssembler;

    @Mock
    private LocalCacheManager localCacheManager;

    @Mock
    private ShopMapper shopMapper;

    private ShopTool shopTool;

    @BeforeEach
    void setUp() {
        shopTool = new ShopTool();
        ReflectionTestUtils.setField(shopTool, "evidenceRetriever", evidenceRetriever);
        ReflectionTestUtils.setField(shopTool, "contextAssembler", contextAssembler);
        ReflectionTestUtils.setField(shopTool, "localCacheManager", localCacheManager);
        ReflectionTestUtils.setField(shopTool, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(shopTool, "shopStatsService", shopStatsService);
        AiRequestContext.set(AiRequestContext.Context.builder()
                .userId("user-42")
                .sessionId("s1")
                .memoryId("m1")
                .traceId("t1")
                .sourceEndpoint("test")
                .build());
    }

    @AfterEach
    void tearDown() {
        AiRequestContext.clear();
    }

    @Test
    void checkShopExistsShouldUseAiRequestContextUserForRateLimit() {
        when(localCacheManager.checkAndIncrementUserCallCount("user-42", "checkShopExists", 20)).thenReturn(true);
        when(localCacheManager.checkAndIncrementTimeBasedCallCount("user-42", "checkShopExists", 60000, 5)).thenReturn(true);
        when(shopStatsService.shopExists(1L)).thenReturn(true);
        when(shopStatsService.getShopReviewCount(1L)).thenReturn(7);

        String result = shopTool.checkShopExists(1L);

        assertThat(result).contains("\"success\":true");
        assertThat(result).contains("\"reviewCount\":7");
    }

    @Test
    void toolCallShouldFailWhenUserContextIsMissing() {
        AiRequestContext.clear();

        String result = shopTool.checkShopExists(1L);

        assertThat(result).contains("\"success\":false");
        assertThat(result).contains("\"errorCode\":\"AUTH_CONTEXT_MISSING\"");
        verify(localCacheManager, never()).checkAndIncrementUserCallCount("anonymous", "checkShopExists", 20);
    }

    @Test
    void findRecommendCandidatesShouldReturnPublicCandidateFieldsOnly() {
        when(localCacheManager.checkAndIncrementUserCallCount("user-42", "findRecommendCandidates", 5)).thenReturn(true);
        when(localCacheManager.checkAndIncrementTimeBasedCallCount("user-42", "findRecommendCandidates", 60000, 2)).thenReturn(true);
        when(shopMapper.selectRecommendCandidates("餐厅", 3)).thenReturn(java.util.List.of(new Shop()
                .setId(9L)
                .setName("Good Shop")
                .setTypeId(1L)
                .setArea("CBD")
                .setAddress("private address")
                .setImages("private image")
                .setX(120.1)
                .setY(30.2)
                .setAvgPrice(88L)
                .setSold(20)
                .setComments(6)
                .setScore(45)
                .setOpenHours("10:00-22:00")
                .setVersion(3)));

        String result = shopTool.findRecommendCandidates("约会", "餐厅", 3);

        assertThat(result).contains("\"shopId\":9");
        assertThat(result).contains("\"shopName\":\"Good Shop\"");
        assertThat(result).doesNotContain("private address");
        assertThat(result).doesNotContain("private image");
        assertThat(result).doesNotContain("\"address\"");
        assertThat(result).doesNotContain("\"images\"");
        assertThat(result).doesNotContain("\"version\"");
        assertThat(result).doesNotContain("\"x\"");
        assertThat(result).doesNotContain("\"y\"");
    }
}

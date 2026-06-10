package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.LocalCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.LocalCacheManager.CacheKeys.shopExistsKey;
import static com.hmdp.utils.LocalCacheManager.CacheKeys.shopReviewCountKey;
import static com.hmdp.utils.LocalCacheManager.CacheType.SHOP_INFO;
import static com.hmdp.utils.LocalCacheManager.CacheType.SHOP_STATS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopStatsServiceTest {

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private LocalCacheManager localCacheManager;

    private ShopStatsService shopStatsService;

    @BeforeEach
    void setUp() {
        shopStatsService = new ShopStatsService();
        ReflectionTestUtils.setField(shopStatsService, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(shopStatsService, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(shopStatsService, "localCacheManager", localCacheManager);
    }

    @Test
    void queryShopStatusShouldTreatShopWithoutReviewsAsExisting() {
        when(localCacheManager.get(shopExistsKey(1L), Boolean.class, SHOP_INFO)).thenReturn(null);
        when(localCacheManager.get(shopReviewCountKey(1L), Integer.class, SHOP_STATS)).thenReturn(null);
        when(shopMapper.selectCount(any())).thenReturn(1);
        when(blogMapper.selectCount(any())).thenReturn(0);

        ShopStatusVO status = shopStatsService.queryShopStatus(1L);

        assertThat(status.getExists()).isTrue();
        assertThat(status.getReviewCount()).isZero();
        verify(localCacheManager).put(shopExistsKey(1L), true, SHOP_INFO);
        verify(localCacheManager).put(shopReviewCountKey(1L), 0, SHOP_STATS);
    }

    @Test
    void queryShopStatusShouldSkipReviewCountWhenShopDoesNotExist() {
        when(localCacheManager.get(shopExistsKey(2L), Boolean.class, SHOP_INFO)).thenReturn(null);
        when(shopMapper.selectCount(any())).thenReturn(0);

        ShopStatusVO status = shopStatsService.queryShopStatus(2L);

        assertThat(status.getExists()).isFalse();
        assertThat(status.getReviewCount()).isNull();
        verify(blogMapper, never()).selectCount(any());
        verify(localCacheManager).put(shopExistsKey(2L), false, SHOP_INFO);
    }

    @Test
    void shopExistsShouldUseLocalCacheBeforeDb() {
        when(localCacheManager.get(shopExistsKey(3L), Boolean.class, SHOP_INFO)).thenReturn(true);

        boolean exists = shopStatsService.shopExists(3L);

        assertThat(exists).isTrue();
        verify(shopMapper, never()).selectCount(any());
    }

    @Test
    void getShopReviewCountShouldUseLocalCacheBeforeDb() {
        when(localCacheManager.get(shopReviewCountKey(4L), Integer.class, SHOP_STATS)).thenReturn(9);

        int count = shopStatsService.getShopReviewCount(4L);

        assertThat(count).isEqualTo(9);
        verify(blogMapper, never()).selectCount(any());
    }

    @Test
    void getShopReviewCountShouldOnlyCountPublishedActiveBlogs() {
        when(localCacheManager.get(shopReviewCountKey(6L), Integer.class, SHOP_STATS)).thenReturn(null);
        when(blogMapper.selectCount(any())).thenReturn(7);

        int count = shopStatsService.getShopReviewCount(6L);

        assertThat(count).isEqualTo(7);
        ArgumentCaptor<QueryWrapper<Blog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(blogMapper).selectCount(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("shop_id", "status", "deleted");
    }

    @Test
    void updateMethodsShouldWriteExpectedCacheKeys() {
        shopStatsService.updateShopExistsCache(5L, true);
        shopStatsService.updateShopReviewCountCache(5L, 11);

        verify(localCacheManager).put(eq(shopExistsKey(5L)), eq(true), eq(SHOP_INFO));
        verify(localCacheManager).put(eq(shopReviewCountKey(5L)), eq(11), eq(SHOP_STATS));
    }
}

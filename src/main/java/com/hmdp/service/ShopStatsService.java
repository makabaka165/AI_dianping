package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class ShopStatsService {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private LocalCacheManager localCacheManager;

    public ShopStatusVO queryShopStatus(Long shopId) {
        boolean exists = shopExists(shopId);
        ShopStatusVO vo = new ShopStatusVO();
        vo.setExists(exists);
        if (exists) {
            vo.setReviewCount(getShopReviewCount(shopId));
        }
        return vo;
    }

    public boolean shopExists(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return false;
        }
        String cacheKey = LocalCacheManager.CacheKeys.shopExistsKey(shopId);
        Boolean cachedResult = localCacheManager.get(cacheKey, Boolean.class, LocalCacheManager.CacheType.SHOP_INFO);
        if (cachedResult != null) {
            return cachedResult;
        }

        try {
            Integer count = shopMapper.selectCount(new QueryWrapper<Shop>().eq("id", shopId));
            boolean exists = count != null && count > 0;
            updateShopExistsCache(shopId, exists);
            return exists;
        } catch (Exception e) {
            log.warn("Check shop existence failed, shopId={}", shopId, e);
            return false;
        }
    }

    public int getShopReviewCount(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return 0;
        }
        String cacheKey = LocalCacheManager.CacheKeys.shopReviewCountKey(shopId);
        Integer cachedCount = localCacheManager.get(cacheKey, Integer.class, LocalCacheManager.CacheType.SHOP_STATS);
        if (cachedCount != null) {
            return cachedCount;
        }

        try {
            Integer count = blogMapper.selectCount(new QueryWrapper<Blog>()
                    .eq("shop_id", shopId)
                    .eq("status", 1)
                    .eq("deleted", 0));
            int reviewCount = count == null ? 0 : count;
            updateShopReviewCountCache(shopId, reviewCount);
            return reviewCount;
        } catch (Exception e) {
            log.warn("Get shop review count failed, shopId={}", shopId, e);
            return 0;
        }
    }

    public void updateShopExistsCache(Long shopId, boolean exists) {
        String cacheKey = LocalCacheManager.CacheKeys.shopExistsKey(shopId);
        localCacheManager.put(cacheKey, exists, LocalCacheManager.CacheType.SHOP_INFO);
    }

    public void updateShopReviewCountCache(Long shopId, int count) {
        String cacheKey = LocalCacheManager.CacheKeys.shopReviewCountKey(shopId);
        localCacheManager.put(cacheKey, count, LocalCacheManager.CacheType.SHOP_STATS);
    }

    public void evictShopStatsCache(Long shopId) {
        localCacheManager.remove(LocalCacheManager.CacheKeys.shopExistsKey(shopId), LocalCacheManager.CacheType.SHOP_INFO);
        localCacheManager.remove(LocalCacheManager.CacheKeys.shopReviewCountKey(shopId), LocalCacheManager.CacheType.SHOP_STATS);
    }
}

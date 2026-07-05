package com.hmdp.ai.port.adapter;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.dto.ai.ShopView;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class MyBatisShopDataAdapter implements ShopDataPort {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private com.hmdp.mapper.BlogMapper blogMapper;

    @Resource
    private LocalCacheManager localCacheManager;

    @Override
    public ShopView getShop(Long shopId) {
        if (shopId == null) {
            return null;
        }
        return toView(shopMapper.selectById(shopId));
    }

    @Override
    public List<ShopView> findRecommendCandidates(String category, int limit) {
        List<com.hmdp.entity.Shop> shops = shopMapper.selectRecommendCandidates(category, limit);
        if (shops == null) {
            return Collections.emptyList();
        }
        return shops.stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Override
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
            Integer count = shopMapper.selectCount(new QueryWrapper<com.hmdp.entity.Shop>().eq("id", shopId));
            boolean exists = count != null && count > 0;
            localCacheManager.put(cacheKey, exists, LocalCacheManager.CacheType.SHOP_INFO);
            return exists;
        } catch (Exception e) {
            log.warn("Check shop existence failed, shopId={}", shopId, e);
            return false;
        }
    }

    @Override
    public int getReviewCount(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return 0;
        }
        String cacheKey = LocalCacheManager.CacheKeys.shopReviewCountKey(shopId);
        Integer cachedCount = localCacheManager.get(cacheKey, Integer.class, LocalCacheManager.CacheType.SHOP_STATS);
        if (cachedCount != null) {
            return cachedCount;
        }
        try {
            Integer count = blogMapper.selectCount(new QueryWrapper<com.hmdp.entity.Blog>()
                    .eq("shop_id", shopId)
                    .eq("status", 1)
                    .eq("deleted", 0));
            int reviewCount = count == null ? 0 : count;
            localCacheManager.put(cacheKey, reviewCount, LocalCacheManager.CacheType.SHOP_STATS);
            return reviewCount;
        } catch (Exception e) {
            log.warn("Get shop review count failed, shopId={}", shopId, e);
            return 0;
        }
    }

    private ShopView toView(com.hmdp.entity.Shop shop) {
        if (shop == null) {
            return null;
        }
        return ShopView.builder()
                .id(shop.getId())
                .name(shop.getName())
                .typeId(shop.getTypeId())
                .area(shop.getArea())
                .address(shop.getAddress())
                .avgPrice(shop.getAvgPrice())
                .sold(shop.getSold())
                .comments(shop.getComments())
                .score(shop.getScore())
                .openHours(shop.getOpenHours())
                .build();
    }
}

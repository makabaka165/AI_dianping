package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.ShopTypeVO;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String LOCAL_CACHE_KEY = "shop-type:list";

    private final Cache<String, List<ShopTypeVO>> typeListCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public List<ShopTypeVO> queryTypeList() {
        List<ShopTypeVO> localCached = typeListCache.getIfPresent(LOCAL_CACHE_KEY);
        if (localCached != null) {
            return copyVoList(localCached);
        }

        List<ShopTypeVO> redisCached = queryFromRedis();
        if (redisCached != null) {
            putLocalCache(redisCached);
            return copyVoList(redisCached);
        }

        return refreshTypeListCache();
    }

    @Override
    public void evictTypeListCache() {
        typeListCache.invalidate(LOCAL_CACHE_KEY);
        try {
            stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
        } catch (Exception e) {
            log.warn("删除分类列表Redis缓存失败，key={}", CACHE_SHOP_TYPE_KEY, e);
        }
    }

    @Override
    public List<ShopTypeVO> refreshTypeListCache() {
        List<ShopTypeVO> typeList = queryFromDb();
        putLocalCache(typeList);
        try {
            cacheClient.set(CACHE_SHOP_TYPE_KEY, typeList, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入分类列表Redis缓存失败，key={}", CACHE_SHOP_TYPE_KEY, e);
        }
        return copyVoList(typeList);
    }

    private List<ShopTypeVO> queryFromRedis() {
        try {
            String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return JSONUtil.toList(JSONUtil.parseArray(json), ShopTypeVO.class);
        } catch (Exception e) {
            log.warn("读取分类列表Redis缓存失败，key={}", CACHE_SHOP_TYPE_KEY, e);
            try {
                stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
            } catch (Exception deleteException) {
                log.warn("删除异常分类列表Redis缓存失败，key={}", CACHE_SHOP_TYPE_KEY, deleteException);
            }
            return null;
        }
    }

    private List<ShopTypeVO> queryFromDb() {
        List<ShopType> records = baseMapper.selectList(new QueryWrapper<ShopType>().orderByAsc("sort"));
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    private ShopTypeVO toVO(ShopType shopType) {
        ShopTypeVO vo = new ShopTypeVO();
        vo.setId(shopType.getId());
        vo.setName(shopType.getName());
        vo.setIcon(shopType.getIcon());
        vo.setSort(shopType.getSort());
        return vo;
    }

    private void putLocalCache(List<ShopTypeVO> typeList) {
        typeListCache.put(LOCAL_CACHE_KEY, copyVoList(typeList));
    }

    private List<ShopTypeVO> copyVoList(List<ShopTypeVO> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<ShopTypeVO> result = new ArrayList<>(source.size());
        for (ShopTypeVO item : source) {
            ShopTypeVO copy = new ShopTypeVO();
            copy.setId(item.getId());
            copy.setName(item.getName());
            copy.setIcon(item.getIcon());
            copy.setSort(item.getSort());
            result.add(copy);
        }
        return result;
    }
}

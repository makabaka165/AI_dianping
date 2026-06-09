package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.ShopTypeAdminVO;
import com.hmdp.dto.ShopTypeCreateDTO;
import com.hmdp.dto.ShopTypeStatusDTO;
import com.hmdp.dto.ShopTypeUpdateDTO;
import com.hmdp.dto.ShopTypeVO;
import com.hmdp.entity.ShopType;
import com.hmdp.exception.BusinessException;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_VERSION_KEY;

@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String LOCAL_CACHE_KEY = "shop-type:list";
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final Cache<String, List<ShopTypeVO>> typeListCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private volatile String localTypeListVersion;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public List<ShopTypeVO> queryTypeList() {
        String redisVersion = queryTypeListVersion();
        List<ShopTypeVO> localCached = typeListCache.getIfPresent(LOCAL_CACHE_KEY);
        if (localCached != null && isLocalVersionFresh(redisVersion)) {
            return copyVoList(localCached);
        }

        List<ShopTypeVO> redisCached = queryFromRedis();
        if (redisCached != null) {
            putLocalCache(redisCached, redisVersion);
            return copyVoList(redisCached);
        }

        return refreshTypeListCache(redisVersion);
    }

    @Override
    public void evictTypeListCache() {
        typeListCache.invalidate(LOCAL_CACHE_KEY);
        localTypeListVersion = null;
        try {
            stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
            stringRedisTemplate.opsForValue().increment(CACHE_SHOP_TYPE_VERSION_KEY);
        } catch (Exception e) {
            log.warn("Evict shop type cache failed, key={}", CACHE_SHOP_TYPE_KEY, e);
        }
    }

    @Override
    public List<ShopTypeVO> refreshTypeListCache() {
        return refreshTypeListCache(queryTypeListVersion());
    }

    private List<ShopTypeVO> refreshTypeListCache(String redisVersion) {
        List<ShopTypeVO> typeList = queryFromDb();
        putLocalCache(typeList, redisVersion);
        try {
            cacheClient.set(CACHE_SHOP_TYPE_KEY, typeList, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Write shop type Redis cache failed, key={}", CACHE_SHOP_TYPE_KEY, e);
        }
        return copyVoList(typeList);
    }

    @Override
    public PageResult<ShopTypeAdminVO> queryAdminTypePage(Integer current, Integer size, Integer status) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null ? 10 : Math.min(Math.max(size, 1), 100);
        Page<ShopType> page = baseMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new QueryWrapper<ShopType>()
                        .eq(status != null, "status", status)
                        .orderByAsc("sort")
                        .orderByAsc("id")
        );
        List<ShopTypeAdminVO> records = page.getRecords().stream()
                .map(this::toAdminVO)
                .collect(Collectors.toList());
        return PageResult.of(records, pageNo, pageSize, page.getTotal(), pageNo * pageSize < page.getTotal(), null);
    }

    @Override
    @Transactional
    public ShopTypeAdminVO createType(ShopTypeCreateDTO request) {
        ShopType shopType = new ShopType();
        shopType.setName(request.getName());
        shopType.setIcon(request.getIcon());
        shopType.setSort(request.getSort());
        shopType.setStatus(ENABLED);
        if (!save(shopType)) {
            throw new BusinessException(ErrorCode.SHOP_TYPE_UPDATE_FAILED, "shop type create failed");
        }
        evictTypeListCacheAfterCommit();
        return toAdminVO(shopType);
    }

    @Override
    @Transactional
    public ShopTypeAdminVO updateType(ShopTypeUpdateDTO request) {
        requireType(request.getId());
        ShopType shopType = new ShopType();
        shopType.setId(request.getId());
        shopType.setName(request.getName());
        shopType.setIcon(request.getIcon());
        shopType.setSort(request.getSort());
        if (!updateById(shopType)) {
            throw new BusinessException(ErrorCode.SHOP_TYPE_UPDATE_FAILED, "shop type update failed");
        }
        evictTypeListCacheAfterCommit();
        ShopType latest = getById(request.getId());
        return toAdminVO(latest == null ? shopType : latest);
    }

    @Override
    @Transactional
    public void updateTypeStatus(ShopTypeStatusDTO request) {
        validateStatus(request.getStatus());
        requireType(request.getId());
        boolean updated = update(null, new UpdateWrapper<ShopType>()
                .set("status", request.getStatus())
                .eq("id", request.getId()));
        if (!updated) {
            throw new BusinessException(ErrorCode.SHOP_TYPE_UPDATE_FAILED, "shop type status update failed");
        }
        evictTypeListCacheAfterCommit();
    }

    @Override
    @Transactional
    public void deleteType(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "id must be greater than 0");
        }
        requireType(id);
        boolean updated = update(null, new UpdateWrapper<ShopType>()
                .set("status", DISABLED)
                .eq("id", id));
        if (!updated) {
            throw new BusinessException(ErrorCode.SHOP_TYPE_UPDATE_FAILED, "shop type delete failed");
        }
        evictTypeListCacheAfterCommit();
    }

    private List<ShopTypeVO> queryFromRedis() {
        try {
            String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return JSONUtil.toList(JSONUtil.parseArray(json), ShopTypeVO.class);
        } catch (Exception e) {
            log.warn("Read shop type Redis cache failed, key={}", CACHE_SHOP_TYPE_KEY, e);
            try {
                stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
            } catch (Exception deleteException) {
                log.warn("Delete broken shop type Redis cache failed, key={}", CACHE_SHOP_TYPE_KEY, deleteException);
            }
            return null;
        }
    }

    private List<ShopTypeVO> queryFromDb() {
        List<ShopType> records = baseMapper.selectList(new QueryWrapper<ShopType>()
                .eq("status", ENABLED)
                .orderByAsc("sort"));
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

    private ShopTypeAdminVO toAdminVO(ShopType shopType) {
        ShopTypeAdminVO vo = new ShopTypeAdminVO();
        vo.setId(shopType.getId());
        vo.setName(shopType.getName());
        vo.setIcon(shopType.getIcon());
        vo.setSort(shopType.getSort());
        vo.setStatus(shopType.getStatus());
        return vo;
    }

    private void putLocalCache(List<ShopTypeVO> typeList, String redisVersion) {
        typeListCache.put(LOCAL_CACHE_KEY, copyVoList(typeList));
        localTypeListVersion = redisVersion;
    }

    private boolean isLocalVersionFresh(String redisVersion) {
        return redisVersion == null || redisVersion.equals(localTypeListVersion);
    }

    private String queryTypeListVersion() {
        try {
            return stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_VERSION_KEY);
        } catch (Exception e) {
            log.warn("Read shop type cache version failed, key={}", CACHE_SHOP_TYPE_VERSION_KEY, e);
            return null;
        }
    }

    private void evictTypeListCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictTypeListCache();
                }
            });
            return;
        }
        evictTypeListCache();
    }

    private ShopType requireType(Long id) {
        ShopType shopType = getById(id);
        if (shopType == null) {
            throw new BusinessException(ErrorCode.SHOP_TYPE_NOT_FOUND, "shop type does not exist");
        }
        return shopType;
    }

    private void validateStatus(Integer status) {
        if (!Integer.valueOf(ENABLED).equals(status) && !Integer.valueOf(DISABLED).equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status only supports 1 enabled or 0 disabled");
        }
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

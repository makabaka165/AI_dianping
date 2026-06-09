package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.ShopTypeAdminVO;
import com.hmdp.dto.ShopTypeCreateDTO;
import com.hmdp.dto.ShopTypeStatusDTO;
import com.hmdp.dto.ShopTypeVO;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_VERSION_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopTypeServiceImplTest {

    @Mock
    private ShopTypeMapper shopTypeMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CacheClient cacheClient;

    private ShopTypeServiceImpl shopTypeService;

    @BeforeEach
    void setUp() {
        shopTypeService = new ShopTypeServiceImpl();
        ReflectionTestUtils.setField(shopTypeService, "baseMapper", shopTypeMapper);
        ReflectionTestUtils.setField(shopTypeService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(shopTypeService, "cacheClient", cacheClient);
    }

    @Test
    void queryTypeListShouldUseLocalCacheFirst() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(List.of(shopType(1L, "美食", 1)));

        List<ShopTypeVO> first = shopTypeService.queryTypeList();
        clearInvocations(valueOperations, shopTypeMapper, cacheClient);
        List<ShopTypeVO> second = shopTypeService.queryTypeList();

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        verify(valueOperations, never()).get(CACHE_SHOP_TYPE_KEY);
        verify(shopTypeMapper, never()).selectList(any());
        verify(cacheClient, never()).set(eq(CACHE_SHOP_TYPE_KEY), any(), eq(CACHE_SHOP_TYPE_TTL), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryTypeListShouldLoadFromRedisAndBackfillLocalCache() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenReturn(JSONUtil.toJsonStr(List.of(shopTypeVO(1L, "美食", 1))));

        List<ShopTypeVO> first = shopTypeService.queryTypeList();
        clearInvocations(valueOperations, shopTypeMapper);
        List<ShopTypeVO> second = shopTypeService.queryTypeList();

        assertThat(first).extracting(ShopTypeVO::getName).containsExactly("美食");
        assertThat(second).extracting(ShopTypeVO::getName).containsExactly("美食");
        verify(valueOperations, never()).get(CACHE_SHOP_TYPE_KEY);
        verify(shopTypeMapper, never()).selectList(any());
    }

    @Test
    void queryTypeListShouldLoadFromDbAndWriteCachesWhenRedisMisses() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(List.of(
                shopType(1L, "美食", 1),
                shopType(2L, "KTV", 2)
        ));

        List<ShopTypeVO> result = shopTypeService.queryTypeList();

        assertThat(result).extracting(ShopTypeVO::getName).containsExactly("美食", "KTV");
        ArgumentCaptor<List<ShopTypeVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(cacheClient).set(eq(CACHE_SHOP_TYPE_KEY), captor.capture(), eq(CACHE_SHOP_TYPE_TTL), eq(TimeUnit.MINUTES));
        assertThat(captor.getValue()).extracting(ShopTypeVO::getIcon).containsExactly("/types/1.png", "/types/2.png");
    }

    @Test
    void evictTypeListCacheShouldRemoveRedisAndLocalCache() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenReturn(null);
        when(shopTypeMapper.selectList(any()))
                .thenReturn(List.of(shopType(1L, "美食", 1)))
                .thenReturn(Collections.emptyList());

        shopTypeService.queryTypeList();
        shopTypeService.evictTypeListCache();
        clearInvocations(valueOperations, shopTypeMapper, cacheClient, stringRedisTemplate);
        List<ShopTypeVO> result = shopTypeService.queryTypeList();

        assertThat(result).isEmpty();
        verify(shopTypeMapper).selectList(any());
        verify(stringRedisTemplate, never()).delete(CACHE_SHOP_TYPE_KEY);
    }

    @Test
    void evictTypeListCacheShouldDeleteRedisKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        shopTypeService.evictTypeListCache();

        verify(stringRedisTemplate).delete(CACHE_SHOP_TYPE_KEY);
        verify(valueOperations).increment(CACHE_SHOP_TYPE_VERSION_KEY);
    }

    @Test
    void queryTypeListShouldFallbackToDbWhenRedisFails() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenThrow(new RuntimeException("redis down"));
        when(shopTypeMapper.selectList(any())).thenReturn(List.of(shopType(1L, "美食", 1)));

        List<ShopTypeVO> result = shopTypeService.queryTypeList();

        assertThat(result).extracting(ShopTypeVO::getName).containsExactly("美食");
        verify(stringRedisTemplate).delete(CACHE_SHOP_TYPE_KEY);
        verify(cacheClient).set(eq(CACHE_SHOP_TYPE_KEY), any(), eq(CACHE_SHOP_TYPE_TTL), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryTypeListShouldInvalidateLocalCacheWhenRedisVersionChanges() {
        stubRedisValueOperations();
        when(valueOperations.get(CACHE_SHOP_TYPE_VERSION_KEY)).thenReturn("1", "2");
        when(valueOperations.get(CACHE_SHOP_TYPE_KEY)).thenReturn(null);
        when(shopTypeMapper.selectList(any()))
                .thenReturn(List.of(shopType(1L, "Food", 1)))
                .thenReturn(List.of(shopType(2L, "Tea", 2)));

        List<ShopTypeVO> first = shopTypeService.queryTypeList();
        List<ShopTypeVO> second = shopTypeService.queryTypeList();

        assertThat(first).extracting(ShopTypeVO::getName).containsExactly("Food");
        assertThat(second).extracting(ShopTypeVO::getName).containsExactly("Tea");
        verify(shopTypeMapper, org.mockito.Mockito.times(2)).selectList(any());
    }

    @Test
    void createTypeShouldEvictRedisAndIncrementVersion() {
        stubRedisValueOperations();
        when(shopTypeMapper.insert(any(ShopType.class))).thenAnswer(invocation -> {
            ShopType shopType = invocation.getArgument(0);
            shopType.setId(10L);
            return 1;
        });

        ShopTypeAdminVO result = shopTypeService.createType(createTypeRequest());

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo(1);
        verify(stringRedisTemplate).delete(CACHE_SHOP_TYPE_KEY);
        verify(valueOperations).increment(CACHE_SHOP_TYPE_VERSION_KEY);
    }

    @Test
    void updateTypeStatusShouldEvictRedisAndIncrementVersion() {
        stubRedisValueOperations();
        when(shopTypeMapper.selectById(10L)).thenReturn(shopType(10L, "Food", 1));
        when(shopTypeMapper.update(eq(null), any())).thenReturn(1);
        ShopTypeStatusDTO request = new ShopTypeStatusDTO();
        request.setId(10L);
        request.setStatus(0);

        shopTypeService.updateTypeStatus(request);

        verify(stringRedisTemplate).delete(CACHE_SHOP_TYPE_KEY);
        verify(valueOperations).increment(CACHE_SHOP_TYPE_VERSION_KEY);
    }

    @Test
    void deleteTypeShouldSoftDeleteAndEvictRedisVersion() {
        stubRedisValueOperations();
        when(shopTypeMapper.selectById(10L)).thenReturn(shopType(10L, "Food", 1));
        when(shopTypeMapper.update(eq(null), any())).thenReturn(1);

        shopTypeService.deleteType(10L);

        verify(stringRedisTemplate).delete(CACHE_SHOP_TYPE_KEY);
        verify(valueOperations).increment(CACHE_SHOP_TYPE_VERSION_KEY);
    }

    private ShopType shopType(Long id, String name, Integer sort) {
        ShopType shopType = new ShopType();
        shopType.setId(id);
        shopType.setName(name);
        shopType.setIcon("/types/" + id + ".png");
        shopType.setSort(sort);
        return shopType;
    }

    private ShopTypeVO shopTypeVO(Long id, String name, Integer sort) {
        ShopTypeVO vo = new ShopTypeVO();
        vo.setId(id);
        vo.setName(name);
        vo.setIcon("/types/" + id + ".png");
        vo.setSort(sort);
        return vo;
    }

    private ShopTypeCreateDTO createTypeRequest() {
        ShopTypeCreateDTO request = new ShopTypeCreateDTO();
        request.setName("Food");
        request.setIcon("/types/10.png");
        request.setSort(1);
        return request;
    }

    private void stubRedisValueOperations() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(CACHE_SHOP_TYPE_VERSION_KEY)).thenReturn("1");
    }
}

package com.hmdp.service;

import com.hmdp.dto.ShopGeoRebuildResult;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopGeoIndexServiceTest {

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    private TestableShopGeoIndexService shopGeoIndexService;

    @BeforeEach
    void setUp() {
        shopGeoIndexService = new TestableShopGeoIndexService();
        ReflectionTestUtils.setField(shopGeoIndexService, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(shopGeoIndexService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void rebuildByTypeIdShouldDeleteTypeKeyAndWriteOnlyIndexableShops() {
        when(stringRedisTemplate.delete(SHOP_GEO_KEY + 1L)).thenReturn(true);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(shopMapper.selectList(any())).thenReturn(List.of(
                shop(1L, 1L, 120.1, 30.2),
                shop(2L, 1L, null, 30.3),
                shop(3L, 2L, 120.3, 30.4)
        ));

        ShopGeoRebuildResult result = shopGeoIndexService.rebuildByTypeId(1L);

        assertThat(result.getTypeId()).isEqualTo(1L);
        assertThat(result.getRebuiltTypes()).isEqualTo(1);
        assertThat(result.getDeletedKeys()).isEqualTo(1);
        assertThat(result.getTotalShops()).isEqualTo(3);
        assertThat(result.getIndexedShops()).isEqualTo(1);
        assertThat(result.getSkippedShops()).isEqualTo(2);

        ArgumentCaptor<Iterable<RedisGeoCommands.GeoLocation<String>>> locationsCaptor = geoLocationsCaptor();
        verify(geoOperations).add(eq(SHOP_GEO_KEY + 1L), locationsCaptor.capture());
        List<RedisGeoCommands.GeoLocation<String>> locations = toList(locationsCaptor.getValue());
        assertThat(locations).hasSize(1);
        assertThat(locations.get(0).getName()).isEqualTo("1");
        assertThat(locations.get(0).getPoint().getX()).isEqualTo(120.1);
        assertThat(locations.get(0).getPoint().getY()).isEqualTo(30.2);
    }

    @Test
    void rebuildByTypeIdShouldRejectInvalidTypeId() {
        assertThatThrownBy(() -> shopGeoIndexService.rebuildByTypeId(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("typeId must be greater than 0");

        verify(shopMapper, never()).selectList(any());
        verify(stringRedisTemplate, never()).opsForGeo();
    }

    @Test
    void rebuildAllShouldDeleteScannedKeysAndGroupLocationsByType() {
        Set<String> scannedKeys = new LinkedHashSet<>(List.of(SHOP_GEO_KEY + 1L, SHOP_GEO_KEY + 9L));
        shopGeoIndexService.setScannedGeoKeys(scannedKeys);
        when(stringRedisTemplate.delete(scannedKeys)).thenReturn(2L);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(shopMapper.selectList(any())).thenReturn(List.of(
                shop(1L, 1L, 120.1, 30.2),
                shop(2L, 1L, 120.2, 30.3),
                shop(3L, 2L, 120.3, 30.4),
                shop(4L, 2L, 200.0, 30.5)
        ));

        ShopGeoRebuildResult result = shopGeoIndexService.rebuildAll();

        assertThat(result.getTypeId()).isNull();
        assertThat(result.getDeletedKeys()).isEqualTo(2);
        assertThat(result.getRebuiltTypes()).isEqualTo(2);
        assertThat(result.getTotalShops()).isEqualTo(4);
        assertThat(result.getIndexedShops()).isEqualTo(3);
        assertThat(result.getSkippedShops()).isEqualTo(1);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Iterable<RedisGeoCommands.GeoLocation<String>>> locationsCaptor = geoLocationsCaptor();
        verify(geoOperations, times(2)).add(keyCaptor.capture(), locationsCaptor.capture());
        assertThat(keyCaptor.getAllValues()).containsExactly(SHOP_GEO_KEY + 1L, SHOP_GEO_KEY + 2L);
        assertThat(toList(locationsCaptor.getAllValues().get(0))).extracting(RedisGeoCommands.GeoLocation::getName)
                .containsExactly("1", "2");
        assertThat(toList(locationsCaptor.getAllValues().get(1))).extracting(RedisGeoCommands.GeoLocation::getName)
                .containsExactly("3");
    }

    @Test
    void refreshShopGeoIndexShouldRemoveOldTypeAndAddLatestType() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        Shop oldShop = shop(10L, 1L, 120.1, 30.2);
        Shop latestShop = shop(10L, 2L, 121.1, 31.2);

        shopGeoIndexService.refreshShopGeoIndex(oldShop, latestShop);

        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        verify(geoOperations).remove(SHOP_GEO_KEY + 1L, "10");
        verify(geoOperations).add(eq(SHOP_GEO_KEY + 2L), pointCaptor.capture(), eq("10"));
        assertThat(pointCaptor.getValue().getX()).isEqualTo(121.1);
        assertThat(pointCaptor.getValue().getY()).isEqualTo(31.2);
    }

    @Test
    void addOrUpdateShopShouldSkipInvalidCoordinates() {
        shopGeoIndexService.addOrUpdateShop(shop(10L, 1L, 181D, 31.2));

        verify(stringRedisTemplate, never()).opsForGeo();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Iterable<RedisGeoCommands.GeoLocation<String>>> geoLocationsCaptor() {
        return ArgumentCaptor.forClass((Class) Iterable.class);
    }

    private List<RedisGeoCommands.GeoLocation<String>> toList(
            Iterable<RedisGeoCommands.GeoLocation<String>> locations) {
        List<RedisGeoCommands.GeoLocation<String>> result = new ArrayList<>();
        for (RedisGeoCommands.GeoLocation<String> location : locations) {
            result.add(location);
        }
        return result;
    }

    private Shop shop(Long id, Long typeId, Double x, Double y) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setTypeId(typeId);
        shop.setX(x);
        shop.setY(y);
        return shop;
    }

    private static class TestableShopGeoIndexService extends ShopGeoIndexService {

        private Set<String> scannedGeoKeys = Set.of();

        void setScannedGeoKeys(Set<String> scannedGeoKeys) {
            this.scannedGeoKeys = scannedGeoKeys;
        }

        @Override
        protected Set<String> scanGeoKeys() {
            return scannedGeoKeys;
        }
    }
}

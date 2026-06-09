package com.hmdp.service.impl;

import com.hmdp.common.ErrorCode;
import com.hmdp.dto.NearbyShopResult;
import com.hmdp.dto.NearbyShopVO;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metric;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplNearbyTest {

    private static final Metric METERS = new Metric() {
        private static final long serialVersionUID = 1L;

        @Override
        public double getMultiplier() {
            return 6378137D;
        }

        @Override
        public String getAbbreviation() {
            return "m";
        }
    };

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    private TestableShopServiceImpl shopService;

    @BeforeEach
    void setUp() {
        shopService = new TestableShopServiceImpl();
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void queryShopByTypeShouldRejectInvalidParams() {
        Result result = shopService.queryShopByType(1, 0, 120.15, 30.31, null, null, "distance");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    void geoPageQueryShouldUseMeterMetricAndKeepDistanceOrder() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(geo("1", 100D), geo("2", 200D))));
        shopService.setDbShops(List.of(shop(1L, 1L, 40, 10), shop(2L, 1L, 45, 20)));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null, "distance");

        ArgumentCaptor<Distance> distanceCaptor = ArgumentCaptor.forClass(Distance.class);
        verify(geoOperations).search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), distanceCaptor.capture(), any());
        Distance distance = distanceCaptor.getValue();
        Metric metric = distance.getMetric();
        assertThat(distance.getValue()).isEqualTo(5000D);
        assertThat(metric.getAbbreviation()).isEqualTo("m");

        List<NearbyShopVO> shops = dataAsList(result);
        assertThat(shops).extracting(NearbyShopVO::getId).containsExactly(1L, 2L);
        assertThat(shops).extracting(NearbyShopVO::getDistance).containsExactly(100D, 200D);
    }

    @Test
    void geoPageQueryShouldFilterAndRemoveStaleIds() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(geo("1", 100D), geo("99", 150D), geo("2", 200D))));
        shopService.setDbShops(List.of(shop(1L, 1L, 40, 10), shop(2L, 2L, 45, 20)));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null, "distance");

        List<NearbyShopVO> shops = dataAsList(result);
        assertThat(shops).extracting(NearbyShopVO::getId).containsExactly(1L);
        verify(geoOperations).remove(SHOP_GEO_KEY + 1, "99", "2");
    }

    @Test
    void cursorQueryShouldSkipPreviousCursorAndReturnNextCursor() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(
                        geo("1", 100D), geo("2", 200D), geo("3", 300D), geo("4", 400D),
                        geo("5", 500D), geo("6", 600D), geo("7", 700D), geo("8", 800D),
                        geo("9", 900D), geo("10", 1000D), geo("11", 1100D)
                )));
        shopService.setDbShops(List.of(
                shop(1L, 1L, 40, 10), shop(2L, 1L, 40, 10), shop(3L, 1L, 40, 10),
                shop(4L, 1L, 40, 10), shop(5L, 1L, 40, 10), shop(6L, 1L, 40, 10),
                shop(7L, 1L, 40, 10), shop(8L, 1L, 40, 10), shop(9L, 1L, 40, 10),
                shop(10L, 1L, 40, 10), shop(11L, 1L, 40, 10)
        ));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, 200D, 2L, "distance");

        NearbyShopResult nearby = (NearbyShopResult) result.getData();
        assertThat(nearby.getList()).extracting(NearbyShopVO::getId).containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(nearby.getLastDistance()).isEqualTo(700D);
        assertThat(nearby.getLastId()).isEqualTo(7L);
        assertThat(nearby.getHasMore()).isTrue();
    }

    @Test
    void scoreSortShouldSortOnlyWithinDistanceCandidateSet() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(geo("1", 100D), geo("2", 200D), geo("3", 300D))));
        shopService.setDbShops(List.of(
                shop(1L, 1L, 40, 10),
                shop(2L, 1L, 50, 5),
                shop(3L, 1L, 45, 20)
        ));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null, "score");

        List<NearbyShopVO> shops = dataAsList(result);
        assertThat(shops).extracting(NearbyShopVO::getId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void cursorQueryWithScoreSortShouldReturnDistanceCursor() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(
                        geo("1", 100D), geo("2", 200D), geo("3", 300D), geo("4", 400D),
                        geo("5", 500D), geo("6", 600D)
                )));
        shopService.setDbShops(List.of(
                shop(1L, 1L, 10, 10),
                shop(2L, 1L, 60, 10),
                shop(3L, 1L, 50, 10),
                shop(4L, 1L, 40, 10),
                shop(5L, 1L, 30, 10),
                shop(6L, 1L, 20, 10)
        ));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, 0D, 1L, "score");

        NearbyShopResult nearby = (NearbyShopResult) result.getData();
        assertThat(nearby.getList()).extracting(NearbyShopVO::getId).containsExactly(2L, 3L, 4L, 5L, 1L);
        assertThat(nearby.getLastDistance()).isEqualTo(500D);
        assertThat(nearby.getLastId()).isEqualTo(5L);
    }

    @Test
    void soldSortShouldSortOnlyWithinDistanceCandidateSet() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(geo("1", 100D), geo("2", 200D), geo("3", 300D))));
        shopService.setDbShops(List.of(
                shop(1L, 1L, 40, 10),
                shop(2L, 1L, 50, 5),
                shop(3L, 1L, 45, 20)
        ));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null, "sold");

        List<NearbyShopVO> shops = dataAsList(result);
        assertThat(shops).extracting(NearbyShopVO::getId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void geoPageQueryShouldApplyBusinessFiltersWithinCandidateSet() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(
                        geo("1", 100D), geo("2", 200D), geo("3", 300D), geo("4", 400D)
                )));
        shopService.setDbShops(List.of(
                shop(1L, 1L, 45, 10, "Hot Noodles", "A", 80L, "00:00-24:00"),
                shop(2L, 1L, 45, 10, "Tea House", "A", 80L, "00:00-24:00"),
                shop(3L, 1L, 35, 10, "Hot Noodles Low Score", "A", 80L, "00:00-24:00"),
                shop(4L, 1L, 45, 10, "Hot Noodles Closed", "A", 120L, "08:00-08:01")
        ));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null,
                "distance", "noodles", "A", 40, 50L, 100L, true, false);

        List<NearbyShopVO> shops = dataAsList(result);
        assertThat(shops).extracting(NearbyShopVO::getId).containsExactly(1L);
    }

    @Test
    void geoPageQueryShouldReturnPageResultWhenRequested() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq(SHOP_GEO_KEY + 1), any(GeoReference.class), any(Distance.class), any()))
                .thenReturn(geoResults(List.of(geo("1", 100D), geo("2", 200D))));
        shopService.setDbShops(List.of(shop(1L, 1L, 40, 10), shop(2L, 1L, 45, 20)));

        Result result = shopService.queryShopByType(1, 1, 120.15, 30.31, null, null,
                "distance", null, null, null, null, null, false, true);

        @SuppressWarnings("unchecked")
        PageResult<NearbyShopVO> page = (PageResult<NearbyShopVO>) result.getData();
        assertThat(page.getList()).extracting(NearbyShopVO::getId).containsExactly(1L, 2L);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(5);
        assertThat(page.getHasMore()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private List<NearbyShopVO> dataAsList(Result result) {
        return (List<NearbyShopVO>) result.getData();
    }

    private GeoResult<RedisGeoCommands.GeoLocation<String>> geo(String id, Double distance) {
        RedisGeoCommands.GeoLocation<String> location =
                new RedisGeoCommands.GeoLocation<>(id, new Point(120D, 30D));
        return new GeoResult<>(location, new Distance(distance, METERS));
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results) {
        return new GeoResults<>(results);
    }

    private Shop shop(Long id, Long typeId, Integer score, Integer sold) {
        return shop(id, typeId, score, sold, "shop-" + id, "area", 50L, "00:00-24:00");
    }

    private Shop shop(Long id, Long typeId, Integer score, Integer sold,
                      String name, String area, Long avgPrice, String openHours) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setTypeId(typeId);
        shop.setName(name);
        shop.setAddress("address-" + id);
        shop.setArea(area);
        shop.setAvgPrice(avgPrice);
        shop.setOpenHours(openHours);
        shop.setScore(score);
        shop.setSold(sold);
        return shop;
    }

    private static class TestableShopServiceImpl extends ShopServiceImpl {
        private Map<Long, Shop> dbShopMap = Map.of();

        void setDbShops(List<Shop> shops) {
            Map<Long, Shop> map = new java.util.LinkedHashMap<>();
            for (Shop shop : shops) {
                map.put(shop.getId(), shop);
            }
            dbShopMap = map;
        }

        @Override
        protected List<Shop> queryShopsByIds(List<Long> ids) {
            List<Shop> shops = new ArrayList<>();
            for (Long id : ids) {
                Shop shop = dbShopMap.get(id);
                if (shop != null) {
                    shops.add(shop);
                }
            }
            return shops;
        }
    }
}

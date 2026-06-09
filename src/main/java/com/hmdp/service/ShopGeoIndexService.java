package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.ShopGeoRebuildResult;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopGeoIndexService {

    private static final long GEO_SCAN_COUNT = 1000L;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public ShopGeoRebuildResult rebuildByTypeId(Long typeId) {
        if (typeId == null || typeId <= 0) {
            throw new IllegalArgumentException("typeId must be greater than 0");
        }

        String key = geoKey(typeId);
        ShopGeoRebuildResult result = new ShopGeoRebuildResult();
        result.setTypeId(typeId);
        result.setRebuiltTypes(1);

        List<Shop> shops = queryShopsByType(typeId);
        result.setTotalShops(shops.size());
        List<RedisGeoCommands.GeoLocation<String>> locations = buildLocations(shops, typeId, result);
        result.setDeletedKeys(deleteGeoKey(key));
        addLocations(key, locations);

        log.info("Rebuilt shop GEO index by type, typeId={}, indexed={}, skipped={}",
                typeId, result.getIndexedShops(), result.getSkippedShops());
        return result;
    }

    public ShopGeoRebuildResult rebuildAll() {
        ShopGeoRebuildResult result = new ShopGeoRebuildResult();
        List<Shop> shops = queryAllShops();
        result.setTotalShops(shops.size());

        Map<Long, List<RedisGeoCommands.GeoLocation<String>>> locationsByType = new LinkedHashMap<>();
        for (Shop shop : shops) {
            if (!isIndexable(shop)) {
                incrementSkipped(result);
                continue;
            }
            locationsByType
                    .computeIfAbsent(shop.getTypeId(), key -> new ArrayList<>())
                    .add(toLocation(shop));
            incrementIndexed(result);
        }

        Set<String> existingKeys = scanGeoKeys();
        result.setDeletedKeys(deleteGeoKeys(existingKeys));
        for (Map.Entry<Long, List<RedisGeoCommands.GeoLocation<String>>> entry : locationsByType.entrySet()) {
            addLocations(geoKey(entry.getKey()), entry.getValue());
        }
        result.setRebuiltTypes(locationsByType.size());

        log.info("Rebuilt all shop GEO indexes, types={}, indexed={}, skipped={}, deletedKeys={}",
                result.getRebuiltTypes(), result.getIndexedShops(), result.getSkippedShops(), result.getDeletedKeys());
        return result;
    }

    public void refreshShopGeoIndex(Shop oldShop, Shop latestShop) {
        removeShopFromGeo(oldShop);
        addOrUpdateShop(latestShop);
    }

    public void addOrUpdateShop(Shop shop) {
        if (!isIndexable(shop)) {
            log.warn("Skip shop GEO sync because shop is not indexable, shopId={}", shop == null ? null : shop.getId());
            return;
        }
        stringRedisTemplate.opsForGeo().add(
                geoKey(shop.getTypeId()),
                new Point(shop.getX(), shop.getY()),
                shop.getId().toString()
        );
    }

    public void removeShopFromGeo(Shop shop) {
        if (shop == null || shop.getTypeId() == null || shop.getId() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().remove(geoKey(shop.getTypeId()), shop.getId().toString());
    }

    protected List<Shop> queryAllShops() {
        List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>().isNotNull("type_id"));
        return shops == null ? Collections.emptyList() : shops;
    }

    protected List<Shop> queryShopsByType(Long typeId) {
        List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>().eq("type_id", typeId));
        return shops == null ? Collections.emptyList() : shops;
    }

    protected Set<String> scanGeoKeys() {
        Set<String> keys = stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(SHOP_GEO_KEY + "*")
                    .count(GEO_SCAN_COUNT)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
        return keys == null ? Collections.emptySet() : keys;
    }

    private List<RedisGeoCommands.GeoLocation<String>> buildLocations(
            List<Shop> shops, Long expectedTypeId, ShopGeoRebuildResult result) {
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
        for (Shop shop : shops) {
            if (!isIndexable(shop) || !expectedTypeId.equals(shop.getTypeId())) {
                incrementSkipped(result);
                continue;
            }
            locations.add(toLocation(shop));
            incrementIndexed(result);
        }
        return locations;
    }

    private RedisGeoCommands.GeoLocation<String> toLocation(Shop shop) {
        return new RedisGeoCommands.GeoLocation<>(
                shop.getId().toString(),
                new Point(shop.getX(), shop.getY())
        );
    }

    private void addLocations(String key, Collection<RedisGeoCommands.GeoLocation<String>> locations) {
        if (locations == null || locations.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(key, locations);
    }

    private int deleteGeoKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key)) ? 1 : 0;
    }

    private int deleteGeoKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = stringRedisTemplate.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    private boolean isIndexable(Shop shop) {
        return shop != null
                && shop.getId() != null
                && shop.getId() > 0
                && shop.getTypeId() != null
                && shop.getTypeId() > 0
                && isLongitude(shop.getX())
                && isLatitude(shop.getY());
    }

    private boolean isLongitude(Double value) {
        return value != null && value >= -180D && value <= 180D;
    }

    private boolean isLatitude(Double value) {
        return value != null && value >= -90D && value <= 90D;
    }

    private String geoKey(Long typeId) {
        return SHOP_GEO_KEY + typeId;
    }

    private void incrementIndexed(ShopGeoRebuildResult result) {
        result.setIndexedShops(result.getIndexedShops() + 1);
    }

    private void incrementSkipped(ShopGeoRebuildResult result) {
        result.setSkippedShops(result.getSkippedShops() + 1);
    }
}

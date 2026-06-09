package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.NearbyShopResult;
import com.hmdp.dto.NearbyShopVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCreateDTO;
import com.hmdp.dto.ShopDetailVO;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopStatsService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final int MAX_NEARBY_PAGE = 20;
    private static final double NEARBY_RADIUS_KILOMETERS = 5D;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private IPermissionService permissionService;

    @Resource
    private IMerchantShopService merchantShopService;

    @Resource
    private ShopStatsService shopStatsService;

    @Override
    public Result queryById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "id must be greater than 0");
        }
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail(ErrorCode.NOT_FOUND, "shop does not exist");
        }
        return Result.ok(toDetailVO(shop));
    }

    @Override
    @Transactional
    public Result createShop(ShopCreateDTO request) {
        Shop shop = toCreateEntity(request);
        boolean saved = save(shop);
        if (!saved) {
            return Result.fail("shop create failed");
        }
        runAfterCommit(() -> {
            addShopToGeo(shop);
            updateShopExistsCache(shop.getId(), true);
        });
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result updateShop(ShopUpdateDTO request) {
        Long id = request.getId();
        Long userId = currentUserService.requireCurrentUserId();
        boolean admin = permissionService.hasRole(userId, "admin");
        if (!admin && !merchantShopService.isShopOwner(userId, id)) {
            return Result.fail(ErrorCode.FORBIDDEN, "no permission to update this shop");
        }

        Shop oldShop = getById(id);
        if (oldShop == null) {
            return Result.fail(ErrorCode.NOT_FOUND, "shop does not exist");
        }

        Shop shop = toUpdateEntity(request);
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("shop update failed");
        }

        runAfterCommit(() -> {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            refreshShopGeoIndex(oldShop, id);
            updateShopExistsCache(id, true);
        });
        return Result.ok();
    }

    @Override
    public Result queryShopStatus(Long id) {
        if (id == null || id <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "id must be greater than 0");
        }
        return Result.ok(buildShopStatusVO(id));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y,
                                  Double lastDistance, Long lastId, String sortBy) {
        String validationError = validateShopTypeQuery(typeId, current, x, y, lastDistance, lastId, sortBy);
        if (validationError != null) {
            return Result.fail(ErrorCode.PARAM_ERROR, validationError);
        }

        if (x == null && y == null) {
            return queryShopByTypeFromDb(typeId, current);
        }

        String normalizedSortBy = normalizeSortBy(sortBy);
        if (lastDistance != null && lastId != null) {
            return queryNearbyShopsByCursor(typeId, x, y, lastDistance, lastId, normalizedSortBy);
        }
        return queryNearbyShopsByPage(typeId, current, x, y, normalizedSortBy);
    }

    private Result queryShopByTypeFromDb(Integer typeId, Integer current) {
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(toNearbyVOList(page.getRecords()));
    }

    private Result queryNearbyShopsByPage(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = searchGeo(typeId, x, y, end);
        if (geoResults.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageResults = geoResults.subList(from, geoResults.size());
        List<Shop> shops = buildNearbyShopList(typeId, pageResults, false, null, null, sortBy);
        return Result.ok(toNearbyVOList(shops));
    }

    private Result queryNearbyShopsByCursor(Integer typeId, Double x, Double y,
                                            Double lastDistance, Long lastId, String sortBy) {
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        int limit = pageSize * 3 + 1;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = searchGeo(typeId, x, y, limit);
        List<Shop> distanceOrderedShops = buildNearbyShopList(
                typeId, geoResults, true, lastDistance, lastId, "distance");

        boolean hasMore = distanceOrderedShops.size() > pageSize;
        List<Shop> pageWindow = distanceOrderedShops;
        if (hasMore) {
            pageWindow = new ArrayList<>(distanceOrderedShops.subList(0, pageSize));
        }

        List<Shop> displayShops = new ArrayList<>(pageWindow);
        sortNearbyShops(displayShops, sortBy);

        NearbyShopResult result = new NearbyShopResult();
        result.setList(toNearbyVOList(displayShops));
        result.setHasMore(hasMore);
        if (!pageWindow.isEmpty()) {
            Shop lastShop = pageWindow.get(pageWindow.size() - 1);
            result.setLastDistance(lastShop.getDistance());
            result.setLastId(lastShop.getId());
        }
        return Result.ok(result);
    }

    private List<GeoResult<RedisGeoCommands.GeoLocation<String>>> searchGeo(Integer typeId, Double x, Double y, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        SHOP_GEO_KEY + typeId,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(NEARBY_RADIUS_KILOMETERS, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(limit)
                );
        if (results == null) {
            return Collections.emptyList();
        }
        return results.getContent();
    }

    private List<Shop> buildNearbyShopList(Integer typeId,
                                           List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults,
                                           boolean cursorMode,
                                           Double lastDistance,
                                           Long lastId,
                                           String sortBy) {
        if (geoResults == null || geoResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<>(geoResults.size());
        Map<Long, Double> distanceMap = new HashMap<>(geoResults.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : geoResults) {
            Long shopId = parseShopId(result.getContent().getName());
            if (shopId == null) {
                continue;
            }
            double distance = result.getDistance().getValue();
            if (cursorMode && isBeforeOrAtCursor(distance, shopId, lastDistance, lastId)) {
                continue;
            }
            ids.add(shopId);
            distanceMap.put(shopId, distance);
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Shop> shops = queryShopsByIds(ids);
        Map<Long, Shop> shopMap = new HashMap<>(shops.size());
        for (Shop shop : shops) {
            shopMap.put(shop.getId(), shop);
        }

        List<Long> staleIds = new ArrayList<>();
        List<Shop> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Shop shop = shopMap.get(id);
            if (shop == null || shop.getTypeId() == null || !shop.getTypeId().equals(typeId.longValue())) {
                staleIds.add(id);
                continue;
            }
            shop.setDistance(distanceMap.get(id));
            result.add(shop);
        }
        removeStaleGeoMembers(typeId, staleIds);
        sortNearbyShops(result, sortBy);
        return result;
    }

    protected List<Shop> queryShopsByIds(List<Long> ids) {
        String idStr = StrUtil.join(",", ids);
        return query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
    }

    private void sortNearbyShops(List<Shop> shops, String sortBy) {
        if ("score".equals(sortBy)) {
            shops.sort(Comparator
                    .comparing(Shop::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Shop::getDistance, Comparator.nullsLast(Double::compareTo))
                    .thenComparing(Shop::getId));
            return;
        }
        if ("sold".equals(sortBy)) {
            shops.sort(Comparator
                    .comparing(Shop::getSold, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Shop::getDistance, Comparator.nullsLast(Double::compareTo))
                    .thenComparing(Shop::getId));
        }
    }

    private void removeStaleGeoMembers(Integer typeId, List<Long> staleIds) {
        if (staleIds == null || staleIds.isEmpty()) {
            return;
        }
        String[] members = staleIds.stream().map(String::valueOf).toArray(String[]::new);
        stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + typeId, members);
    }

    private Long parseShopId(String shopIdStr) {
        try {
            return Long.valueOf(shopIdStr);
        } catch (Exception e) {
            log.warn("Invalid Redis GEO shop id: {}", shopIdStr);
            return null;
        }
    }

    private boolean isBeforeOrAtCursor(double distance, Long shopId, Double lastDistance, Long lastId) {
        int distanceCompare = Double.compare(distance, lastDistance);
        return distanceCompare < 0 || (distanceCompare == 0 && shopId <= lastId);
    }

    private String validateShopTypeQuery(Integer typeId, Integer current, Double x, Double y,
                                         Double lastDistance, Long lastId, String sortBy) {
        if (typeId == null || typeId <= 0) {
            return "typeId must be greater than 0";
        }
        if (current == null || current < 1) {
            return "current must be greater than 0";
        }
        if ((x == null) != (y == null)) {
            return "x and y must be provided together";
        }
        if (x != null && (x < -180 || x > 180)) {
            return "x must be between -180 and 180";
        }
        if (y != null && (y < -90 || y > 90)) {
            return "y must be between -90 and 90";
        }
        if ((lastDistance == null) != (lastId == null)) {
            return "lastDistance and lastId must be provided together";
        }
        if (lastDistance != null && lastDistance < 0) {
            return "lastDistance must be greater than or equal to 0";
        }
        if (lastId != null && lastId <= 0) {
            return "lastId must be greater than 0";
        }
        if (!isSupportedSortBy(sortBy)) {
            return "sortBy only supports distance, score or sold";
        }
        if (x != null && lastDistance == null && current > MAX_NEARBY_PAGE) {
            return "current must be less than or equal to 20 for nearby shop page query";
        }
        return null;
    }

    private boolean isSupportedSortBy(String sortBy) {
        String normalized = normalizeSortBy(sortBy);
        return "distance".equals(normalized) || "score".equals(normalized) || "sold".equals(normalized);
    }

    private String normalizeSortBy(String sortBy) {
        return StrUtil.isBlank(sortBy) ? "distance" : sortBy.trim().toLowerCase(Locale.ROOT);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runCacheSyncAction(action);
                }
            });
            return;
        }
        runCacheSyncAction(action);
    }

    private void runCacheSyncAction(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Shop cache synchronization failed", e);
        }
    }

    private void refreshShopGeoIndex(Shop oldShop, Long shopId) {
        removeShopFromGeo(oldShop);
        Shop latestShop = getById(shopId);
        addShopToGeo(latestShop);
    }

    private void removeShopFromGeo(Shop shop) {
        if (shop == null || shop.getTypeId() == null || shop.getId() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + shop.getTypeId(), shop.getId().toString());
    }

    private void addShopToGeo(Shop shop) {
        if (shop == null || shop.getTypeId() == null || shop.getId() == null
                || shop.getX() == null || shop.getY() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(
                SHOP_GEO_KEY + shop.getTypeId(),
                new Point(shop.getX(), shop.getY()),
                shop.getId().toString()
        );
    }

    private void updateShopExistsCache(Long shopId, boolean exists) {
        if (shopStatsService != null) {
            shopStatsService.updateShopExistsCache(shopId, exists);
        }
    }

    private ShopStatusVO buildShopStatusVO(Long id) {
        return shopStatsService.queryShopStatus(id);
    }

    private Shop toCreateEntity(ShopCreateDTO request) {
        Shop shop = new Shop();
        shop.setName(request.getName());
        shop.setTypeId(request.getTypeId());
        shop.setImages(request.getImages());
        shop.setArea(request.getArea());
        shop.setAddress(request.getAddress());
        shop.setX(request.getX());
        shop.setY(request.getY());
        shop.setAvgPrice(request.getAvgPrice());
        shop.setOpenHours(request.getOpenHours());
        shop.setSold(0);
        shop.setComments(0);
        shop.setScore(0);
        return shop;
    }

    private Shop toUpdateEntity(ShopUpdateDTO request) {
        Shop shop = new Shop();
        shop.setId(request.getId());
        shop.setName(request.getName());
        shop.setTypeId(request.getTypeId());
        shop.setImages(request.getImages());
        shop.setArea(request.getArea());
        shop.setAddress(request.getAddress());
        shop.setX(request.getX());
        shop.setY(request.getY());
        shop.setAvgPrice(request.getAvgPrice());
        shop.setOpenHours(request.getOpenHours());
        return shop;
    }

    private ShopDetailVO toDetailVO(Shop shop) {
        ShopDetailVO vo = new ShopDetailVO();
        fillDetailVO(vo, shop);
        return vo;
    }

    private NearbyShopVO toNearbyVO(Shop shop) {
        NearbyShopVO vo = new NearbyShopVO();
        fillDetailVO(vo, shop);
        vo.setDistance(shop.getDistance());
        return vo;
    }

    private List<NearbyShopVO> toNearbyVOList(List<Shop> shops) {
        if (shops == null || shops.isEmpty()) {
            return Collections.emptyList();
        }
        return shops.stream().map(this::toNearbyVO).collect(Collectors.toList());
    }

    private void fillDetailVO(ShopDetailVO vo, Shop shop) {
        vo.setId(shop.getId());
        vo.setName(shop.getName());
        vo.setTypeId(shop.getTypeId());
        vo.setImages(shop.getImages());
        vo.setArea(shop.getArea());
        vo.setAddress(shop.getAddress());
        vo.setX(shop.getX());
        vo.setY(shop.getY());
        vo.setAvgPrice(shop.getAvgPrice());
        vo.setSold(shop.getSold());
        vo.setComments(shop.getComments());
        vo.setScore(shop.getScore());
        vo.setOpenHours(shop.getOpenHours());
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.NearbyShopVO;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCreateDTO;
import com.hmdp.dto.ShopDetailVO;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.ai.application.ShopAICacheInvalidationService;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopGeoIndexService;
import com.hmdp.service.ShopStatsService;
import com.hmdp.utils.CacheBusyException;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metric;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalTime;
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

    private static final double NEARBY_RADIUS_METERS = 5000D;
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

    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private ShopAICacheInvalidationService shopAICacheInvalidationService;

    @Value("${hmdp.shop.nearby.initial-fetch-multiplier:3}")
    private int nearbyInitialFetchMultiplier = 3;

    @Value("${hmdp.shop.nearby.max-scan-size:200}")
    private int nearbyMaxScanSize = 200;

    @Value("${hmdp.shop.nearby.fetch-growth-factor:2}")
    private int nearbyFetchGrowthFactor = 2;

    @Override
    public Result queryById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "id must be greater than 0");
        }
        Shop shop;
        try {
            shop = cacheClient.queryWithMutex(
                    CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (CacheBusyException e) {
            log.warn("Shop detail cache is busy, id={}", id, e);
            return Result.fail(ErrorCode.SYSTEM_BUSY, "system busy, please retry later");
        }
        if (shop == null) {
            return Result.fail(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }
        return Result.ok(toDetailVO(shop));
    }

    @Override
    @Transactional
    public Result createShop(ShopCreateDTO request) {
        Long userId = currentUserService.getCurrentUserId();
        boolean bindToCurrentMerchant = shouldBindCreatedShopToCurrentUser(userId);
        Shop shop = toCreateEntity(request);
        try {
            boolean saved = save(shop);
            if (!saved) {
                recordShopOperation("create", null, buildCreateAuditDetail(request), false, "shop create failed");
                return Result.fail("shop create failed");
            }
            if (bindToCurrentMerchant) {
                merchantShopService.bindMerchantShop(userId, shop.getId(), "auto bind on shop create");
            }
        } catch (RuntimeException e) {
            recordShopOperation("create", shop.getId(), buildCreateAuditDetail(request), false, e.getMessage());
            throw e;
        }
        recordShopOperation("create", shop.getId(), buildCreateAuditDetail(request), true, null);
        runAfterCommit(() -> {
            runGeoSyncAction(() -> shopGeoIndexService.addOrUpdateShop(shop));
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
            recordShopOperation("update", id, buildUpdateAuditDetail(request), false, "no permission to update this shop");
            return Result.fail(ErrorCode.FORBIDDEN, "no permission to update this shop");
        }

        Shop oldShop = getById(id);
        if (oldShop == null) {
            recordShopOperation("update", id, buildUpdateAuditDetail(request), false, "shop does not exist");
            return Result.fail(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }

        boolean updated = updateShopWithVersion(request);
        if (!updated) {
            ErrorCode errorCode = ErrorCode.SHOP_UPDATE_CONFLICT;
            String message = "shop update conflict";
            recordShopOperation("update", id, buildUpdateAuditDetail(request), false, message);
            return Result.fail(errorCode, message);
        }

        recordShopOperation("update", id, buildUpdateAuditDetail(request), true, null);
        runAfterCommit(() -> {
            runCacheSyncAction(() -> cacheClient.deleteWithMutex(CACHE_SHOP_KEY, id));
            runCacheSyncAction(() -> shopAICacheInvalidationService.clearShopRelatedCaches(id));
            runGeoSyncAction(() -> shopGeoIndexService.refreshShopGeoIndex(oldShop, getById(id)));
            runCacheSyncAction(() -> updateShopExistsCache(id, true));
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
                                  Double lastDistance, Long lastId, String sortBy,
                                  String keyword, String area, Integer minScore,
                                  Long minAvgPrice, Long maxAvgPrice, Boolean openNow,
                                  Boolean pageResult) {
        ShopQueryFilter filter = new ShopQueryFilter(keyword, area, minScore, minAvgPrice, maxAvgPrice,
                Boolean.TRUE.equals(openNow), Boolean.TRUE.equals(pageResult));
        String validationError = validateShopTypeQuery(typeId, current, x, y, lastDistance, lastId, sortBy, filter);
        if (validationError != null) {
            return Result.fail(ErrorCode.PARAM_ERROR, validationError);
        }

        String normalizedSortBy = normalizeSortBy(sortBy);
        if (x == null && y == null) {
            return queryShopByTypeFromDb(typeId, current, normalizedSortBy, filter);
        }

        return queryNearbyShopsByCursor(typeId, current, x, y, lastDistance, lastId, normalizedSortBy, filter);
    }

    private Result queryShopByTypeFromDb(Integer typeId, Integer current, String sortBy, ShopQueryFilter filter) {
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .like(StrUtil.isNotBlank(filter.keyword), "name", filter.keyword)
                .eq(StrUtil.isNotBlank(filter.area), "area", filter.area)
                .ge(filter.minScore != null, "score", filter.minScore)
                .ge(filter.minAvgPrice != null, "avg_price", filter.minAvgPrice)
                .le(filter.maxAvgPrice != null, "avg_price", filter.maxAvgPrice)
                .orderByDesc("score".equals(sortBy), "score")
                .orderByDesc("sold".equals(sortBy), "sold")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        List<NearbyShopVO> list = toNearbyVOList(filterShops(page.getRecords(), filter));
        return Result.ok(PageResult.of(list, current, SystemConstants.DEFAULT_PAGE_SIZE,
                page.getTotal(), current * SystemConstants.DEFAULT_PAGE_SIZE < page.getTotal(), null));
    }

    private Result queryNearbyShopsByCursor(Integer typeId, Integer current, Double x, Double y,
                                            Double lastDistance, Long lastId, String sortBy,
                                            ShopQueryFilter filter) {
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        List<Shop> distanceOrderedShops = queryNearbyDistanceWindow(typeId, x, y, lastDistance, lastId, filter, pageSize);

        boolean hasMore = distanceOrderedShops.size() > pageSize;
        List<Shop> pageWindow = distanceOrderedShops;
        if (hasMore) {
            pageWindow = new ArrayList<>(distanceOrderedShops.subList(0, pageSize));
        }

        List<Shop> displayShops = new ArrayList<>(pageWindow);
        sortNearbyShops(displayShops, sortBy);

        Double nextLastDistance = null;
        Long nextLastId = null;
        if (!pageWindow.isEmpty()) {
            Shop lastShop = pageWindow.get(pageWindow.size() - 1);
            nextLastDistance = lastShop.getDistance();
            nextLastId = lastShop.getId();
        }
        Map<String, Object> cursor = new HashMap<>();
        cursor.put("lastDistance", nextLastDistance);
        cursor.put("lastId", nextLastId);
        return Result.ok(PageResult.of(toNearbyVOList(displayShops), current, pageSize, null, hasMore, cursor));
    }

    private List<Shop> queryNearbyDistanceWindow(Integer typeId, Double x, Double y,
                                                 Double lastDistance, Long lastId,
                                                 ShopQueryFilter filter, int pageSize) {
        int initialLimit = pageSize * effectiveInitialFetchMultiplier() + 1;
        int maxScanSize = Math.max(initialLimit, effectiveNearbyMaxScanSize());
        int limit = Math.min(initialLimit, maxScanSize);
        List<Shop> filtered = Collections.emptyList();
        boolean shouldExpand = filter.hasBusinessFilters();

        while (true) {
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = searchGeo(typeId, x, y, limit);
            List<Shop> distanceOrderedShops = buildNearbyShopList(
                    typeId, geoResults, true, lastDistance, lastId, "distance");
            filtered = filterShops(distanceOrderedShops, filter);
            if (!shouldExpand || filtered.size() >= pageSize + 1 || limit >= maxScanSize || geoResults.size() < limit) {
                return filtered;
            }
            int nextLimit = Math.min(maxScanSize, limit * effectiveFetchGrowthFactor());
            if (nextLimit <= limit) {
                return filtered;
            }
            limit = nextLimit;
        }
    }

    private List<GeoResult<RedisGeoCommands.GeoLocation<String>>> searchGeo(Integer typeId, Double x, Double y, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        SHOP_GEO_KEY + typeId,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(NEARBY_RADIUS_METERS, METERS),
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
            if (cursorMode && lastDistance != null && lastId != null
                    && isBeforeOrAtCursor(distance, shopId, lastDistance, lastId)) {
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

    private List<Shop> filterShops(List<Shop> shops, ShopQueryFilter filter) {
        if (shops == null || shops.isEmpty()) {
            return Collections.emptyList();
        }
        return shops.stream()
                .filter(shop -> matchesKeyword(shop, filter.keyword))
                .filter(shop -> StrUtil.isBlank(filter.area) || StrUtil.equals(filter.area, shop.getArea()))
                .filter(shop -> filter.minScore == null || (shop.getScore() != null && shop.getScore() >= filter.minScore))
                .filter(shop -> filter.minAvgPrice == null || (shop.getAvgPrice() != null && shop.getAvgPrice() >= filter.minAvgPrice))
                .filter(shop -> filter.maxAvgPrice == null || (shop.getAvgPrice() != null && shop.getAvgPrice() <= filter.maxAvgPrice))
                .filter(shop -> !filter.openNow || isOpenNow(shop.getOpenHours()))
                .collect(Collectors.toList());
    }

    private boolean matchesKeyword(Shop shop, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        return StrUtil.containsIgnoreCase(shop.getName(), keyword)
                || StrUtil.containsIgnoreCase(shop.getAddress(), keyword);
    }

    private boolean isOpenNow(String openHours) {
        if (StrUtil.isBlank(openHours)) {
            return false;
        }
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        String[] ranges = openHours.split(",");
        for (String range : ranges) {
            String[] parts = range.trim().split("-");
            if (parts.length != 2) {
                continue;
            }
            Integer start = parseMinuteOfDay(parts[0]);
            Integer end = parseMinuteOfDay(parts[1]);
            if (start == null || end == null) {
                continue;
            }
            if (start.equals(end)) {
                return true;
            }
            if (end > start && now >= start && now < end) {
                return true;
            }
            if (end < start && (now >= start || now < end)) {
                return true;
            }
        }
        return false;
    }

    private Integer parseMinuteOfDay(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour == 24 && minute == 0) {
                return 24 * 60;
            }
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String validateShopTypeQuery(Integer typeId, Integer current, Double x, Double y,
                                         Double lastDistance, Long lastId, String sortBy,
                                         ShopQueryFilter filter) {
        if (typeId == null || typeId <= 0) {
            return "typeId must be greater than 0";
        }
        if (current == null || current < 1) {
            return "current must be greater than 0";
        }
        if ((x == null) != (y == null)) {
            return "x and y must be provided together";
        }
        if (x != null && (!Double.isFinite(x) || x < -180 || x > 180)) {
            return "x must be between -180 and 180";
        }
        if (y != null && (!Double.isFinite(y) || y < -90 || y > 90)) {
            return "y must be between -90 and 90";
        }
        if ((lastDistance == null) != (lastId == null)) {
            return "lastDistance and lastId must be provided together";
        }
        if (lastDistance != null && !Double.isFinite(lastDistance)) {
            return "lastDistance must be finite";
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
        if (!filter.pageResult) {
            return "pageResult=true is required";
        }
        if (filter.minScore != null && (filter.minScore < 0 || filter.minScore > 50)) {
            return "minScore must be between 0 and 50";
        }
        if (filter.minAvgPrice != null && filter.minAvgPrice < 0) {
            return "minAvgPrice must be greater than or equal to 0";
        }
        if (filter.maxAvgPrice != null && filter.maxAvgPrice < 0) {
            return "maxAvgPrice must be greater than or equal to 0";
        }
        if (filter.minAvgPrice != null && filter.maxAvgPrice != null && filter.minAvgPrice > filter.maxAvgPrice) {
            return "minAvgPrice must be less than or equal to maxAvgPrice";
        }
        if (x != null && current > 1 && lastDistance == null) {
            return "lastDistance and lastId are required for nearby cursor pagination after first page";
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

    private int effectiveInitialFetchMultiplier() {
        return Math.max(1, nearbyInitialFetchMultiplier);
    }

    private int effectiveNearbyMaxScanSize() {
        return Math.max(SystemConstants.DEFAULT_PAGE_SIZE + 1, nearbyMaxScanSize);
    }

    private int effectiveFetchGrowthFactor() {
        return Math.max(2, nearbyFetchGrowthFactor);
    }

    private boolean shouldBindCreatedShopToCurrentUser(Long userId) {
        if (userId == null) {
            return false;
        }
        return permissionService.hasRole(userId, "merchant") && !permissionService.hasRole(userId, "admin");
    }

    protected boolean updateShopWithVersion(ShopUpdateDTO request) {
        UpdateWrapper<Shop> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", request.getId());
        wrapper.eq("version", request.getVersion());
        wrapper.set("name", request.getName());
        wrapper.set("type_id", request.getTypeId());
        wrapper.set("images", request.getImages());
        wrapper.set("area", request.getArea());
        wrapper.set("address", request.getAddress());
        if (request.getX() != null) {
            wrapper.set("x", request.getX());
        }
        if (request.getY() != null) {
            wrapper.set("y", request.getY());
        }
        if (request.getAvgPrice() != null) {
            wrapper.set("avg_price", request.getAvgPrice());
        }
        if (request.getOpenHours() != null) {
            wrapper.set("open_hours", request.getOpenHours());
        }
        wrapper.setSql("version = version + 1");
        return baseMapper.update(null, wrapper) > 0;
    }

    private String buildCreateAuditDetail(ShopCreateDTO request) {
        return "name=" + request.getName() + ", typeId=" + request.getTypeId()
                + ", address=" + request.getAddress();
    }

    private String buildUpdateAuditDetail(ShopUpdateDTO request) {
        return "name=" + request.getName() + ", typeId=" + request.getTypeId()
                + ", version=" + request.getVersion();
    }

    private void recordShopOperation(String operation, Long shopId, String detail, boolean success, String failReason) {
        if (operationLogService == null) {
            return;
        }
        try {
            operationLogService.record("shop", operation, "shop",
                    shopId == null ? null : String.valueOf(shopId), detail, success, failReason);
        } catch (Exception e) {
            log.warn("Record shop operation log failed, operation={}, shopId={}", operation, shopId, e);
        }
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

    private void runGeoSyncAction(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Shop GEO synchronization failed", e);
        }
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
        shop.setVersion(0);
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
        vo.setVersion(shop.getVersion());
    }

    private static class ShopQueryFilter {
        private final String keyword;
        private final String area;
        private final Integer minScore;
        private final Long minAvgPrice;
        private final Long maxAvgPrice;
        private final boolean openNow;
        private final boolean pageResult;

        private ShopQueryFilter(String keyword, String area, Integer minScore,
                                Long minAvgPrice, Long maxAvgPrice,
                                boolean openNow, boolean pageResult) {
            this.keyword = StrUtil.isBlank(keyword) ? null : keyword.trim();
            this.area = StrUtil.isBlank(area) ? null : area.trim();
            this.minScore = minScore;
            this.minAvgPrice = minAvgPrice;
            this.maxAvgPrice = maxAvgPrice;
            this.openNow = openNow;
            this.pageResult = pageResult;
        }

        private boolean hasBusinessFilters() {
            return StrUtil.isNotBlank(keyword)
                    || StrUtil.isNotBlank(area)
                    || minScore != null
                    || minAvgPrice != null
                    || maxAvgPrice != null
                    || openNow;
        }
    }
}

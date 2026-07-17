package com.hmdp.service.impl;

import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCreateDTO;
import com.hmdp.dto.ShopDetailVO;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.ai.application.ShopAICacheInvalidationService;
import com.hmdp.entity.Shop;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.ShopGeoIndexService;
import com.hmdp.service.ShopStatsService;
import com.hmdp.utils.CacheBusyException;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplDomainModelTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ShopGeoIndexService shopGeoIndexService;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private IPermissionService permissionService;

    @Mock
    private IMerchantShopService merchantShopService;

    @Mock
    private ShopStatsService shopStatsService;

    @Mock
    private IOperationLogService operationLogService;

    @Mock
    private ShopAICacheInvalidationService shopAICacheInvalidationService;

    private TestableShopServiceImpl shopService;

    @BeforeEach
    void setUp() {
        shopService = new TestableShopServiceImpl();
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(shopService, "shopGeoIndexService", shopGeoIndexService);
        ReflectionTestUtils.setField(shopService, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(shopService, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(shopService, "permissionService", permissionService);
        ReflectionTestUtils.setField(shopService, "merchantShopService", merchantShopService);
        ReflectionTestUtils.setField(shopService, "shopStatsService", shopStatsService);
        ReflectionTestUtils.setField(shopService, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(shopService, "shopAICacheInvalidationService", shopAICacheInvalidationService);
    }

    @Test
    void createShopShouldPersistSystemDefaultsAndSyncCaches() {
        shopService.nextSaveId = 101L;

        Result result = shopService.createShop(createRequest());

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(101L);
        Shop saved = shopService.savedShop;
        assertThat(saved.getName()).isEqualTo("test shop");
        assertThat(saved.getSold()).isZero();
        assertThat(saved.getComments()).isZero();
        assertThat(saved.getScore()).isZero();
        assertThat(saved.getVersion()).isZero();
        verify(shopGeoIndexService).addOrUpdateShop(saved);
        verify(shopStatsService).updateShopExistsCache(101L, true);
        verify(operationLogService).record(eq("shop"), eq("create"), eq("shop"), eq("101"),
                anyString(), eq(true), isNull());
    }

    @Test
    void createShopShouldReturnFailWhenSaveFailsAndSkipSync() {
        shopService.saveResult = false;

        Result result = shopService.createShop(createRequest());

        assertThat(result.getSuccess()).isFalse();
        verify(shopGeoIndexService, never()).addOrUpdateShop(any());
        verify(shopStatsService, never()).updateShopExistsCache(any(), eq(true));
        verify(operationLogService).record(eq("shop"), eq("create"), eq("shop"), isNull(),
                anyString(), eq(false), eq("shop create failed"));
    }

    @Test
    void createShopShouldBindMerchantUserAutomatically() {
        shopService.nextSaveId = 102L;
        when(currentUserService.getCurrentUserId()).thenReturn(9L);
        when(permissionService.hasRole(9L, "merchant")).thenReturn(true);
        when(permissionService.hasRole(9L, "admin")).thenReturn(false);

        Result result = shopService.createShop(createRequest());

        assertThat(result.getSuccess()).isTrue();
        verify(merchantShopService).bindMerchantShop(eq(9L), eq(102L), anyString());
    }

    @Test
    void createShopShouldNotBindAdminAutomatically() {
        shopService.nextSaveId = 103L;
        when(currentUserService.getCurrentUserId()).thenReturn(1L);
        when(permissionService.hasRole(1L, "merchant")).thenReturn(true);
        when(permissionService.hasRole(1L, "admin")).thenReturn(true);

        Result result = shopService.createShop(createRequest());

        assertThat(result.getSuccess()).isTrue();
        verify(merchantShopService, never()).bindMerchantShop(any(), any(), anyString());
    }

    @Test
    void updateShopShouldReturnNotFoundWhenShopMissing() {
        when(currentUserService.requireCurrentUserId()).thenReturn(9L);
        when(permissionService.hasRole(9L, "admin")).thenReturn(true);

        Result result = shopService.updateShop(updateRequest());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SHOP_NOT_FOUND.getCode());
    }

    @Test
    void updateShopShouldReturnForbiddenWhenMerchantDoesNotOwnShop() {
        when(currentUserService.requireCurrentUserId()).thenReturn(9L);
        when(permissionService.hasRole(9L, "admin")).thenReturn(false);
        when(merchantShopService.isShopOwner(9L, 100L)).thenReturn(false);

        Result result = shopService.updateShop(updateRequest());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(shopService.updateCalled).isFalse();
    }

    @Test
    void updateShopShouldReturnConflictWhenConditionalUpdateAffectsNoRows() {
        shopService.db.put(100L, oldShop());
        shopService.updateResult = false;
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(permissionService.hasRole(1L, "admin")).thenReturn(true);

        Result result = shopService.updateShop(updateRequest());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SHOP_UPDATE_CONFLICT.getCode());
        verify(cacheClient, never()).deleteWithMutex(anyString(), any());
    }

    @Test
    void updateShopShouldReturnConflictWhenVersionDoesNotMatch() {
        Shop oldShop = oldShop();
        oldShop.setVersion(2);
        shopService.db.put(100L, oldShop);
        ShopUpdateDTO request = updateRequest();
        request.setVersion(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(permissionService.hasRole(1L, "admin")).thenReturn(true);

        Result result = shopService.updateShop(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SHOP_UPDATE_CONFLICT.getCode());
        verify(cacheClient, never()).deleteWithMutex(anyString(), any());
    }

    @Test
    void updateShopShouldDeleteRedisRefreshGeoAndSyncSummaryCache() {
        Shop oldShop = oldShop();
        shopService.db.put(100L, oldShop);
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(permissionService.hasRole(1L, "admin")).thenReturn(true);

        Result result = shopService.updateShop(updateRequest());

        assertThat(result.getSuccess()).isTrue();
        assertThat(shopService.db.get(100L).getVersion()).isEqualTo(1);
        verify(cacheClient).deleteWithMutex(CACHE_SHOP_KEY, 100L);
        verify(shopAICacheInvalidationService).clearShopRelatedCaches(100L);
        verify(shopGeoIndexService).refreshShopGeoIndex(eq(oldShop), any(Shop.class));
        verify(shopStatsService).updateShopExistsCache(100L, true);
    }

    @Test
    void updateShopShouldContinueOtherSyncActionsWhenDetailCacheEvictionFails() {
        Shop oldShop = oldShop();
        shopService.db.put(100L, oldShop);
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(permissionService.hasRole(1L, "admin")).thenReturn(true);
        doThrow(new IllegalStateException("redis down"))
                .when(cacheClient).deleteWithMutex(CACHE_SHOP_KEY, 100L);

        Result result = shopService.updateShop(updateRequest());

        assertThat(result.getSuccess()).isTrue();
        verify(shopAICacheInvalidationService).clearShopRelatedCaches(100L);
        verify(shopGeoIndexService).refreshShopGeoIndex(eq(oldShop), any(Shop.class));
        verify(shopStatsService).updateShopExistsCache(100L, true);
    }

    @Test
    void queryByIdShouldReturnShopDetailVOWithoutDatabaseMetadata() {
        Shop shop = oldShop();
        when(cacheClient.queryWithMutex(eq(CACHE_SHOP_KEY), eq(100L), eq(Shop.class), any(), any(), any()))
                .thenReturn(shop);

        Result result = shopService.queryById(100L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isInstanceOf(ShopDetailVO.class);
        ShopDetailVO vo = (ShopDetailVO) result.getData();
        assertThat(vo.getId()).isEqualTo(100L);
        assertThat(vo.getName()).isEqualTo("old shop");
        assertThat(vo).hasNoNullFieldsOrPropertiesExcept("area", "avgPrice", "openHours");
    }

    @Test
    void queryByIdShouldReturnSystemBusyWhenCacheMutexIsBusy() {
        when(cacheClient.queryWithMutex(eq(CACHE_SHOP_KEY), eq(100L), eq(Shop.class), any(), any(), any()))
                .thenThrow(new CacheBusyException("busy"));

        Result result = shopService.queryById(100L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SYSTEM_BUSY.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("system busy, please retry later");
    }

    @Test
    void queryShopStatusShouldReturnReviewCountOnlyWhenShopExists() {
        ShopStatusVO missingStatus = new ShopStatusVO();
        missingStatus.setExists(false);
        when(shopStatsService.queryShopStatus(100L)).thenReturn(missingStatus);

        Result missing = shopService.queryShopStatus(100L);

        ShopStatusVO missingVo = (ShopStatusVO) missing.getData();
        assertThat(missingVo.getExists()).isFalse();
        assertThat(missingVo.getReviewCount()).isNull();

        ShopStatusVO existsStatus = new ShopStatusVO();
        existsStatus.setExists(true);
        existsStatus.setReviewCount(12);
        when(shopStatsService.queryShopStatus(101L)).thenReturn(existsStatus);

        Result exists = shopService.queryShopStatus(101L);

        ShopStatusVO existsVo = (ShopStatusVO) exists.getData();
        assertThat(existsVo.getExists()).isTrue();
        assertThat(existsVo.getReviewCount()).isEqualTo(12);
    }

    private ShopCreateDTO createRequest() {
        ShopCreateDTO dto = new ShopCreateDTO();
        dto.setName("test shop");
        dto.setTypeId(1L);
        dto.setImages("https://example.com/a.jpg");
        dto.setArea("area");
        dto.setAddress("address");
        dto.setX(120.1);
        dto.setY(30.2);
        dto.setAvgPrice(88L);
        dto.setOpenHours("10:00-22:00");
        return dto;
    }

    private ShopUpdateDTO updateRequest() {
        ShopUpdateDTO dto = new ShopUpdateDTO();
        dto.setId(100L);
        dto.setName("new shop");
        dto.setTypeId(2L);
        dto.setImages("https://example.com/b.jpg");
        dto.setArea("new area");
        dto.setAddress("new address");
        dto.setX(121.1);
        dto.setY(31.2);
        dto.setAvgPrice(99L);
        dto.setOpenHours("09:00-21:00");
        dto.setVersion(0);
        return dto;
    }

    private Shop oldShop() {
        Shop shop = new Shop();
        shop.setId(100L);
        shop.setName("old shop");
        shop.setTypeId(1L);
        shop.setImages("https://example.com/old.jpg");
        shop.setAddress("old address");
        shop.setX(120.0);
        shop.setY(30.0);
        shop.setSold(10);
        shop.setComments(5);
        shop.setScore(45);
        shop.setVersion(0);
        return shop;
    }

    private static class TestableShopServiceImpl extends ShopServiceImpl {
        private final Map<Long, Shop> db = new HashMap<>();
        private boolean saveResult = true;
        private boolean updateResult = true;
        private boolean updateCalled;
        private Long nextSaveId;
        private Shop savedShop;

        @Override
        public boolean save(Shop entity) {
            if (!saveResult) {
                return false;
            }
            if (nextSaveId != null) {
                entity.setId(nextSaveId);
            }
            savedShop = entity;
            db.put(entity.getId(), entity);
            return true;
        }

        @Override
        public Shop getById(java.io.Serializable id) {
            return db.get((Long) id);
        }

        @Override
        protected boolean updateShopWithVersion(ShopUpdateDTO request) {
            updateCalled = true;
            if (!updateResult) {
                return false;
            }
            Shop old = db.get(request.getId());
            if (old == null) {
                return false;
            }
            Integer oldVersion = old.getVersion() == null ? 0 : old.getVersion();
            if (!request.getVersion().equals(oldVersion)) {
                return false;
            }
            Shop entity = new Shop();
            entity.setId(request.getId());
            entity.setName(request.getName());
            entity.setTypeId(request.getTypeId());
            entity.setImages(request.getImages());
            entity.setArea(request.getArea());
            entity.setAddress(request.getAddress());
            entity.setX(request.getX() == null ? old.getX() : request.getX());
            entity.setY(request.getY() == null ? old.getY() : request.getY());
            entity.setAvgPrice(request.getAvgPrice() == null ? old.getAvgPrice() : request.getAvgPrice());
            entity.setOpenHours(request.getOpenHours() == null ? old.getOpenHours() : request.getOpenHours());
            entity.setSold(old.getSold());
            entity.setComments(old.getComments());
            entity.setScore(old.getScore());
            entity.setVersion(oldVersion + 1);
            db.put(entity.getId(), entity);
            return true;
        }
    }
}

package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.infra.AiResultCacheService;
import com.hmdp.utils.LocalCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShopAICacheInvalidationServiceTest {

    @Mock
    private LocalCacheManager localCacheManager;

    @Mock
    private AiResultCacheService aiResultCacheService;

    @Mock
    private MemoryService memoryService;

    private ShopAICacheInvalidationService service;

    @BeforeEach
    void setUp() {
        service = new ShopAICacheInvalidationService();
        ReflectionTestUtils.setField(service, "localCacheManager", localCacheManager);
        ReflectionTestUtils.setField(service, "aiResultCacheService", aiResultCacheService);
        ReflectionTestUtils.setField(service, "memoryService", memoryService);
    }

    @Test
    void clearShopRelatedCachesShouldEvictAllAiCaches() {
        service.clearShopRelatedCaches(7L);

        verify(localCacheManager).removeShopRelatedCaches(7L);
        verify(aiResultCacheService).evictShop(7L);
        verify(memoryService).clearAllShopSummaryMemory(7L);
    }

    @Test
    void clearShopRelatedCachesShouldIgnoreInvalidShopId() {
        service.clearShopRelatedCaches(0L);

        verify(localCacheManager, never()).removeShopRelatedCaches(0L);
        verify(aiResultCacheService, never()).evictShop(0L);
        verify(memoryService, never()).clearAllShopSummaryMemory(0L);
    }
}

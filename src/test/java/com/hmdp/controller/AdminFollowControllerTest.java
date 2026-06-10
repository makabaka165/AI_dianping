package com.hmdp.controller;

import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.service.FollowConsistencyService;
import com.hmdp.service.IOperationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminFollowControllerTest {

    @Mock
    private FollowConsistencyService followConsistencyService;

    @Mock
    private IOperationLogService operationLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminFollowController controller = new AdminFollowController();
        ReflectionTestUtils.setField(controller, "followConsistencyService", followConsistencyService);
        ReflectionTestUtils.setField(controller, "operationLogService", operationLogService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void rebuildFollowCacheWithoutUserIdShouldRebuildAllAndRecordAudit() throws Exception {
        when(followConsistencyService.rebuildAllFollowCaches())
                .thenReturn(Map.of("rebuiltUsers", 2, "rebuiltRelations", 5));

        mockMvc.perform(post("/admin/follows/cache/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rebuiltUsers").value(2));

        verify(followConsistencyService).rebuildAllFollowCaches();
        verify(operationLogService).record(eq("follow"), eq("rebuild_cache"), eq("follow"),
                isNull(), contains("rebuiltUsers"), eq(true), isNull());
    }

    @Test
    void rebuildFollowCacheWithUserIdShouldRebuildSingleUser() throws Exception {
        when(followConsistencyService.rebuildFollowCache(1L))
                .thenReturn(Map.of("userId", 1L, "followCount", 3, "rebuiltUsers", 1));

        mockMvc.perform(post("/admin/follows/cache/rebuild").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.followCount").value(3));

        verify(followConsistencyService).rebuildFollowCache(1L);
        verify(operationLogService).record(eq("follow"), eq("rebuild_cache"), eq("follow"),
                eq("1"), contains("followCount"), eq(true), isNull());
    }

    @Test
    void repairUserFeedShouldDelegateAndRecordAudit() throws Exception {
        when(followConsistencyService.repairUserFeed(1L))
                .thenReturn(Map.of("userId", 1L, "scannedFeedItems", 4, "removedFeedItems", 2));

        mockMvc.perform(post("/admin/follows/feed/repair").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.removedFeedItems").value(2));

        verify(followConsistencyService).repairUserFeed(1L);
        verify(operationLogService).record(eq("follow"), eq("repair_feed"), eq("follow"),
                eq("1"), contains("removedFeedItems"), eq(true), isNull());
    }

    @Test
    void rebuildFollowCacheShouldRecordFailureWhenServiceRejectsRequest() throws Exception {
        when(followConsistencyService.rebuildFollowCache(0L))
                .thenThrow(new IllegalArgumentException("user id is invalid"));

        mockMvc.perform(post("/admin/follows/cache/rebuild").param("userId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        verify(operationLogService).record(eq("follow"), eq("rebuild_cache"), eq("follow"),
                eq("0"), contains("userId=0"), eq(false), eq("user id is invalid"));
    }
}

package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.PageResult;
import com.hmdp.service.IFollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FollowControllerTest {

    @Mock
    private IFollowService followService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FollowController controller = new FollowController();
        ReflectionTestUtils.setField(controller, "followService", followService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void followShouldUsePutResourceEndpoint() throws Exception {
        when(followService.follow(2L)).thenReturn(Result.ok());

        mockMvc.perform(put("/follow/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(followService).follow(2L);
    }

    @Test
    void unfollowShouldUseDeleteResourceEndpoint() throws Exception {
        when(followService.unfollow(2L)).thenReturn(Result.ok());

        mockMvc.perform(delete("/follow/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(followService).unfollow(2L);
    }

    @Test
    void statusShouldUseResourceStatusEndpoint() throws Exception {
        when(followService.isFollow(2L)).thenReturn(Result.ok(true));

        mockMvc.perform(get("/follow/2/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(followService).isFollow(2L);
    }

    @Test
    void commonShouldUseResourceCommonEndpoint() throws Exception {
        when(followService.followCommons(2L, 2, 20))
                .thenReturn(Result.ok(PageResult.of(Collections.emptyList(), 2, 20, 0L, false, null)));

        mockMvc.perform(get("/follow/2/common")
                        .param("current", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isArray());

        verify(followService).followCommons(2L, 2, 20);
    }

    @Test
    void legacyPathBooleanEndpointShouldBeRemoved() throws Exception {
        mockMvc.perform(put("/follow/2/true"))
                .andExpect(status().isNotFound());

        verify(followService, never()).follow(2L);
    }

    @Test
    void legacyStatusEndpointShouldBeRemoved() throws Exception {
        mockMvc.perform(get("/follow/or/not/2"))
                .andExpect(status().isNotFound());

        verify(followService, never()).isFollow(2L);
    }

    @Test
    void legacyCommonEndpointShouldBeRemoved() throws Exception {
        mockMvc.perform(get("/follow/common/2"))
                .andExpect(status().isNotFound());

        verify(followService, never()).followCommons(eq(2L), any(), any());
    }
}

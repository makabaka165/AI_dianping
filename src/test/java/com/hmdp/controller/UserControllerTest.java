package com.hmdp.controller;

import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private IUserService userService;

    @Mock
    private IUserInfoService userInfoService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userInfoService", userInfoService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void publicUserInfoShouldNotExposePrivateFields() throws Exception {
        UserInfo info = new UserInfo()
                .setUserId(1L)
                .setCity("Hangzhou")
                .setIntroduce("hello")
                .setFans(3)
                .setFollowee(4)
                .setGender(true)
                .setBirthday(LocalDate.of(2000, 1, 1))
                .setCredits(100)
                .setLevel(true)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        when(userInfoService.getById(1L)).thenReturn(info);

        mockMvc.perform(get("/user/info/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.city").value("Hangzhou"))
                .andExpect(jsonPath("$.data.introduce").value("hello"))
                .andExpect(jsonPath("$.data.fans").value(3))
                .andExpect(jsonPath("$.data.followee").value(4))
                .andExpect(jsonPath("$.data.birthday").doesNotExist())
                .andExpect(jsonPath("$.data.credits").doesNotExist())
                .andExpect(jsonPath("$.data.level").doesNotExist())
                .andExpect(jsonPath("$.data.createTime").doesNotExist())
                .andExpect(jsonPath("$.data.updateTime").doesNotExist())
                .andExpect(jsonPath("$.data.gender").doesNotExist());
    }

    @Test
    void missingUserInfoShouldStillReturnOkWithoutData() throws Exception {
        when(userInfoService.getById(2L)).thenReturn(null);

        mockMvc.perform(get("/user/info/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void queryUserByIdShouldStillReturnUserDto() throws Exception {
        User user = new User().setId(3L).setNickName("nick").setIcon("/i.png");
        when(userService.getById(3L)).thenReturn(user);

        mockMvc.perform(get("/user/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.nickName").value("nick"))
                .andExpect(jsonPath("$.data.icon").value("/i.png"));
    }

    @Test
    void loginShouldKeepReturningTokenInResultData() throws Exception {
        when(userService.login(any(), any())).thenReturn(com.hmdp.dto.Result.ok("token-value"));

        mockMvc.perform(post("/user/login")
                        .contentType("application/json")
                        .content("{\"phone\":\"13812341234\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("token-value"));
    }
}

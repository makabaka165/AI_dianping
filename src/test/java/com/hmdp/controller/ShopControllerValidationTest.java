package com.hmdp.controller;

import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopStatusVO;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShopControllerValidationTest {

    @Mock
    private IShopService shopService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(controller, "shopService", shopService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .setValidator(validator)
                .build();
    }

    @Test
    void saveShopShouldReturnParamErrorWhenNameIsBlank() throws Exception {
        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"typeId\":1,\"images\":\"img\",\"address\":\"addr\",\"x\":120.1,\"y\":30.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void saveShopShouldReturnParamErrorWhenCoordinateInvalid() throws Exception {
        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shop\",\"typeId\":1,\"images\":\"img\",\"address\":\"addr\",\"x\":181,\"y\":30.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void updateShopShouldReturnParamErrorWhenIdMissing() throws Exception {
        mockMvc.perform(put("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shop\",\"typeId\":1,\"images\":\"img\",\"address\":\"addr\",\"x\":120.1,\"y\":30.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void updateShopShouldReturnParamErrorWhenOnlyOneCoordinateProvided() throws Exception {
        mockMvc.perform(put("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"name\":\"shop\",\"typeId\":1,\"images\":\"img\",\"address\":\"addr\",\"x\":120.1,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void updateShopShouldReturnParamErrorWhenVersionMissing() throws Exception {
        mockMvc.perform(put("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"name\":\"shop\",\"typeId\":1,\"images\":\"img\",\"address\":\"addr\",\"x\":120.1,\"y\":30.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void statusShouldDelegateToShopStatusServiceMethod() throws Exception {
        ShopStatusVO vo = new ShopStatusVO();
        vo.setExists(true);
        vo.setReviewCount(7);
        when(shopService.queryShopStatus(1L)).thenReturn(Result.ok(vo));

        mockMvc.perform(get("/shop/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.reviewCount").value(7));

        verify(shopService).queryShopStatus(1L);
    }

    @Test
    void statsEndpointShouldBeRemovedAfterStatusReplacement() throws Exception {
        mockMvc.perform(get("/shop/1/stats"))
                .andExpect(status().isNotFound());

        verify(shopService, never()).queryShopStatus(1L);
    }
}

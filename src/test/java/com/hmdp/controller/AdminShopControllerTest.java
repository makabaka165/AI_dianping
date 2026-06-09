package com.hmdp.controller;

import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.ShopTypeAdminVO;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.ShopGeoIndexService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminShopControllerTest {

    @Mock
    private IShopTypeService shopTypeService;

    @Mock
    private ShopGeoIndexService shopGeoIndexService;

    @Mock
    private IOperationLogService operationLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminShopController controller = new AdminShopController();
        ReflectionTestUtils.setField(controller, "shopTypeService", shopTypeService);
        ReflectionTestUtils.setField(controller, "shopGeoIndexService", shopGeoIndexService);
        ReflectionTestUtils.setField(controller, "operationLogService", operationLogService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .setValidator(validator)
                .build();
    }

    @Test
    void createShopTypeShouldReturnAdminVoAndRecordAudit() throws Exception {
        ShopTypeAdminVO vo = new ShopTypeAdminVO();
        vo.setId(10L);
        vo.setName("Food");
        vo.setIcon("/types/10.png");
        vo.setSort(1);
        vo.setStatus(1);
        when(shopTypeService.createType(any())).thenReturn(vo);

        mockMvc.perform(post("/admin/shop-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Food\",\"icon\":\"/types/10.png\",\"sort\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.status").value(1));

        verify(operationLogService).record(eq("shop_type"), eq("create"), eq("shop_type"),
                isNull(), contains("name=Food"), eq(true), isNull());
    }

    @Test
    void createShopTypeShouldReturnParamErrorWhenNameBlank() throws Exception {
        mockMvc.perform(post("/admin/shop-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"icon\":\"/types/10.png\",\"sort\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        verify(shopTypeService, never()).createType(any());
    }

    @Test
    void updateShopTypeStatusShouldDelegateAndRecordAudit() throws Exception {
        mockMvc.perform(patch("/admin/shop-types/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10,\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(shopTypeService).updateTypeStatus(any());
        verify(operationLogService).record(eq("shop_type"), eq("update_status"), eq("shop_type"),
                eq("10"), contains("status=0"), eq(true), isNull());
    }

    @Test
    void rebuildShopGeoShouldReturnDomainErrorWhenRebuildFails() throws Exception {
        when(shopGeoIndexService.rebuildAll()).thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(post("/admin/shops/geo/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_GEO_REBUILD_FAILED.getCode()));

        verify(operationLogService).record(eq("shop_geo"), eq("rebuild"), eq("shop_geo"),
                isNull(), anyString(), eq(false), eq("redis down"));
    }
}

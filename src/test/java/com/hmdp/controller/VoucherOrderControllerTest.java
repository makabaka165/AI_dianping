package com.hmdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VoucherOrderControllerTest {

    @Mock
    private IVoucherOrderService voucherOrderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", voucherOrderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void payVoucherOrderShouldReturnParamErrorWhenBodyMissing() throws Exception {
        when(voucherOrderService.payVoucherOrder(1001L, null))
                .thenReturn(Result.fail(ErrorCode.PARAM_ERROR, "payRequestId is required"));

        mockMvc.perform(post("/voucher-order/pay/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.errorMsg").value("payRequestId is required"));

        verify(voucherOrderService).payVoucherOrder(1001L, null);
    }

    @Test
    void payVoucherOrderShouldReturnParamErrorWhenJsonMalformed() throws Exception {
        mockMvc.perform(post("/voucher-order/pay/1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.errorMsg").value("request body is invalid"));

        verify(voucherOrderService, never()).payVoucherOrder(any(), any());
    }

    @Test
    void payVoucherOrderShouldDelegatePayRequestId() throws Exception {
        when(voucherOrderService.payVoucherOrder(eq(1001L), eq("pay-1"))).thenReturn(Result.ok(1001L));

        mockMvc.perform(post("/voucher-order/pay/1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payRequestId\":\"pay-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(1001));

        verify(voucherOrderService).payVoucherOrder(1001L, "pay-1");
    }
}

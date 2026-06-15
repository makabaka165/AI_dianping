package com.hmdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherCreateDTO;
import com.hmdp.dto.VoucherCreateDTO;
import com.hmdp.service.IVoucherService;
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

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VoucherControllerTest {

    @Mock
    private IVoucherService voucherService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", voucherService);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void addVoucherShouldRejectInvalidRequestBeforeService() throws Exception {
        VoucherCreateDTO request = validVoucher();
        request.setShopId(0L);

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.errorMsg", containsString("shopId")));

        verify(voucherService, never()).addVoucher(any());
    }

    @Test
    void addVoucherShouldDelegateDtoRequest() throws Exception {
        when(voucherService.addVoucher(any(VoucherCreateDTO.class))).thenReturn(Result.ok(12L));

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validVoucher())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(12));

        verify(voucherService).addVoucher(any(VoucherCreateDTO.class));
    }

    @Test
    void addSeckillVoucherShouldRejectInvalidRequestBeforeService() throws Exception {
        SeckillVoucherCreateDTO request = validSeckillVoucher();
        request.setStock(0);

        mockMvc.perform(post("/voucher/seckill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.errorMsg", containsString("stock")));

        verify(voucherService, never()).addSeckillVoucher(any());
    }

    private VoucherCreateDTO validVoucher() {
        VoucherCreateDTO request = new VoucherCreateDTO();
        request.setShopId(1L);
        request.setTitle("50 yuan coupon");
        request.setSubTitle("weekday");
        request.setRules("rules");
        request.setPayValue(4750L);
        request.setActualValue(5000L);
        request.setType(0);
        return request;
    }

    private SeckillVoucherCreateDTO validSeckillVoucher() {
        SeckillVoucherCreateDTO request = new SeckillVoucherCreateDTO();
        request.setShopId(1L);
        request.setTitle("flash sale coupon");
        request.setSubTitle("limited");
        request.setRules("rules");
        request.setPayValue(4750L);
        request.setActualValue(5000L);
        request.setStock(3);
        request.setBeginTime(LocalDateTime.now().plusDays(1));
        request.setEndTime(LocalDateTime.now().plusDays(2));
        return request;
    }
}

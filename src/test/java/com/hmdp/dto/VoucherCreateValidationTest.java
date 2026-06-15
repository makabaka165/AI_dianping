package com.hmdp.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherCreateValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void blogCreateRequestShouldRejectNonPositiveShopId() {
        BlogCreateRequest request = new BlogCreateRequest();
        request.setShopId(0L);
        request.setTitle("title");
        request.setImages("/imgs/a.jpg");
        request.setContent("content");

        assertThat(messagesOf(validator.validate(request)))
                .contains("shopId must be greater than 0");
    }

    @Test
    void voucherCreateDtoShouldRejectInvalidFields() {
        VoucherCreateDTO request = validVoucher();
        request.setShopId(-1L);
        request.setTitle("");
        request.setPayValue(-1L);
        request.setActualValue(1L);
        request.setType(1);
        request.setBeginTime(LocalDateTime.of(2099, 1, 2, 0, 0));
        request.setEndTime(LocalDateTime.of(2099, 1, 1, 0, 0));

        Set<String> messages = messagesOf(validator.validate(request));

        assertThat(messages).contains(
                "shopId must be greater than 0",
                "title is required",
                "payValue must be greater than 0",
                "type only supports normal voucher for this endpoint",
                "beginTime must be before endTime"
        );
    }

    @Test
    void voucherCreateDtoShouldAcceptFaceValueNotLessThanPayValue() {
        VoucherCreateDTO request = validVoucher();
        request.setPayValue(4750L);
        request.setActualValue(5000L);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void voucherCreateDtoShouldRejectActualValueLowerThanPayValue() {
        VoucherCreateDTO request = validVoucher();
        request.setPayValue(5000L);
        request.setActualValue(4750L);

        assertThat(messagesOf(validator.validate(request)))
                .contains("actualValue must be greater than or equal to payValue");
    }

    @Test
    void seckillVoucherCreateDtoShouldRejectInvalidStockAndTimeRange() {
        SeckillVoucherCreateDTO request = validSeckillVoucher();
        request.setStock(0);
        request.setBeginTime(LocalDateTime.of(2099, 1, 2, 0, 0));
        request.setEndTime(LocalDateTime.of(2099, 1, 1, 0, 0));

        Set<String> messages = messagesOf(validator.validate(request));

        assertThat(messages).contains(
                "stock must be greater than 0",
                "beginTime must be before endTime"
        );
    }

    @Test
    void seckillVoucherCreateDtoShouldRejectExpiredEndTime() {
        SeckillVoucherCreateDTO request = validSeckillVoucher();
        request.setBeginTime(LocalDateTime.now().minusDays(2));
        request.setEndTime(LocalDateTime.now().minusDays(1));

        assertThat(messagesOf(validator.validate(request)))
                .contains("endTime must be in the future");
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

    private Set<String> messagesOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}

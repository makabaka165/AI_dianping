package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Future;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
public class SeckillVoucherCreateDTO {

    @NotNull(message = "shopId is required")
    @Positive(message = "shopId must be greater than 0")
    private Long shopId;

    @NotBlank(message = "title is required")
    @Size(max = 128, message = "title length must be less than or equal to 128")
    private String title;

    @Size(max = 255, message = "subTitle length must be less than or equal to 255")
    private String subTitle;

    @Size(max = 1024, message = "rules length must be less than or equal to 1024")
    private String rules;

    @NotNull(message = "payValue is required")
    @Positive(message = "payValue must be greater than 0")
    private Long payValue;

    @NotNull(message = "actualValue is required")
    @Positive(message = "actualValue must be greater than 0")
    private Long actualValue;

    @NotNull(message = "stock is required")
    @Positive(message = "stock must be greater than 0")
    private Integer stock;

    @NotNull(message = "beginTime is required")
    private LocalDateTime beginTime;

    @NotNull(message = "endTime is required")
    @Future(message = "endTime must be in the future")
    private LocalDateTime endTime;

    @JsonIgnore
    @AssertTrue(message = "actualValue must be greater than or equal to payValue")
    public boolean isAmountRangeValid() {
        return payValue == null || actualValue == null || actualValue >= payValue;
    }

    @JsonIgnore
    @AssertTrue(message = "beginTime must be before endTime")
    public boolean isTimeRangeValid() {
        return beginTime == null || endTime == null || beginTime.isBefore(endTime);
    }
}

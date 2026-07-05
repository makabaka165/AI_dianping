package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class VoucherOrderPayRequest {

    @NotBlank(message = "payRequestId is required")
    @Size(max = 64, message = "payRequestId length must be less than or equal to 64")
    private String payRequestId;
}

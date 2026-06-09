package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
public class ShopTypeStatusDTO {

    @NotNull(message = "id is required")
    @Positive(message = "id must be greater than 0")
    private Long id;

    @NotNull(message = "status is required")
    private Integer status;
}

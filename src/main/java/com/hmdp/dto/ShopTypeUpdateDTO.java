package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Size;

@Data
public class ShopTypeUpdateDTO {

    @NotNull(message = "id is required")
    @Positive(message = "id must be greater than 0")
    private Long id;

    @NotBlank(message = "name is required")
    @Size(max = 64, message = "name length must be less than or equal to 64")
    private String name;

    @NotBlank(message = "icon is required")
    @Size(max = 255, message = "icon length must be less than or equal to 255")
    private String icon;

    @NotNull(message = "sort is required")
    @PositiveOrZero(message = "sort must be greater than or equal to 0")
    private Integer sort;
}

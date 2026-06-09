package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShopStatusVO {

    private Boolean exists;
    private Integer reviewCount;
}

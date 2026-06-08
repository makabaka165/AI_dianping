package com.hmdp.dto;

import lombok.Data;

@Data
public class UpdateUserStatusDTO {
    private Long userId;
    private Integer status;
    private String reason;
}

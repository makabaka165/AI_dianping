package com.hmdp.dto;

import lombok.Data;

@Data
public class UpdateUserRoleStatusDTO {
    private Long userId;
    private String roleKey;
    private Integer status;
    private String reason;
}

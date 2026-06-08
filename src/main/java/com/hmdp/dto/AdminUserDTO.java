package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminUserDTO {
    private Long id;
    private String phone;
    private String nickName;
    private String icon;
    private Integer status;
    private LocalDateTime createTime;
    private List<String> roles;
}

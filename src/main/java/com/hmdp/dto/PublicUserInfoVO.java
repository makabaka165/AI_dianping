package com.hmdp.dto;

import lombok.Data;

@Data
public class PublicUserInfoVO {

    private Long userId;

    private String city;

    private String introduce;

    private Integer fans;

    private Integer followee;
}

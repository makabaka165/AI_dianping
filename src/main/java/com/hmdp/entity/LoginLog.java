package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_login_log")
public class LoginLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String phone;

    private String loginType;

    private String action;

    private Integer success;

    private String failReason;

    private String ip;

    private String userAgent;

    private String deviceFingerprint;

    private String tokenId;

    private LocalDateTime loginTime;

    private LocalDateTime logoutTime;

    private Integer riskLevel;

    private Integer failCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

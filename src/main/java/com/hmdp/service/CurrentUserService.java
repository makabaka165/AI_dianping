package com.hmdp.service;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    public Long getCurrentUserId() {
        if (StpUtil.isLogin()) {
            return StpUtil.getLoginIdAsLong();
        }
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    public Long requireCurrentUserId() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            StpUtil.checkLogin();
            return StpUtil.getLoginIdAsLong();
        }
        return userId;
    }

    public UserDTO getCurrentUser() {
        return UserHolder.getUser();
    }
}

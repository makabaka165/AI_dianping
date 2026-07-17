package com.hmdp.utils;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.exception.BusinessException;
import com.hmdp.service.IUserService;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SaTokenUserHolderInterceptor implements HandlerInterceptor {

    private static final int USER_STATUS_DISABLED = 0;

    private final IUserService userService;

    public SaTokenUserHolderInterceptor(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserHolder.getUser() != null || !StpUtil.isLogin()) {
            return true;
        }

        Long userId = StpUtil.getLoginIdAsLong();
        User user = userService.getById(userId);
        if (user == null) {
            StpUtil.logout(userId);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (Integer.valueOf(USER_STATUS_DISABLED).equals(user.getStatus())) {
            StpUtil.logout(userId);
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        UserHolder.saveUser(BeanUtil.copyProperties(user, UserDTO.class));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}

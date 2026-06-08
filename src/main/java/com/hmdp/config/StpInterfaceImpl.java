package com.hmdp.config;

import cn.dev33.satoken.stp.StpInterface;
import com.hmdp.service.IPermissionService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class StpInterfaceImpl implements StpInterface {

    private static final String ROLE_ADMIN = "admin";
    private static final String ALL_PERMISSION = "*";

    @Resource
    private IPermissionService permissionService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = parseUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        List<String> permissions = new ArrayList<>(permissionService.getPermissionCodesByUserId(userId));
        if (permissionService.hasRole(userId, ROLE_ADMIN) && !permissions.contains(ALL_PERMISSION)) {
            permissions.add(ALL_PERMISSION);
        }
        return permissions;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = parseUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        return permissionService.getRoleKeysByUserId(userId);
    }

    private Long parseUserId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        if (loginId instanceof Number) {
            return ((Number) loginId).longValue();
        }
        try {
            return Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

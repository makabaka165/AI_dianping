package com.hmdp.service;

import java.util.List;

public interface IPermissionService {

    List<String> getRoleKeysByUserId(Long userId);

    List<String> getPermissionCodesByUserId(Long userId);

    boolean hasRole(Long userId, String roleKey);

    boolean hasPermission(Long userId, String permissionCode);

    void assignDefaultBuyerRole(Long userId);

    java.util.List<com.hmdp.entity.Role> listRoles();

    java.util.List<com.hmdp.entity.Permission> listPermissions();

    void assignRole(Long userId, String roleKey);

    void setUserRoleStatus(Long userId, String roleKey, Integer status);
}

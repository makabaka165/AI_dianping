package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.Permission;
import com.hmdp.entity.Role;
import com.hmdp.entity.UserRole;
import com.hmdp.mapper.PermissionMapper;
import com.hmdp.mapper.PermissionQueryMapper;
import com.hmdp.mapper.RoleMapper;
import com.hmdp.mapper.UserRoleMapper;
import com.hmdp.service.IPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class PermissionServiceImpl implements IPermissionService {

    private static final String DEFAULT_ROLE_BUYER = "buyer";
    private static final int ENABLED = 1;

    @Resource
    private PermissionQueryMapper permissionQueryMapper;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private UserRoleMapper userRoleMapper;

    @Resource
    private PermissionMapper permissionMapper;

    @Override
    public List<String> getRoleKeysByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return permissionQueryMapper.selectRoleKeysByUserId(userId);
    }

    @Override
    public List<String> getPermissionCodesByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return permissionQueryMapper.selectPermissionCodesByUserId(userId);
    }

    @Override
    public boolean hasRole(Long userId, String roleKey) {
        if (userId == null || StrUtil.isBlank(roleKey)) {
            return false;
        }
        return getRoleKeysByUserId(userId).contains(roleKey);
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || StrUtil.isBlank(permissionCode)) {
            return false;
        }
        return getPermissionCodesByUserId(userId).contains(permissionCode);
    }

    @Override
    public void assignDefaultBuyerRole(Long userId) {
        if (userId == null) {
            return;
        }
        Role buyerRole = roleMapper.selectOne(
                new QueryWrapper<Role>()
                        .eq("role_key", DEFAULT_ROLE_BUYER)
                        .eq("status", ENABLED)
        );
        if (buyerRole == null) {
            log.warn("默认买家角色不存在或未启用，用户{}未绑定默认角色", userId);
            return;
        }

        UserRole userRole = new UserRole()
                .setUserId(userId)
                .setRoleId(buyerRole.getId())
                .setStatus(ENABLED);
        try {
            userRoleMapper.insert(userRole);
        } catch (DuplicateKeyException e) {
            log.debug("用户{}已绑定默认买家角色，无需重复绑定", userId);
        }
    }

    @Override
    public List<Role> listRoles() {
        return roleMapper.selectList(new QueryWrapper<Role>().eq("status", ENABLED).orderByAsc("id"));
    }

    @Override
    public List<Permission> listPermissions() {
        return permissionMapper.selectList(new QueryWrapper<Permission>().eq("status", ENABLED).orderByAsc("permission_code"));
    }

    @Override
    public void assignRole(Long userId, String roleKey) {
        if (userId == null || StrUtil.isBlank(roleKey)) {
            throw new IllegalArgumentException("用户ID和角色不能为空");
        }
        if (!Arrays.asList("buyer", "merchant", "admin").contains(roleKey)) {
            throw new IllegalArgumentException("仅支持 buyer、merchant、admin 三类角色");
        }
        Role role = roleMapper.selectOne(new QueryWrapper<Role>()
                .eq("role_key", roleKey)
                .eq("status", ENABLED));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在或已禁用");
        }

        userRoleMapper.update(null, new UpdateWrapper<UserRole>()
                .set("status", 0)
                .eq("user_id", userId));

        UserRole userRole = new UserRole()
                .setUserId(userId)
                .setRoleId(role.getId())
                .setStatus(ENABLED);
        try {
            userRoleMapper.insert(userRole);
        } catch (DuplicateKeyException e) {
            userRoleMapper.update(null, new UpdateWrapper<UserRole>()
                    .set("status", ENABLED)
                    .eq("user_id", userId)
                    .eq("role_id", role.getId()));
        }
    }
}

package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.AdminUserDTO;
import com.hmdp.dto.AssignRoleDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IUserService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/rbac")
public class AdminRbacController {

    @Resource
    private IPermissionService permissionService;

    @Resource
    private IUserService userService;

    @Resource
    private IOperationLogService operationLogService;

    @GetMapping("/users")
    @SaCheckPermission("role:assign")
    public Result pageUsers(@RequestParam(value = "current", defaultValue = "1") Integer current,
                            @RequestParam(value = "size", defaultValue = "10") Integer size,
                            @RequestParam(value = "phone", required = false) String phone) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null ? 10 : Math.min(Math.max(size, 1), 50);
        Page<User> page = userService.query()
                .like(StrUtil.isNotBlank(phone), "phone", phone)
                .orderByDesc("id")
                .page(new Page<>(pageNo, pageSize));
        List<AdminUserDTO> users = page.getRecords().stream()
                .map(user -> {
                    AdminUserDTO dto = BeanUtil.copyProperties(user, AdminUserDTO.class);
                    dto.setRoles(permissionService.getRoleKeysByUserId(user.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
        return Result.ok(users, page.getTotal());
    }

    @GetMapping("/users/{userId}/roles")
    @SaCheckPermission("role:assign")
    public Result getUserRoles(@PathVariable Long userId) {
        if (userId == null || userId <= 0) {
            return Result.fail("用户ID不合法");
        }
        return Result.ok(permissionService.getRoleKeysByUserId(userId));
    }

    @PostMapping("/users/roles")
    @SaCheckPermission("role:assign")
    public Result assignRole(@RequestBody AssignRoleDTO request) {
        if (request == null) {
            return Result.fail("请求参数不能为空");
        }
        if (request.getUserId() == null || request.getUserId() <= 0) {
            return Result.fail("用户ID不合法");
        }
        if (!StrUtil.equalsAny(request.getRoleKey(), "buyer", "merchant", "admin")) {
            return Result.fail("角色只支持 buyer、merchant、admin");
        }
        User user = userService.getById(request.getUserId());
        if (user == null) {
            return Result.fail("用户不存在");
        }
        try {
            permissionService.assignRole(request.getUserId(), request.getRoleKey());
            operationLogService.record("rbac", "assign_role", "user", String.valueOf(request.getUserId()),
                    "roleKey=" + request.getRoleKey(), true, null);
        } catch (RuntimeException e) {
            operationLogService.record("rbac", "assign_role", "user", String.valueOf(request.getUserId()),
                    "roleKey=" + request.getRoleKey(), false, e.getMessage());
            throw e;
        }
        return Result.ok();
    }

    @GetMapping("/roles")
    @SaCheckPermission("role:assign")
    public Result listRoles() {
        return Result.ok(permissionService.listRoles());
    }

    @GetMapping("/permissions")
    @SaCheckPermission("role:assign")
    public Result listPermissions() {
        return Result.ok(permissionService.listPermissions());
    }
}

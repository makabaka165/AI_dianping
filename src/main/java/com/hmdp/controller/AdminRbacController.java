package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.AdminUserDTO;
import com.hmdp.dto.AssignRoleDTO;
import com.hmdp.dto.MerchantShopBindDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserRoleStatusDTO;
import com.hmdp.dto.UpdateUserStatusDTO;
import com.hmdp.entity.MerchantShop;
import com.hmdp.entity.OperationLog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.exception.BusinessException;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/rbac")
public class AdminRbacController {

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    @Resource
    private IPermissionService permissionService;

    @Resource
    private IUserService userService;

    @Resource
    private IShopService shopService;

    @Resource
    private IMerchantShopService merchantShopService;

    @Resource
    private IOperationLogService operationLogService;

    @GetMapping("/users")
    @SaCheckPermission("user:read")
    public Result pageUsers(@RequestParam(value = "current", defaultValue = "1") Integer current,
                            @RequestParam(value = "size", defaultValue = "10") Integer size,
                            @RequestParam(value = "phone", required = false) String phone,
                            @RequestParam(value = "status", required = false) Integer status) {
        int pageNo = normalizePage(current);
        int pageSize = normalizeSize(size, 100);
        Page<User> page = userService.query()
                .like(StrUtil.isNotBlank(phone), "phone", phone)
                .eq(status != null, "status", status)
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

    @PatchMapping("/users/status")
    @SaCheckPermission("user:disable")
    public Result updateUserStatus(@RequestBody UpdateUserStatusDTO request) {
        validateUserStatusRequest(request);
        User user = requireUser(request.getUserId());
        try {
            userService.update()
                    .set("status", request.getStatus())
                    .eq("id", request.getUserId())
                    .update();
            if (Integer.valueOf(DISABLED).equals(request.getStatus())) {
                StpUtil.logout(request.getUserId());
            }
            operationLogService.record("rbac", "update_user_status", "user", String.valueOf(request.getUserId()),
                    "status=" + request.getStatus() + ", reason=" + StrUtil.nullToEmpty(request.getReason()),
                    true, null);
        } catch (RuntimeException e) {
            operationLogService.record("rbac", "update_user_status", "user", String.valueOf(request.getUserId()),
                    "status=" + request.getStatus(), false, e.getMessage());
            throw e;
        }
        return Result.ok(BeanUtil.copyProperties(user.setStatus(request.getStatus()), AdminUserDTO.class));
    }

    @GetMapping("/users/{userId}/roles")
    @SaCheckPermission("role:assign")
    public Result getUserRoles(@PathVariable Long userId) {
        validateId(userId, "userId");
        requireUser(userId);
        return Result.ok(permissionService.getRoleKeysByUserId(userId));
    }

    @PostMapping("/users/roles")
    @SaCheckPermission("role:assign")
    public Result assignRole(@RequestBody AssignRoleDTO request) {
        validateAssignRoleRequest(request);
        requireUser(request.getUserId());
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

    @PatchMapping("/users/roles/status")
    @SaCheckPermission("role:assign")
    public Result updateUserRoleStatus(@RequestBody UpdateUserRoleStatusDTO request) {
        validateRoleStatusRequest(request);
        requireUser(request.getUserId());
        try {
            permissionService.setUserRoleStatus(request.getUserId(), request.getRoleKey(), request.getStatus());
            if ("admin".equals(request.getRoleKey())) {
                StpUtil.logout(request.getUserId());
            }
            operationLogService.record("rbac", "update_user_role_status", "user", String.valueOf(request.getUserId()),
                    "roleKey=" + request.getRoleKey() + ", status=" + request.getStatus() +
                            ", reason=" + StrUtil.nullToEmpty(request.getReason()),
                    true, null);
        } catch (RuntimeException e) {
            operationLogService.record("rbac", "update_user_role_status", "user", String.valueOf(request.getUserId()),
                    "roleKey=" + request.getRoleKey() + ", status=" + request.getStatus(),
                    false, e.getMessage());
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

    @GetMapping("/operation-logs")
    @SaCheckPermission("system:log:read")
    public Result pageOperationLogs(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                    @RequestParam(value = "size", defaultValue = "10") Integer size,
                                    @RequestParam(value = "module", required = false) String module,
                                    @RequestParam(value = "operation", required = false) String operation,
                                    @RequestParam(value = "operatorUserId", required = false) Long operatorUserId,
                                    @RequestParam(value = "success", required = false) Integer success) {
        Page<OperationLog> page = operationLogService.pageLogs(current, size, module, operation, operatorUserId, success);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/merchant-shops")
    @SaCheckPermission("shop:update")
    public Result pageMerchantShops(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                    @RequestParam(value = "size", defaultValue = "10") Integer size,
                                    @RequestParam(value = "merchantUserId", required = false) Long merchantUserId,
                                    @RequestParam(value = "shopId", required = false) Long shopId,
                                    @RequestParam(value = "status", required = false) Integer status) {
        Page<MerchantShop> page = merchantShopService.query()
                .eq(merchantUserId != null && merchantUserId > 0, "merchant_user_id", merchantUserId)
                .eq(shopId != null && shopId > 0, "shop_id", shopId)
                .eq(status != null, "status", status)
                .orderByDesc("id")
                .page(new Page<>(normalizePage(current), normalizeSize(size, 100)));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @PostMapping("/merchant-shops")
    @SaCheckPermission("shop:update")
    public Result bindMerchantShop(@RequestBody MerchantShopBindDTO request) {
        validateMerchantShopRequest(request);
        requireUser(request.getMerchantUserId());
        requireShop(request.getShopId());
        try {
            merchantShopService.bindMerchantShop(request.getMerchantUserId(), request.getShopId(), request.getRemark());
            operationLogService.record("merchant_shop", "bind", "shop", String.valueOf(request.getShopId()),
                    "merchantUserId=" + request.getMerchantUserId() + ", remark=" + StrUtil.nullToEmpty(request.getRemark()),
                    true, null);
        } catch (RuntimeException e) {
            operationLogService.record("merchant_shop", "bind", "shop", String.valueOf(request.getShopId()),
                    "merchantUserId=" + request.getMerchantUserId(), false, e.getMessage());
            throw e;
        }
        return Result.ok();
    }

    @DeleteMapping("/merchant-shops")
    @SaCheckPermission("shop:update")
    public Result unbindMerchantShop(@RequestBody MerchantShopBindDTO request) {
        validateMerchantShopRequest(request);
        try {
            merchantShopService.unbindMerchantShop(request.getMerchantUserId(), request.getShopId(), request.getRemark());
            operationLogService.record("merchant_shop", "unbind", "shop", String.valueOf(request.getShopId()),
                    "merchantUserId=" + request.getMerchantUserId() + ", remark=" + StrUtil.nullToEmpty(request.getRemark()),
                    true, null);
        } catch (RuntimeException e) {
            operationLogService.record("merchant_shop", "unbind", "shop", String.valueOf(request.getShopId()),
                    "merchantUserId=" + request.getMerchantUserId(), false, e.getMessage());
            throw e;
        }
        return Result.ok();
    }

    private void validateUserStatusRequest(UpdateUserStatusDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "request body is required");
        }
        validateId(request.getUserId(), "userId");
        if (!Integer.valueOf(ENABLED).equals(request.getStatus()) && !Integer.valueOf(DISABLED).equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status only supports 1 enabled or 0 disabled");
        }
    }

    private void validateAssignRoleRequest(AssignRoleDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "request body is required");
        }
        validateId(request.getUserId(), "userId");
        validateRoleKey(request.getRoleKey());
    }

    private void validateRoleStatusRequest(UpdateUserRoleStatusDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "request body is required");
        }
        validateId(request.getUserId(), "userId");
        validateRoleKey(request.getRoleKey());
        if (!Integer.valueOf(ENABLED).equals(request.getStatus()) && !Integer.valueOf(DISABLED).equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status only supports 1 enabled or 0 disabled");
        }
    }

    private void validateMerchantShopRequest(MerchantShopBindDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "request body is required");
        }
        validateId(request.getMerchantUserId(), "merchantUserId");
        validateId(request.getShopId(), "shopId");
    }

    private void validateRoleKey(String roleKey) {
        if (!StrUtil.equalsAny(roleKey, "buyer", "merchant", "admin")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "roleKey only supports buyer, merchant, admin");
        }
    }

    private void validateId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, name + " is invalid");
        }
    }

    private User requireUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        return user;
    }

    private Shop requireShop(Long shopId) {
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "shop not found");
        }
        return shop;
    }

    private int normalizePage(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    private int normalizeSize(Integer size, int max) {
        return size == null ? 10 : Math.min(Math.max(size, 1), max);
    }
}

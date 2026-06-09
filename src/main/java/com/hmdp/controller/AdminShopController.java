package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopTypeCreateDTO;
import com.hmdp.dto.ShopTypeStatusDTO;
import com.hmdp.dto.ShopTypeUpdateDTO;
import com.hmdp.exception.BusinessException;
import com.hmdp.service.IOperationLogService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.ShopGeoIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin")
@Slf4j
public class AdminShopController {

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Resource
    private IOperationLogService operationLogService;

    @GetMapping("/shop-types")
    @SaCheckPermission("shop:type:manage")
    public Result pageShopTypes(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                @RequestParam(value = "size", defaultValue = "10") Integer size,
                                @RequestParam(value = "status", required = false) Integer status) {
        return Result.ok(shopTypeService.queryAdminTypePage(current, size, status));
    }

    @PostMapping("/shop-types")
    @SaCheckPermission("shop:type:manage")
    public Result createShopType(@RequestBody @Validated ShopTypeCreateDTO request) {
        try {
            Object result = shopTypeService.createType(request);
            record("shop_type", "create", null, "name=" + request.getName(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("shop_type", "create", null, "name=" + request.getName(), false, e.getMessage());
            throw e;
        }
    }

    @PutMapping("/shop-types")
    @SaCheckPermission("shop:type:manage")
    public Result updateShopType(@RequestBody @Validated ShopTypeUpdateDTO request) {
        try {
            Object result = shopTypeService.updateType(request);
            record("shop_type", "update", request.getId(), "name=" + request.getName(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("shop_type", "update", request.getId(), "name=" + request.getName(), false, e.getMessage());
            throw e;
        }
    }

    @PatchMapping("/shop-types/status")
    @SaCheckPermission("shop:type:manage")
    public Result updateShopTypeStatus(@RequestBody @Validated ShopTypeStatusDTO request) {
        try {
            shopTypeService.updateTypeStatus(request);
            record("shop_type", "update_status", request.getId(), "status=" + request.getStatus(), true, null);
            return Result.ok();
        } catch (RuntimeException e) {
            record("shop_type", "update_status", request.getId(), "status=" + request.getStatus(), false, e.getMessage());
            throw e;
        }
    }

    @DeleteMapping("/shop-types/{id}")
    @SaCheckPermission("shop:type:manage")
    public Result deleteShopType(@PathVariable Long id) {
        try {
            shopTypeService.deleteType(id);
            record("shop_type", "delete", id, "soft delete", true, null);
            return Result.ok();
        } catch (RuntimeException e) {
            record("shop_type", "delete", id, "soft delete", false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/shops/geo/rebuild")
    @SaCheckPermission("shop:geo:rebuild")
    public Result rebuildShopGeo(@RequestParam(value = "typeId", required = false) Long typeId) {
        try {
            Object result = typeId == null ? shopGeoIndexService.rebuildAll() : shopGeoIndexService.rebuildByTypeId(typeId);
            record("shop_geo", "rebuild", typeId, "typeId=" + typeId, true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("shop_geo", "rebuild", typeId, "typeId=" + typeId, false, e.getMessage());
            throw new BusinessException(ErrorCode.SHOP_GEO_REBUILD_FAILED, e.getMessage());
        }
    }

    private void record(String module, String operation, Long targetId, String detail, boolean success, String failReason) {
        if (operationLogService == null) {
            return;
        }
        try {
            operationLogService.record(module, operation, module, targetId == null ? null : String.valueOf(targetId),
                    detail, success, failReason);
        } catch (Exception e) {
            log.warn("Record admin shop operation log failed, module={}, operation={}, targetId={}",
                    module, operation, targetId, e);
        }
    }
}

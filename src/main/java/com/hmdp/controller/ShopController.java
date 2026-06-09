package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCreateDTO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.service.IShopService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    private IShopService shopService;

    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y,
            @RequestParam(value = "lastDistance", required = false) Double lastDistance,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "sortBy", defaultValue = "distance") String sortBy,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "area", required = false) String area,
            @RequestParam(value = "minScore", required = false) Integer minScore,
            @RequestParam(value = "minAvgPrice", required = false) Long minAvgPrice,
            @RequestParam(value = "maxAvgPrice", required = false) Long maxAvgPrice,
            @RequestParam(value = "openNow", defaultValue = "false") Boolean openNow,
            @RequestParam(value = "pageResult", defaultValue = "true") Boolean pageResult) {
        return shopService.queryShopByType(typeId, current, x, y, lastDistance, lastId, sortBy,
                keyword, area, minScore, minAvgPrice, maxAvgPrice, openNow, pageResult);
    }

    @PostMapping
    @SaCheckPermission(value = {"shop:create:own", "shop:create"}, mode = SaMode.OR)
    public Result saveShop(@RequestBody @Validated ShopCreateDTO request) {
        return shopService.createShop(request);
    }

    @PutMapping
    @SaCheckPermission(value = {"shop:update:own", "shop:update"}, mode = SaMode.OR)
    public Result updateShop(@RequestBody @Validated ShopUpdateDTO request) {
        return shopService.updateShop(request);
    }

    @GetMapping("/{id}/status")
    public Result getShopStatus(@PathVariable("id") Long id) {
        return shopService.queryShopStatus(id);
    }
}

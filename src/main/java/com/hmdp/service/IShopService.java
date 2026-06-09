package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCreateDTO;
import com.hmdp.dto.ShopUpdateDTO;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result createShop(ShopCreateDTO request);

    Result updateShop(ShopUpdateDTO request);

    Result queryShopStatus(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y,
                           Double lastDistance, Long lastId, String sortBy,
                           String keyword, String area, Integer minScore,
                           Long minAvgPrice, Long maxAvgPrice, Boolean openNow,
                           Boolean pageResult);
}

package com.hmdp.service;

import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.ShopTypeAdminVO;
import com.hmdp.dto.ShopTypeCreateDTO;
import com.hmdp.dto.ShopTypeStatusDTO;
import com.hmdp.dto.ShopTypeUpdateDTO;
import com.hmdp.dto.ShopTypeVO;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    List<ShopTypeVO> queryTypeList();

    void evictTypeListCache();

    List<ShopTypeVO> refreshTypeListCache();

    PageResult<ShopTypeAdminVO> queryAdminTypePage(Integer current, Integer size, Integer status);

    ShopTypeAdminVO createType(ShopTypeCreateDTO request);

    ShopTypeAdminVO updateType(ShopTypeUpdateDTO request);

    void updateTypeStatus(ShopTypeStatusDTO request);

    void deleteType(Long id);
}

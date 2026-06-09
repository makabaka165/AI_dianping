package com.hmdp.service;

import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;
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
}

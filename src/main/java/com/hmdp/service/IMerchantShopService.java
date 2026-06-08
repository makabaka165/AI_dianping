package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.MerchantShop;

public interface IMerchantShopService extends IService<MerchantShop> {

    boolean isShopOwner(Long merchantUserId, Long shopId);

    void bindMerchantShop(Long merchantUserId, Long shopId, String remark);

    void unbindMerchantShop(Long merchantUserId, Long shopId, String remark);
}

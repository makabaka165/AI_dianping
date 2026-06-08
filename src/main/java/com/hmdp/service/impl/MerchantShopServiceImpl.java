package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.MerchantShop;
import com.hmdp.mapper.MerchantShopMapper;
import com.hmdp.service.IMerchantShopService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MerchantShopServiceImpl extends ServiceImpl<MerchantShopMapper, MerchantShop> implements IMerchantShopService {

    private static final int ENABLED = 1;

    @Override
    public boolean isShopOwner(Long merchantUserId, Long shopId) {
        if (merchantUserId == null || shopId == null) {
            return false;
        }
        Integer count = count(new QueryWrapper<MerchantShop>()
                .eq("merchant_user_id", merchantUserId)
                .eq("shop_id", shopId)
                .eq("status", ENABLED));
        return count != null && count > 0;
    }

    @Override
    public void bindMerchantShop(Long merchantUserId, Long shopId, String remark) {
        if (merchantUserId == null || shopId == null) {
            return;
        }
        MerchantShop merchantShop = new MerchantShop()
                .setMerchantUserId(merchantUserId)
                .setShopId(shopId)
                .setStatus(ENABLED)
                .setRemark(remark);
        try {
            save(merchantShop);
        } catch (DuplicateKeyException e) {
            update()
                    .set("status", ENABLED)
                    .set("remark", remark)
                    .eq("merchant_user_id", merchantUserId)
                    .eq("shop_id", shopId)
                    .update();
        }
    }
}

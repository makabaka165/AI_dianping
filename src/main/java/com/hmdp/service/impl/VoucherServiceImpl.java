package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherCreateDTO;
import com.hmdp.dto.VoucherCreateDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.BusinessException;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private static final ZoneId SECKILL_TIME_ZONE = ZoneId.systemDefault();
    private static final int VOUCHER_STATUS_ON_SHELF = 1;
    private static final int NORMAL_VOUCHER_TYPE = 0;
    private static final int SECKILL_VOUCHER_TYPE = 1;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CurrentUserService currentUserService;
    @Resource
    private IPermissionService permissionService;
    @Resource
    private IMerchantShopService merchantShopService;
    @Resource
    private ShopMapper shopMapper;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public Result addVoucher(VoucherCreateDTO request) {
        Result validationResult = validateCreateRequest(request);
        if (validationResult != null) {
            return validationResult;
        }
        Voucher voucher = toVoucher(request, NORMAL_VOUCHER_TYPE);
        boolean saved = saveVoucherEntity(voucher);
        if (!saved) {
            return Result.fail(ErrorCode.BUSINESS_ERROR, "voucher create failed");
        }
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(SeckillVoucherCreateDTO request) {
        Result validationResult = validateCreateRequest(request);
        if (validationResult != null) {
            return validationResult;
        }
        Voucher voucher = toVoucher(request, SECKILL_VOUCHER_TYPE);
        boolean voucherSaved = saveVoucherEntity(voucher);
        if (!voucherSaved) {
            return Result.fail(ErrorCode.BUSINESS_ERROR, "voucher create failed");
        }

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(request.getStock());
        seckillVoucher.setBeginTime(request.getBeginTime());
        seckillVoucher.setEndTime(request.getEndTime());
        boolean seckillSaved = seckillVoucherService.save(seckillVoucher);
        if (!seckillSaved) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "seckill voucher create failed");
        }

        registerAfterCommit(() -> prewarmSeckillVoucher(voucher.getId(), request));
        return Result.ok(voucher.getId());
    }

    @Override
    public boolean save(Voucher entity) {
        if (entity == null || entity.getShopId() == null || entity.getShopId() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "shopId must be greater than 0");
        }
        if (!existsActiveShop(entity.getShopId())) {
            throw new BusinessException(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }
        enforceCanManageVoucherShop(entity.getShopId());
        return super.save(entity);
    }

    protected boolean saveVoucherEntity(Voucher voucher) {
        return super.save(voucher);
    }

    private Result validateCreateRequest(VoucherCreateDTO request) {
        if (request == null || request.getShopId() == null || request.getShopId() <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "shopId must be greater than 0");
        }
        if (!existsActiveShop(request.getShopId())) {
            return Result.fail(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }
        if (!canManageVoucherShop(request.getShopId())) {
            return Result.fail(ErrorCode.FORBIDDEN, "permission denied");
        }
        return null;
    }

    private Result validateCreateRequest(SeckillVoucherCreateDTO request) {
        if (request == null || request.getShopId() == null || request.getShopId() <= 0) {
            return Result.fail(ErrorCode.PARAM_ERROR, "shopId must be greater than 0");
        }
        if (!existsActiveShop(request.getShopId())) {
            return Result.fail(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }
        if (!canManageVoucherShop(request.getShopId())) {
            return Result.fail(ErrorCode.FORBIDDEN, "permission denied");
        }
        return null;
    }

    private Voucher toVoucher(VoucherCreateDTO request, int type) {
        Voucher voucher = new Voucher();
        voucher.setShopId(request.getShopId());
        voucher.setTitle(request.getTitle());
        voucher.setSubTitle(request.getSubTitle());
        voucher.setRules(request.getRules());
        voucher.setPayValue(request.getPayValue());
        voucher.setActualValue(request.getActualValue());
        voucher.setType(type);
        voucher.setStatus(VOUCHER_STATUS_ON_SHELF);
        return voucher;
    }

    private Voucher toVoucher(SeckillVoucherCreateDTO request, int type) {
        Voucher voucher = new Voucher();
        voucher.setShopId(request.getShopId());
        voucher.setTitle(request.getTitle());
        voucher.setSubTitle(request.getSubTitle());
        voucher.setRules(request.getRules());
        voucher.setPayValue(request.getPayValue());
        voucher.setActualValue(request.getActualValue());
        voucher.setType(type);
        voucher.setStatus(VOUCHER_STATUS_ON_SHELF);
        return voucher;
    }

    private boolean existsActiveShop(Long shopId) {
        Integer count = shopMapper.selectCount(new QueryWrapper<Shop>().eq("id", shopId));
        return count != null && count > 0;
    }

    private void enforceCanManageVoucherShop(Long shopId) {
        if (!canManageVoucherShop(shopId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "permission denied");
        }
    }

    private boolean canManageVoucherShop(Long shopId) {
        Long userId = currentUserService.requireCurrentUserId();
        return permissionService.hasRole(userId, "admin") || merchantShopService.isShopOwner(userId, shopId);
    }

    private void prewarmSeckillVoucher(Long voucherId, SeckillVoucherCreateDTO request) {
        try {
            ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
            valueOperations.set(SECKILL_STOCK_KEY + voucherId, request.getStock().toString());
            valueOperations.set(SECKILL_BEGIN_KEY + voucherId,
                    String.valueOf(request.getBeginTime().atZone(SECKILL_TIME_ZONE).toEpochSecond()));
            valueOperations.set(SECKILL_END_KEY + voucherId,
                    String.valueOf(request.getEndTime().atZone(SECKILL_TIME_ZONE).toEpochSecond()));
        } catch (RuntimeException e) {
            log.error("prewarm seckill voucher Redis keys failed, voucherId={}", voucherId, e);
        }
    }

    private void registerAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}

package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
@Slf4j
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    private static final ZoneId SECKILL_TIME_ZONE = ZoneId.systemDefault();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void prewarmSeckillVouchers() {
        try {
            List<SeckillVoucher> vouchers = list();
            if (vouchers == null || vouchers.isEmpty()) {
                return;
            }
            int prewarmed = 0;
            for (SeckillVoucher voucher : vouchers) {
                if (prewarmSeckillVoucher(voucher)) {
                    prewarmed++;
                }
            }
            log.info("秒杀券Redis预热完成，count={}", prewarmed);
        } catch (Exception e) {
            log.warn("秒杀券Redis预热失败，后续可通过新增/更新秒杀券或补偿任务恢复", e);
        }
    }

    protected boolean prewarmSeckillVoucher(SeckillVoucher voucher) {
        if (voucher == null || voucher.getVoucherId() == null || voucher.getStock() == null) {
            return false;
        }
        stringRedisTemplate.opsForValue()
                .setIfAbsent(SECKILL_STOCK_KEY + voucher.getVoucherId(), voucher.getStock().toString());
        if (voucher.getBeginTime() != null) {
            stringRedisTemplate.opsForValue().set(SECKILL_BEGIN_KEY + voucher.getVoucherId(),
                    String.valueOf(voucher.getBeginTime().atZone(SECKILL_TIME_ZONE).toEpochSecond()));
        }
        if (voucher.getEndTime() != null) {
            stringRedisTemplate.opsForValue().set(SECKILL_END_KEY + voucher.getVoucherId(),
                    String.valueOf(voucher.getEndTime().atZone(SECKILL_TIME_ZONE).toEpochSecond()));
        }
        return true;
    }
}

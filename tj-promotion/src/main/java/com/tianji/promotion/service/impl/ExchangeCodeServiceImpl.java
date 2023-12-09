package com.tianji.promotion.service.impl;

import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_SERIAL_KEY;
import static com.tianji.promotion.constants.PromotionConstants.COUPON_RANGE_KEY;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-03
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {
    private final StringRedisTemplate redisTemplate;
    //异步生成兑换码
    @Override
    @Async("generateExchangeCodeExecutor")
    public void asyncGenerateExchangeCode(Coupon coupon) {
        log.debug("生成兑换码 线程名： {}", Thread.currentThread().getName());
        //获取优惠卷总数量
        Integer totalNum = coupon.getTotalNum();
        //1. 循环兑换码总数量
        //2. 调用incrby 批量兑换兑换码
        //1. 生成自增id  借助于redis incr 只需要操作一次redis
        Long increment = redisTemplate.opsForValue().increment(COUPON_CODE_SERIAL_KEY, totalNum);
        if(increment == null) {
            return;
        }
        int maxSerial = increment.intValue();
        int startSerial = maxSerial - totalNum + 1;     //自增id循环开始值
        List<ExchangeCode> list = new ArrayList<ExchangeCode>();
        //2. 循环生成兑换码  调用工具类生成兑换码
        for (int serialNum = startSerial; serialNum <= maxSerial; serialNum++) {
            String code = CodeUtil.generateCode(serialNum, coupon.getId());//参数1为自增id值， 参数2 为优惠卷id （内部会计算出0-15之间数字，然后找对应的密钥
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setCode(code);
            exchangeCode.setId(serialNum);                   //兑换码id    //po类的主键生成策略修改为INPUT
            exchangeCode.setExchangeTargetId(coupon.getId());     //优惠卷id
            exchangeCode.setExpiredTime(coupon.getIssueEndTime());   // 兑换码兑换的截止时间 就是优惠卷领取的截止时间
            list.add(exchangeCode);
        }
        //3.将兑换码保存db exchange_code  批量保存
        this.saveBatch(list);

        // 写入Redis缓存  member coupenId score 兑换码的最大序列号
        redisTemplate.opsForZSet().add(COUPON_RANGE_KEY,coupon.getId().toString(),maxSerial);
    }

    @Override
    public boolean updateExchangeCodeMark(long serialNum, boolean flag) {
        String key = PromotionConstants.COUPON_CODE_MAP_KEY;
        //1. 修改兑换码的自增id对应的offset值
        Boolean aBoolean = redisTemplate.opsForValue().setBit(key, serialNum, flag);
        return aBoolean!=null && aBoolean;
    }
}

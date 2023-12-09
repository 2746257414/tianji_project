//package com.tianji.promotion.service.impl;
//
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.tianji.common.exceptions.BadRequestException;
//import com.tianji.common.exceptions.BizIllegalException;
//import com.tianji.common.utils.StringUtils;
//import com.tianji.common.utils.UserContext;
//import com.tianji.promotion.domain.po.Coupon;
//import com.tianji.promotion.domain.po.ExchangeCode;
//import com.tianji.promotion.domain.po.UserCoupon;
//import com.tianji.promotion.enums.CouponStatus;
//import com.tianji.promotion.enums.ExchangeCodeStatus;
//import com.tianji.promotion.mapper.CouponMapper;
//import com.tianji.promotion.mapper.UserCouponMapper;
//import com.tianji.promotion.service.IExchangeCodeService;
//import com.tianji.promotion.service.IUserCouponService;
//import com.tianji.promotion.utils.CodeUtil;
//import com.tianji.promotion.utils.MyLock;
//import com.tianji.promotion.utils.MyLockStrategy;
//import com.tianji.promotion.utils.MyLockType;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.aop.framework.AopContext;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDateTime;
//
///**
// * <p>
// * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
// * </p>
// *
// * @author Kaza
// * @since 2023-12-04
// */
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class UserCouponRedissonCustomServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
//    private final CouponMapper couponMapper;
//    private final IExchangeCodeService exchangeCodeService;
//    private final StringRedisTemplate redisTemplate;
//    private final RedissonClient redissonClient;
//
//    /*
//    领取优惠卷
//     */
//    @Transactional
//    @MyLock(name = "lock:coupon:uid:#{userId}",
//            lockType = MyLockType.RE_ENTRANT_LOCK,
//            lockStategy = MyLockStrategy.FAIL_FAST)
//    public void checkAndCreateUserCoupon(Long id, Coupon coupon) {
//
//        LocalDateTime now = LocalDateTime.now();
//        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
//            throw new BadRequestException("优惠卷已过期或者未发放");
//        }
//        if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
//            throw new BadRequestException("该优惠卷库存不足");
//        }
//        Integer count = this.lambdaQuery()
//                .eq(UserCoupon::getCouponId, coupon.getId())
//                .eq(UserCoupon::getUserId, UserContext.getUser())
//                .count();
//
//        if (count != null && coupon.getUserLimit() <= count) {
//            throw new BadRequestException("该优惠卷领取已达上限！");
//        }
//        //2. 优惠卷已发放数量 + 1
//        couponMapper.incrIssueNum(id);
//        //3. 生成用户卷
//        saveUserCoupon(UserContext.getUser(), coupon);
//
////        throw new RuntimeException("故意抛出");
//    }
//
//    @Override
//    public void receiveCoupon(Long id) {
//        //1. 根据id查询优惠卷， 做相关校验
//        if (id == null) {
//            throw new BadRequestException("非法参数");
//        }
//        Coupon coupon = couponMapper.selectById(id);
//        if (coupon == null) {
//            throw new BadRequestException("优惠卷不存在");
//        }
//        if (coupon.getStatus() != CouponStatus.ISSUING) {
//            throw new BadRequestException("优惠卷状态错误");
//        }
//        IUserCouponService userCouponServiceProxy = (IUserCouponService)AopContext.currentProxy();
//        userCouponServiceProxy.checkAndCreateUserCoupon(id, coupon);
//    }
//    //生成用户优惠卷信息和优惠卷数量 + 1 通用方法
//
//
//    //兑换码兑换优惠卷
//    @Override
//    @Transactional
//    public void exchangeCoupon(String code) {
//        //1. 校验code是否为空
//        if (StringUtils.isBlank(code)) {
//            throw new BadRequestException("非法参数");
//        }
//        //2. 解析兑换码 得到自增id
//        long serialNum = CodeUtil.parseCode(code);
//        log.debug("自增id  = {}", serialNum);
//        //3. 判断兑换码是否已兑换  采用redis的bitmap结构 setbit key offset  1 如果返回true 代表兑换码已兑换
//        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
//        if (result) {
//            throw new BizIllegalException("兑换码已被使用");
//        }
//        try {
//            //4. 判断兑换码是否存在 根据自增id查询 主键查询
//            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
//            if (exchangeCode == null) {
//                throw new BizIllegalException("兑换码不存在");
//            }
//            //5. 判断是否过期
//
//            LocalDateTime now = LocalDateTime.now();
//            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
//            if (now.isAfter(expiredTime)) {
//                throw new BizIllegalException("兑换码已过期");
//            }
//            //6. 判断是否超出限领数量
//            //7. 优惠卷已发放数量 + 1
//            //8. 生成用户卷
//            Long couponId = exchangeCode.getExchangeTargetId();
//            Coupon coupon = couponMapper.selectById(couponId);
//            if (coupon == null) {
//                throw new BizIllegalException("优惠卷不存在");
//            }
//            checkAndCreateUserCoupon(couponId, coupon);
//            //9. 更新兑换码状态
//            exchangeCodeService.lambdaUpdate()
//                    .eq(ExchangeCode::getId, serialNum)
//                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
//                    .set(ExchangeCode::getUserId, UserContext.getUser())
//                    .update();
//        } catch (Exception e) {
//            //将兑换码的状态重置
//            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
//            throw e;
//        }
//    }
//
//    //保存用户卷
//    private void saveUserCoupon(Long user, Coupon coupon) {
//        UserCoupon userCoupon = new UserCoupon();
//        userCoupon.setUserId(user);
//        userCoupon.setCouponId(coupon.getId());
//        LocalDateTime termBeginTime = coupon.getTermBeginTime();
//        LocalDateTime termEndTime = coupon.getTermEndTime();
//        if (termBeginTime == null && termEndTime == null) {
//            termBeginTime = LocalDateTime.now();
//            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
//        }
//        userCoupon.setTermBeginTime(termBeginTime);
//        userCoupon.setTermEndTime(termEndTime);
//        this.save(userCoupon);
//    }
//}

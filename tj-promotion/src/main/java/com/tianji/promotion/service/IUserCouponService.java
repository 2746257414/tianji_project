package com.tianji.promotion.service;

import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-04
 */
public interface IUserCouponService extends IService<UserCoupon> {

    //领取优惠卷
    public void receiveCoupon(Long id);

    //兑换码兑换优惠卷
    void exchangeCoupon(String code);

    //生成用户优惠卷信息和优惠卷数量 + 1 通用方法  给IUserCouponService代理对象调用
    public void checkAndCreateUserCoupon(Long id, Coupon coupon);

    void checkAndCreateUserCouponNew(UserCouponDTO msg);

    /*
        查询可用优惠卷方案
        @param courses 订单中的课程信息
        @return 方案集合
     */
    //该方法是给tj-trade服务远程调用的
    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> courses);
}

package com.tianji.promotion.controller;


import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-12-04
 */
@RestController
@RequestMapping("/user-coupons")
@RequiredArgsConstructor
@Api(tags = "用户优惠卷相关接口")
public class UserCouponController {
    private final IUserCouponService userCouponService;
    @PostMapping("/{id}/receive")
    @ApiOperation("领取优惠卷")
    public void receiveCoupon(@PathVariable Long id) {
        userCouponService.receiveCoupon(id);

    }

    @PostMapping("/{code}/exchange")
    @ApiOperation("兑换码兑换优惠卷")
    public void exchangeCoupon(@PathVariable String code ) {
        userCouponService.exchangeCoupon(code);
    }

    /*
        查询可用优惠卷方案
        @param courses 订单中的课程信息
        @return 方案集合
     */
    //该方法是给tj-trade服务远程调用的
    @PostMapping("/available")
    @ApiOperation("查询可用优惠卷方案")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> courses) {
        return userCouponService.findDiscountSolution(courses);
    }
}

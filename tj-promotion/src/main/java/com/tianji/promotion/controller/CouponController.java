package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.OutputBuffer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-12-03
 */
@RestController
@RequestMapping("/coupons")
@Api(tags = "优惠卷相关接口")
@RequiredArgsConstructor
public class CouponController {

    private final ICouponService couponService;
    @PostMapping
    @ApiOperation("新增优惠卷 - 管理端")
    public void saveCoupon(@RequestBody @Validated CouponFormDTO dto){
        couponService.saveCoupon(dto);
    }

    @GetMapping("/page")
    @ApiOperation("分页查询优惠卷信息 - 管理端")
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        return couponService.queryCouponPage(query);
    }

    @ApiOperation("发放优惠卷 - 管理端")
    @PutMapping("/{id}/issue")
    public void issueCoupon(@PathVariable Long id,
                            @RequestBody @Validated CouponIssueFormDTO dto) {
        couponService.issueCoupon(id, dto);
    }

    @GetMapping("/list")
    @ApiOperation("查询发放中的优惠卷列表 - 用户端")
    public List<CouponVO> queryIssuingCoupons() {
        return couponService.queryIssuingCoupons();
    }
}

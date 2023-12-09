package com.tianji.promotion.service.impl;

import cn.hutool.core.img.gif.NeuQuant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-03
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {
    private final ICouponScopeService couponScopeService;       //优惠卷的选定范围业务类
    private final IExchangeCodeService exchangeCodeService;     //生成兑换码业务类
    private final IUserCouponService userCouponService;     //用户优惠卷业务
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        //1. dto转 po 保存优惠卷  coupon表
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.save(coupon);
        //2. 判断是否限定了范围 dot.specific 如果为false 直接return
        if (!dto.getSpecific()) {
            return; // 没有限定优惠卷的使用范围
        }
        //3. 如果dto.specific 为true 需要校验 dto.scopes
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("分类的id不能为空");
        }
        //4. 保存优惠卷的限定范围  coupon_scope
        /* 常规写法
        List<CouponScope> csList = new ArrayList<CouponScope>();
        for (Long scope : scopes) {
            CouponScope couponScope = new CouponScope();
            couponScope.setCouponId(dto.getId());
            couponScope.setType(1);
            couponScope.setBizId(scope);
            csList.add(couponScope);
        }
        */
        List<CouponScope> csList = scopes.stream().map(aLong -> new CouponScope().setCouponId(coupon.getId()).setBizId(aLong).setType(1)).collect(Collectors.toList());

        couponScopeService.saveBatch(csList);


    }

    @Override
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        //1.分页条件查询优惠卷表 Coupon
        Page<Coupon> page = this.lambdaQuery()
                .eq(query.getType() != null, Coupon::getDiscountType, query.getType())
                .eq(query.getStatus() != null, Coupon::getStatus, query.getStatus())
                .like(StringUtils.isNotBlank(query.getName()), Coupon::getName, query.getName())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        List<CouponPageVO> couponPageVOS = BeanUtils.copyList(records, CouponPageVO.class);
        return PageDTO.of(page, couponPageVOS);
    }

    @Override
    public void issueCoupon(Long id, CouponIssueFormDTO dto) {
        log.debug("发放优惠卷 线程名：{}", Thread.currentThread().getName());
        //1. 校验
        if (id == null || !id.equals(dto.getId())) {
            throw new BadRequestException("非法参数");
        }
        //2. 校验优惠卷id是否存在
        Coupon coupon = this.getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠卷不存在！");
        }
        //3. 校验优惠卷状态 只有待发放和暂停状态才能发放
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BizIllegalException("只有待发放和暂停中的优惠卷才能发放");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isBeginIssue = dto.getIssueBeginTime() == null || !dto.getIssueBeginTime().isAfter(now); //该变量代表优惠卷是否立刻发放
        //4. 修改优惠卷的 领取开始和结束日期 使用有效期开始和结束日的 天数的状态

        //方式1
        Coupon tmp = BeanUtils.copyBean(dto, Coupon.class);
        if (isBeginIssue) {
            tmp.setStatus(CouponStatus.ISSUING);
            tmp.setIssueBeginTime(now);
        } else {
            tmp.setStatus(CouponStatus.UN_ISSUE);
        }

        this.updateById(tmp);

        //5. 如果优惠卷是立刻发放， 将优惠卷信息（优惠卷Id、 领卷开始时间结束时间、发行总数量、限量数量  采用HASH存入redis
        if(isBeginIssue) {
            String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;  //prs:coupon:优惠卷id

//            redisTemplate.opsForHash().put(key,"issueBeginTime", String.valueOf(DateUtils.toEpochMilli(now)));
//            redisTemplate.opsForHash().put(key,"issueEndTime", String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
//            redisTemplate.opsForHash().put(key,"totalNum", String.valueOf(coupon.getTotalNum()));
//            redisTemplate.opsForHash().put(key,"userLimit", String.valueOf(coupon.getUserLimit()));
            Map<String,String> map = new HashMap<>();
            map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(now)));
            map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
            map.put("totalNum", String.valueOf(coupon.getTotalNum()));
            map.put("userLimit", String.valueOf(coupon.getUserLimit()));
            redisTemplate.opsForHash().putAll(key,map);
        }

        //5. 如果优惠卷的 领取方式为指定发放 且优惠卷之前的状态是待发放 需要生成兑换码
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            //兑换码兑换的截止时间， 就是优惠卷领取截止时间， 该时间由前端传的封装到tmp中了
            coupon.setIssueEndTime(tmp.getIssueEndTime());        //设置兑换码过期时间 就是优惠卷领取的截止时间
            //比如 6.13-6.15是领取优惠卷时间 6.18-6.30是优惠卷的使用时间  兑换码过期时间应设置为6.15
            exchangeCodeService.asyncGenerateExchangeCode(coupon);      //异步生成兑换码
        }


    }

    //查询发放中优惠卷列表
    @Override
    public List<CouponVO> queryIssuingCoupons() {
        //1. 查询db  coupon 条件 发放中 手动领取
        List<Coupon> list = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(list)) {
            return Collections.emptyList();
        }
        //2. 查询用户卷表user_coupon  条件 user_id  status = issuing
        //当前用户， 针对正在发放中的优惠卷领取记录
        Set<Long> issuingCouponsIds = list.stream().map(Coupon::getId).collect(Collectors.toSet());
        List<UserCoupon> userCoupons = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, issuingCouponsIds)
                .list();
        //2.1 统计当前用户 针对每一个卷的已领数量
        Map<Long, Long> issueMap = userCoupons.stream().collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        //2.2 统计当前用户 针对每一个卷 的已领且未使用的数量
        Map<Long, Long> unusedMap = userCoupons.stream().filter(c -> c.getStatus()==UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //2. po封装vo返回
        List<CouponVO> voList = new ArrayList<>();
        for (Coupon coupon : list) {
            CouponVO couponVO = BeanUtils.copyBean(coupon, CouponVO.class);
            //优惠卷还有剩余(issue_num  <  total_num)  且 （统计用户卷表user——coupon  取出当前用户已领数量 《 user_limit ）
            boolean available = coupon.getIssueNum() < coupon.getTotalNum() && issueMap.getOrDefault(coupon.getId(),0L) < coupon.getUserLimit();
            couponVO.setAvailable(available);// 是否可以领取
            // 统计用户卷表 取出当前用户已领且未使用的卷数量
            boolean received = unusedMap.getOrDefault(coupon.getId(),0L) > 0;
            couponVO.setReceived(received); //是否可以使用
            voList.add(couponVO);
        }

        return voList;
    }
}

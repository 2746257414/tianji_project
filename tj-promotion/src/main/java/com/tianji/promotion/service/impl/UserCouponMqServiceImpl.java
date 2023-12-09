package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.crypto.digest.otp.TOTP;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.discount.Discount;
import com.tianji.promotion.discount.DiscountStrategy;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-04
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserCouponMqServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    private final ICouponScopeService couponScopeService;
    private final Executor calculateSolutionExecutor;

    /*
    领取优惠卷
     */
    @Transactional
    @MyLock(name = "lock:coupon:uid:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStategy = MyLockStrategy.FAIL_FAST)
    public void checkAndCreateUserCoupon(Long id, Coupon coupon) {

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("优惠卷已过期或者未发放");
        }
        if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
            throw new BadRequestException("该优惠卷库存不足");
        }
        Integer count = this.lambdaQuery().eq(UserCoupon::getCouponId, coupon.getId()).eq(UserCoupon::getUserId, UserContext.getUser()).count();

        if (count != null && coupon.getUserLimit() <= count) {
            throw new BadRequestException("该优惠卷领取已达上限！");
        }
        //2. 优惠卷已发放数量 + 1
        int num = couponMapper.incrIssueNum(id);
        //3. 生成用户卷
        saveUserCoupon(UserContext.getUser(), coupon);

//        throw new RuntimeException("故意抛出");
    }

    @Override
    @Transactional
    public void checkAndCreateUserCouponNew(UserCouponDTO msg) {
        Long couponId = msg.getCouponId();
        //从db中查询优惠卷信息
        Coupon coupon = couponMapper.selectById(couponId);
        if(coupon == null) {
            return;
        }
        //2. 优惠卷已发放数量 + 1
        int num = couponMapper.incrIssueNum(couponId);
        if(num == 0) {
            return;
        }
        //3. 生成用户卷
        saveUserCoupon(msg.getUserId(), coupon);
//        throw new RuntimeException("故意抛出");
    }


    @Override
    //分布式锁 ， 对优惠卷加锁
    @MyLock(name = "lock:coupon:uid:#{id}")
    public void receiveCoupon(Long id) {
        //1. 根据id查询优惠卷， 做相关校验
        if (id == null) {
            throw new BadRequestException("非法参数");
        }
//        Coupon coupon = couponMapper.selectById(id);
        //从redis中获取优惠卷信息
        Coupon coupon = queryCouponByCache(id);
        if (coupon == null) {
            throw new BadRequestException("优惠卷不存在");
        }
//        if (coupon.getStatus() != CouponStatus.ISSUING) {
//            throw new BadRequestException("优惠卷状态错误");
//        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("优惠卷已过期或者未发放");
        }
        if (coupon.getTotalNum() <= 0) {
            throw new BadRequestException("该优惠卷库存不足");
        }


        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + id;      //prs:user:coupon:优惠卷id
        Long userGetNum = redisTemplate.opsForHash().increment(key, UserContext.getUser().toString(), 1);
        if(userGetNum > coupon.getUserLimit()) {
            redisTemplate.opsForHash().increment(key, UserContext.getUser().toString(), -1);
            throw new BizIllegalException("超出限领数量");
        }
        //increment领取后的已领数量
        //修改优惠卷库存 -1
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        redisTemplate.opsForHash().increment(couponKey, "totalNum", -1);


        UserCouponDTO msg = new UserCouponDTO();
        msg.setUserId(UserContext.getUser());
        msg.setCouponId(id);

        // 发送消息 到mq  消息内容为 userId couponid
        mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVE, msg);
//        IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        userCouponServiceProxy.checkAndCreateUserCoupon(id, coupon);
    }

    private Coupon queryCouponByCache(Long id) {
        //1. 拼接key
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;

        //2. 从redis获取数据
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        return BeanUtils.mapToBean(entries, Coupon.class, false, CopyOptions.create());
    }
    //生成用户优惠卷信息和优惠卷数量 + 1 通用方法


    //兑换码兑换优惠卷
    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        //1. 校验code是否为空
        if (StringUtils.isBlank(code)) {
            throw new BadRequestException("非法参数");
        }
        //2. 解析兑换码 得到自增id
        long serialNum = CodeUtil.parseCode(code);
        log.debug("自增id  = {}", serialNum);
        //3. 判断兑换码是否已兑换  采用redis的bitmap结构 setbit key offset  1 如果返回true 代表兑换码已兑换
        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
        if (result) {
            throw new BizIllegalException("兑换码已被使用");
        }
        try {
            //4. 判断兑换码是否存在 根据自增id查询 主键查询
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在");
            }
            //5. 判断是否过期

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
            if (now.isAfter(expiredTime)) {
                throw new BizIllegalException("兑换码已过期");
            }
            //6. 判断是否超出限领数量
            //7. 优惠卷已发放数量 + 1
            //8. 生成用户卷
            Long couponId = exchangeCode.getExchangeTargetId();
            Coupon coupon = couponMapper.selectById(couponId);
            if (coupon == null) {
                throw new BizIllegalException("优惠卷不存在");
            }
            checkAndCreateUserCoupon(couponId, coupon);
            //9. 更新兑换码状态
            exchangeCodeService.lambdaUpdate().eq(ExchangeCode::getId, serialNum).set(ExchangeCode::getStatus, ExchangeCodeStatus.USED).set(ExchangeCode::getUserId, UserContext.getUser()).update();
        } catch (Exception e) {
            //将兑换码的状态重置
            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
            throw e;
        }
    }

    //保存用户卷
    private void saveUserCoupon(Long user, Coupon coupon) {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setUserId(user);
        userCoupon.setCouponId(coupon.getId());
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null && termEndTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);
        this.save(userCoupon);
    }



    /*
            查询可用优惠卷方案
            @param courses 订单中的课程信息
            @return 方案集合
         */
    //该方法是给tj-trade服务远程调用的
    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> courses) {
        //1. 查询当前用户可用的优惠卷coupon 和 user_coupon 条件：userId / status = 1/ 优惠卷的规则  优惠卷id  用户卷id
        List<Coupon> coupons = getBaseMapper().queryMyCoupon(UserContext.getUser());
        if(CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        log.debug("用户的优惠卷共有 {} 张", coupons.size());
        for (Coupon coupon : coupons) {
            log.debug("优惠卷：{}, {} " ,
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon),
                    coupon);
        }
        //2. 初步筛选
        //2.1 计算订单的总金额 对courses的price累加
        int totalAmount = courses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        log.debug("订单的总金额 {} ", totalAmount);
        //2.2 校验哪些优惠卷可用
        List<Coupon> availableCoupons = coupons.stream()
                .filter(coupon -> DiscountStrategy.getDiscount(coupon.getDiscountType()).canUse(totalAmount, coupon))
                .collect(Collectors.toList());
        if(CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }
        log.debug("经过初筛之后还剩 {} 张", availableCoupons.size());
        //3. 细筛 （需要考虑优惠卷的限定范围 排列组合
        Map<Coupon, List<OrderCourseDTO>> avaMap = findAvailableCoupons(availableCoupons,courses);
        if(avaMap.isEmpty()) {
            return CollUtils.emptyList();
        }
        Set<Map.Entry<Coupon, List<OrderCourseDTO>>> entries = avaMap.entrySet();
        for (Map.Entry<Coupon, List<OrderCourseDTO>> entry : entries) {
            log.debug("细筛之后优惠卷 {} ，{}",
                        DiscountStrategy.getDiscount(entry.getKey().getDiscountType()).getRule(entry.getKey()),
                        entry.getKey());
            List<OrderCourseDTO> value = entry.getValue();
            for (OrderCourseDTO orderCourseDTO : value) {
                log.debug("可用课程 {}", orderCourseDTO);
            }

        }

        availableCoupons = new ArrayList<>(avaMap.keySet());        //才是真正可用的优惠卷集合
        log.debug("经过细筛之后 优惠卷个数： {}", availableCoupons.size());
        for (Coupon coupon : availableCoupons) {
            log.debug("细筛之后优惠卷 {} ，{}",
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon),
                    coupon);
        }

        //排列组合
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        for (Coupon availableCoupon : availableCoupons) {
            solutions.add(List.of(availableCoupon));    //把单卷添加到方案中
        }
        for (List<Coupon> solution : solutions) {
            List<Long> collect = solution.stream().map(Coupon::getId).collect(Collectors.toList());
            System.out.println(collect);
        }
        //4. 计算每一种组合的优惠明细
//        List<CouponDiscountDTO> dtos = new ArrayList<>();
//        for (List<Coupon> solution : solutions) {
//            CouponDiscountDTO dto = calculateSolutionDiscount(avaMap, courses,solution);
//            log.debug("方案最终优惠{}  方案中优惠卷使用了 {} 规则{}", dto.getDiscountAmount(), dto.getIds(),dto.getRules());
//            dtos.add(dto);
//        }
        //5. 使用多线程改造第四步 并行计算每一种组合的优惠明细
        log.debug("开始计算 每一种组合的优惠明细");
//        List<CouponDiscountDTO> dtos = new ArrayList<>();       //线程不安全的
        List<CouponDiscountDTO> dtos = Collections.synchronizedList(new ArrayList<>(solutions.size()));       //线程安全的集合
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture.supplyAsync(new Supplier<CouponDiscountDTO>() {
                @Override
                public CouponDiscountDTO get() {
                    log.debug("线程{} 开始计算方案 {}",
                            Thread.currentThread().getName(),
                            solution.stream().map(Coupon::getId).collect(Collectors.toSet()));
                    CouponDiscountDTO dto = calculateSolutionDiscount(avaMap, courses,solution);
                    return dto;
                }
            },calculateSolutionExecutor).thenAccept(new Consumer<CouponDiscountDTO>() {
                @Override
                public void accept(CouponDiscountDTO dto) {
                    log.debug("方案最终优惠{}  方案中优惠卷使用了 {} 规则{}", dto.getDiscountAmount(), dto.getIds(),dto.getRules());
                    dtos.add(dto);
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(2, TimeUnit.SECONDS);  //主线程会最多阻塞两秒
        } catch (InterruptedException e) {
            log.error(" 多线程计算组合优惠明细 {} 报错了", e);
        }
        //6. 筛选最优解


        return findBestSolution(dtos);
    }

    /**
     * 求最优解
     * - 用券相同时，优惠金额最高的方案
     * - 优惠金额相同时，用券最少的方案
     * @param dtos
     * @return
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> solutions) {
        // 1. 创建两个map 分别记录用卷相同， 金额最高， 金额相同，用卷最少
        Map<String,CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer,CouponDiscountDTO> lessCouponMap = new HashMap<>();

        //2. 循环方案 向map中记录  用卷相同， 金额最高， 金额相同，用卷最少
        for (CouponDiscountDTO solution : solutions) {
            log.debug("当前方案时：{}", solution);
            //2.1 对优惠卷id 升序， 转字符串 然后以逗号拼接
            String ids = solution.getIds().stream()
                    .sorted(Comparator.comparing(Long::longValue))
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            //2.2 从moreDiscountMap中取 旧的记录 判断  如果当前方案的优惠金额 小于 旧的方案金额  当前方案忽略 处理下一个方案
            CouponDiscountDTO old = moreDiscountMap.get(ids);
            if(old != null && old.getDiscountAmount() >= solution.getDiscountAmount()) {
                continue;
            }
            //2.3 从lessCouponMap中取 旧的记录 判断如果当前方案 用卷数量大于 旧的方案用卷数量 前方案忽略 处理下一个方案
            old = lessCouponMap.get(solution.getDiscountAmount());
            List<Long> ids1 = solution.getIds();
            int newSize = ids1.size();
            if(old != null && newSize > 1 &&old.getIds().size() <= newSize) {
                continue;
            }
            //2.4 添加更优方案到map中
            moreDiscountMap.put(ids, solution); //说明当前方案 更优0
            lessCouponMap.put(solution.getDiscountAmount(), solution);  //说明当前方案 更优
        }
        //3. 求两个map的交集
        Collection<CouponDiscountDTO> bestSolution = CollUtils.intersection(moreDiscountMap.values(), lessCouponMap.values());
        //4. 对最终的方案结果  按优惠金额 倒序
        List<CouponDiscountDTO> latestBestSolution = bestSolution.stream().sorted(Comparator.comparing(CouponDiscountDTO::getDiscountAmount).reversed()).collect(Collectors.toList());

        return latestBestSolution;
    }

    /**
     * 计算每一个方案的 优惠信息
     * @param avaMap 优惠卷和可用课程的映射集合
     * @param courses 订单中所有的课程
     * @param solution 方案
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> avaMap, List<OrderCourseDTO> courses, List<Coupon> solution) {
        //1.  创建方案结果dto对象
        CouponDiscountDTO dto = new CouponDiscountDTO();
        //2. 初始化商品id和商品折扣明细的映射，初始折扣明细全部设置为0
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, orderCourseDTO -> 0));
        //3. 计算该方案的优惠信息
        //3.1 循环方案中优惠卷
        for (Coupon coupon : solution) {
            //3.2 取出该优惠卷对应的可用课程
            List<OrderCourseDTO> availableCourses = avaMap.get(coupon);

            //3.3 计算可用课程的总金额（商品加个 - 该商品的折扣明细）
            int totalAmount = availableCourses.stream().mapToInt(value -> value.getPrice() - detailMap.get(value.getId())).sum();
            //3.4 判断优惠卷是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if(!discount.canUse(totalAmount, coupon)) {
                continue;
            }
            //3.5 计算该优惠卷使用后的折扣值
            int discountAmount = discount.calculateDiscount(totalAmount,coupon);
            //3.6 更新商品的折扣明细(更新商品id的商品折扣明细
            calculateDetailDiscount(detailMap,availableCourses,totalAmount,discountAmount);
            //3.7 累加每一个优惠卷的优惠金额 赋值给方案结果dto对象
            dto.getIds().add(coupon.getId());
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount()); //不能覆盖， 应该是所有生效的优惠卷累加的结果
        }
        return dto;
    }

    /**
     *  更新商品优惠明细表
     * @param detailMap 商品优惠明细表
     * @param availableCourses 当前优惠卷可用课程
     * @param totalAmount   优惠后的课程的总金额
     * @param discountAmount    当前优惠卷能优惠的金额
     */
    private void calculateDetailDiscount(Map<Long, Integer> detailMap,
                                         List<OrderCourseDTO> availableCourses,
                                         int totalAmount,
                                         int discountAmount) {
        //目的： 本方法就是优惠卷在使用后 计算每个商品的折扣明细
        //规则 ： 前面的商品按逼里计算， 最后一个商品折扣明细 = 总的优惠金额 - 前面商品优惠的总额
        //循环可用商品
        int times = 0; //已处理商品个数
        int remainDiscount = discountAmount;    // 代表剩余的优惠金额
        for (OrderCourseDTO c : availableCourses) {
            times++;
            int discount = 0;
            if(times == availableCourses.size()) {
                //说明是最后一个课程
                discount = remainDiscount;
            } else {
                discount = c.getPrice() * discountAmount / totalAmount; //先乘再除 否则结果为0
            }
            detailMap.put(c.getId(), discount + detailMap.get(c.getId()));

        }
    }


    /**
     * 细筛 查询每一个优惠卷可用课程
     * @param coupons  初筛之后的优惠卷集合
     * @param orderCourses 订单中的课程集合
     * @return
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupons(List<Coupon> coupons,
                                                                   List<OrderCourseDTO> orderCourses) {

        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>();
        //1. 循环遍历初筛后的优惠卷集合
        for (Coupon coupon : coupons) {
            //2. 找出每一个优惠卷的可用课程
            List<OrderCourseDTO> availableCourses = orderCourses;
            //2.1 判断优惠卷是否限定了范围 coupon.specific 为true
            if(coupon.getSpecific()) {
                //2.2 查询限定范围 查询coupon-scope表 条件coupon_id
                List<CouponScope> scopeList = couponScopeService.lambdaQuery().eq(CouponScope::getCouponId, coupon.getId()).list();
                //2.3 得到限定范围的id集合
                List<Long> scopeIds = scopeList.stream().map(CouponScope::getBizId).collect(Collectors.toList());
                //2.4 从orderCourses订单中所有的课程集合 筛选 合适的课程
                availableCourses = orderCourses.stream().filter(c -> scopeIds.contains(c.getCateId())).collect(Collectors.toList());
            }
            if(CollUtils.isEmpty(availableCourses)) {
                continue;//说明当前优惠卷限定了范围，但是没有在订单中的课程没有找到可用的课程，说明该卷不可用
            }
            //3.计算该优惠卷可用课程的总金额
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            //4. 判断该优惠卷是否可用  如果可用添加到map中
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if(discount.canUse(totalAmount,coupon)) {
                map.put(coupon,availableCourses);
            }
        }
        return map;
    }
}

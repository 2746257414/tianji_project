package com.tianji.promotion.handler;

import com.tianji.common.constants.MqConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.tianji.common.constants.MqConstants.Exchange.PROMOTION_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.COUPON_RECEIVE;

/**
 * ClassName: PromotionCouponHandler
 * Package: com.tianji.promotion.handler
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/7 13:58
 * @Version 1.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PromotionCouponHandler {
    private final IUserCouponService userCouponsService;
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "coupon.receive.queue", durable = "true"),
            exchange = @Exchange(name = PROMOTION_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = COUPON_RECEIVE))
    public void onMsg(UserCouponDTO msg) {
        log.debug("收到领卷消息 {}",msg);
        userCouponsService.checkAndCreateUserCouponNew(msg);
    }


}

package com.tianji.learning.mq;

import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ClassName: LikedRecordListener
 * Package: com.tianji.learning.mq
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/11/29 23:27
 * @Version 1.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LikedRecordListener {
    private final IInteractionReplyService replyService;

        //QA问答系统 消费者
    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(value = MqConstants.Exchange.LIKE_RECORD_EXCHANGE, type = ExchangeTypes.TOPIC),
            value = @Queue(value = "qa.liked.times.queue",durable = "true"),
            key = MqConstants.Key.QA_LIKED_TIMES_KEY))
    public void onMsg(List<LikedTimesDTO> list) {
        log.debug("LikedRecordListener 监听到消息 {}", list);
        //消息转po
        List<InteractionReply> replyList = new ArrayList<>();
        for (LikedTimesDTO likedTimesDTO : list) {
            InteractionReply reply = new InteractionReply();
            reply.setLikedTimes(likedTimesDTO.getLikedTimes());
            reply.setId(likedTimesDTO.getBizId());
            replyList.add(reply);
        }
        replyService.updateBatchById(replyList);
        /*
        log.debug("LikedRecordListener 监听到消息 {}", dto);
        InteractionReply reply = replyService.getById(dto.getBizId());
        if(reply == null) {
            return;
        }
        reply.setLikedTimes(dto.getLikedTimes());
        replyService.updateById(reply);
        */
    }
}

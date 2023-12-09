//package com.tianji.remark.service.impl;
//
//import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
//import com.tianji.common.constants.MqConstants;
//import com.tianji.common.utils.BeanUtils;
//import com.tianji.common.utils.CollUtils;
//import com.tianji.common.utils.StringUtils;
//import com.tianji.common.utils.UserContext;
//import com.tianji.remark.controller.LikedRecordController;
//import com.tianji.remark.domain.dto.LikeRecordFormDTO;
//import com.tianji.remark.domain.dto.LikedTimesDTO;
//import com.tianji.remark.domain.po.LikedRecord;
//import com.tianji.remark.mapper.LikedRecordMapper;
//import com.tianji.remark.service.ILikedRecordService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.util.Collections;
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
///**
// * <p>
// * 点赞记录表 服务实现类
// * </p>
// *
// * @author Kaza
// * @since 2023-11-29
// */
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
//    private final RabbitMqHelper rabbitMqHelper;
//    @Override
//    public void addLikeRecord(LikeRecordFormDTO dto) {
//        //1. 获取当前用户
//        Long userId = UserContext.getUser();
//        //2. 判断是否是点赞 true为点赞  false为取消点赞
//        boolean flag = dto.getLiked() ? liked(dto,userId) : unliked(dto,userId);
//
//        if(!flag) { //说明点赞或者取消赞失败
//            return;
//        }
//
//        //3. 统计该业务id的总点赞数
//        Integer totalLikesNum = this.lambdaQuery()
//                .eq(LikedRecord::getBizId,dto.getBizId())
//                .count();
//
//        //4. 发送消息到mq
//        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
//        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), totalLikesNum);
//        log.debug("发送的消息 {}", msg);
//        rabbitMqHelper.send(
//                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
//                routingKey,
//                msg);
//    }
//
//    @Override
//    public Set<Long> getLikesStatusByBizIds(List<Long> bizIds) {
//        //1. 获取用户id
//        Long userId = UserContext.getUser();
//        //2. 查询点赞记录表 in bizIds
//        List<LikedRecord> list = this.lambdaQuery().eq(LikedRecord::getUserId, userId).in(LikedRecord::getBizId, bizIds).list();
//        if(CollUtils.isEmpty(list)) {
//            return Collections.emptySet();
//        }
//        //3. 将查询到的bizIds转集合返回
//        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
//    }
//
//    /**
//     * 取消赞
//     */
//    private boolean unliked(LikeRecordFormDTO dto,Long userId) {
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if(record==null) {
//            //说明没有点过赞 取消失败
//            return false;
//        }
//        //删除点赞记录
//        return this.removeById(record.getId());
//    }
//
//    /**
//     * 点赞
//     */
//    private boolean liked(LikeRecordFormDTO dto,Long userId) {
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if(record!=null) {
//            //说明之前点过赞
//            return false;
//        }
//        //保存点赞记录到表中
//        LikedRecord likedRecord = new LikedRecord();
//        likedRecord.setBizId(dto.getBizId());
//        likedRecord.setBizType(dto.getBizType());
//        likedRecord.setUserId(userId);
//        return this.save(likedRecord);
//    }
//}

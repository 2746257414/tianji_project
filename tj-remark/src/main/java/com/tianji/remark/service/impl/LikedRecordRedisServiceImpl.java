package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.BooleanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.dto.LikedTimesDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-11-29
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
    private final RabbitMqHelper rabbitMqHelper;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        //1. 获取当前用户
        Long userId = UserContext.getUser();
        //2. 判断是否是点赞 true为点赞  false为取消点赞
        boolean flag = dto.getLiked() ? liked(dto, userId) : unliked(dto, userId);
        if (!flag) { //说明点赞或者取消赞失败
            return;
        }

        //基于redis统计业务id 的总点赞量
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long totalLikesNum = redisTemplate.opsForSet().size(key);
        if (totalLikesNum == null) {
            return;
        }

        //4. 采用zset结构 缓存点赞的总数
        String bizTypeTotalLikeKey = RedisConstants.LIKE_COUNT_KEY_PREFIX + dto.getBizType();
        redisTemplate.opsForZSet().add(bizTypeTotalLikeKey, dto.getBizId().toString(), totalLikesNum);
/*
//1. 获取当前用户
        Long userId = UserContext.getUser();
        //2. 判断是否是点赞 true为点赞  false为取消点赞
        boolean flag = dto.getLiked() ? liked(dto,userId) : unliked(dto,userId);
        if(!flag) { //说明点赞或者取消赞失败
            return;
        }
        //3. 统计该业务id的总点赞数
        Integer totalLikesNum = this.lambdaQuery()
                .eq(LikedRecord::getBizId,dto.getBizId())
                .count();



        4. 发送消息到mq
        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), totalLikesNum);
        log.debug("发送的消息 {}", msg);
        rabbitMqHelper.send(
                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                routingKey,
                msg);
*/
    }

    //批量查询点赞状态统计
    @Override
    public Set<Long> getLikesStatusByBizIds(List<Long> bizIds) {
        /*//1. 获取用户
        Long userId = UserContext.getUser();
        if(CollUtils.isEmpty(bizIds)) {
            return CollUtils.emptySet();
        }

        Set<Long> likedBizIds = new HashSet<>();
        //2. 循环bizIds
        for (Long bizId : bizIds) {
            //3. 判断该业务id 的点赞用户集合中是否包含当前用户
            Boolean member = redisTemplate.opsForSet().isMember(RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId, userId.toString());

            if(BooleanUtils.isTrue(member)) {
                //4. 如果有当前用户id则存入新集合返回
                likedBizIds.add(bizId);
            }
        }
        return likedBizIds;*/
        /*//1. 获取用户id
        Long userId = UserContext.getUser();
        //2. 查询点赞记录表 in bizIds
        List<LikedRecord> list = this.lambdaQuery().eq(LikedRecord::getUserId, userId).in(LikedRecord::getBizId, bizIds).list();
        if (CollUtils.isEmpty(list)) {
            return Collections.emptySet();
        }
        //3. 将查询到的bizIds转集合返回
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());*/
            // 1.获取登录用户id
            Long userId = UserContext.getUser();
            // 2.查询点赞状态
            List<Object> objects = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection src = (StringRedisConnection) connection;
                for (Long bizId : bizIds) {
                    String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId;
                    src.sIsMember(key, userId.toString());
                }
                return null;
            });
            // 3.返回结果
            return IntStream.range(0, objects.size()) // 创建从0到集合size的流
                    .filter(i -> (boolean) objects.get(i)) // 遍历每个元素，保留结果为true的角标i
                    .mapToObj(bizIds::get)// 用角标i取bizIds中的对应数据，就是点赞过的id
                    .collect(Collectors.toSet());// 收集

    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        //1. 拼接key:  likes:times:type:QA  likes:times:type:NOTE
        String bizTypeTotalLikeKey = RedisConstants.LIKE_COUNT_KEY_PREFIX + bizType;

        List<LikedTimesDTO> list = new ArrayList<>();
        //2. 从redis的zset结构中取 按分数排序（从小到大）取maxBizSize条的业务点赞信息， popmin
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().popMin(bizTypeTotalLikeKey, maxBizSize);
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String bizId = typedTuple.getValue();   //业务id
            Double likedTimes = typedTuple.getScore();  // 点赞次数
            if(StringUtils.isBlank(bizId) || likedTimes == null) {
                continue;
            }
            //3. 封装LikedTimesDTO 数据
            LikedTimesDTO msg = LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue());
            list.add(msg);
        }

        //4. 发送消息到mq
        //QA.times.changed

        if(!CollUtils.isEmpty(list)){
            log.debug("批量发送的消息 {}", list);
            String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE,bizType);
            rabbitMqHelper.send(
                    MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                    routingKey,
                    list);
        }

    }

    /**
     * 取消赞
     */
    private boolean unliked(LikeRecordFormDTO dto, Long userId) {
        //基于redis
        //拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        //从set结构中 删除当前userid
        Long result = redisTemplate.opsForSet().remove(key, userId.toString());
        return result != null && result > 0;
        /*LikedRecord record = this.lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .one();
        if(record==null) {
            //说明没有点过赞 取消失败
            return false;
        }
        //删除点赞记录
        return this.removeById(record.getId());*/
    }

    /**
     * 点赞
     */
    private boolean liked(LikeRecordFormDTO dto, Long userId) {
        //基于redis
        //拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        //注入redisTemplate 往 redis的set结构添加 点赞记录
        Long result = redisTemplate.opsForSet().add(key, userId.toString());
        return result != null && result > 0;
        /*LikedRecord record = this.lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .one();
        if(record!=null) {
            //说明之前点过赞
            return false;
        }
        //保存点赞记录到表中
        LikedRecord likedRecord = new LikedRecord();
        likedRecord.setBizId(dto.getBizId());
        likedRecord.setBizType(dto.getBizType());
        likedRecord.setUserId(userId);
        return this.save(likedRecord);*/
    }
}

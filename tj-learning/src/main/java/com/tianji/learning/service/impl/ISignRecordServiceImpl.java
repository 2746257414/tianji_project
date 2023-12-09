package com.tianji.learning.service.impl;

import com.mysql.cj.NoSubInterceptorWrapper;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BooleanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ClassName: ISignRecordServiceImpl
 * Package: com.tianji.learning.service.impl
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/1 3:27
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ISignRecordServiceImpl implements ISignRecordService {
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;
    @Override
    public SignResultVO addSignRecords() {
        //1. 获取用户id
        Long userId = UserContext.getUser();
        //2. 拼接key
        LocalDate now = LocalDate.now();    //当前时间的年月
        String yearAndMon = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX +userId.toString() + yearAndMon ;

        //3. 利用bitfiled 命令 将签到记录保存到redis的bitmap结构中  需要校验是否已签到
        //now.getDayOfMonth() - 1, 本月天数-1 ： 偏移量
        Boolean setBit = redisTemplate.opsForValue().setBit(key, now.getDayOfMonth() - 1, true);
        if(BooleanUtils.isTrue(setBit)) {
            //说明当天已经签过到了
            throw new BizIllegalException("不能重复签到");
        }

        // bitfiled key get u[dayOfMonth] 0  //获取本月到今天为止的所有签到数据
        //4. 计算连续签到的天数
        int days = countSignDays(key, now.getDayOfMonth());
        //5. 计算连续签到 奖励积分
        int rewardPoints = 0; //代表连续签到 奖励积分
        switch (days) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }

        //5. 保存积分
        rabbitMqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));
        //7. 封装vo返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(days);
        vo.setRewardPoints(rewardPoints);
        return vo;
    }

    @Override
    public Byte[] queryMySignRecord() {
        //1. 获取当前登录用户
        Long userId = UserContext.getUser();
        //2. 查询redis 获取本月签到信息
        LocalDate now = LocalDate.now();
        String yearAndMon = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX +userId.toString()+ yearAndMon;
        //获取本月到今天为止的所有签到数据 得到10进制数
        int dayOfMonth = now.getDayOfMonth();

        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(CollUtils.isEmpty(bitField)) {
            return new Byte[0];
        }
        //获取本月到今天为止的所有签到数据 得到10进制数
        Long num = bitField.get(0);
        int offset = dayOfMonth - 1;
        //2. num转2进制后， 从后往前统计有多少个1  与运算 右移
        Byte[] result = new Byte[dayOfMonth];

        while(num != 0) {
            if((num&1) == 1) {
                result[offset] = 1;
            } else {
                result[offset] = 0;
            }
            num = num >>> 1;  //右移一位
            offset--;
        }

        return result;
    }


    private int countSignDays(String key, int dayOfMonth) {
        //1. bitfiled key get u[dayOfMonth] 0  //获取本月到今天为止的所有签到数据 得到10进制数
        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if(CollUtils.isEmpty(bitField)) {
            return 0;
        }
        //获取本月到今天为止的所有签到数据 得到10进制数
        Long num = bitField.get(0);
        log.debug("num  {}", num);
        //2. num转2进制后， 从后往前统计有多少个1  与运算 右移
        int counter = 0; //计数器
        while((num & 1) == 1) {
            counter++;
            num = num >>> 1;  //右移一位
        }
        return counter;
    }

}

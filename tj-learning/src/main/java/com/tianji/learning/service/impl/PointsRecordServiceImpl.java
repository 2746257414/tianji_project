package com.tianji.learning.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.xml.stream.events.ProcessingInstruction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tianji.common.utils.DateUtils.POINTS_BOARD_SUFFIX_FORMATTER;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-01
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;
    //增加积分
    @Override
    public void addPointRecord(SignInMessage msg, PointsRecordType type) {
        //0. 校验
        if(msg.getUserId() == null || msg.getPoints() == null) {
            return;
        }
        int realPoint = msg.getPoints();    //实际可以增加的积分
        //1. 判断该积分类型 是否有积分获取上限 type.maxPOints 是否大于0
        int maxPoints = type.getMaxPoints();;
        if(maxPoints > 0) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime dayStartTime = DateUtils.getDayStartTime(now);
            LocalDateTime dayEndTime = DateUtils.getDayEndTime(now);
            //2. 如果有上限 查询该用户 该积分类型 今日已得积分 points_record 条件： userId type 今天 sum（points）
            //MyBatisPlus
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.select("sum(points) as totalPoints");
            wrapper.eq("user_id", msg.getUserId());
            wrapper.eq("type", type);
            wrapper.between("create_time",dayStartTime ,dayEndTime);
            Map<String, Object> map = this.getMap(wrapper);
            int currentPoints = 0; //当前用户 该积分类型 已得积分
            if(CollUtils.isNotEmpty(map)) {
                BigDecimal totalPoints = (BigDecimal) map.get("totalPoints");
                currentPoints = totalPoints.intValue();
            }

            //3 判断已得积分是否超过上限
            if(currentPoints >= maxPoints) {
                return ; //说明已达上限 无法继续获取积分
            }
            //计算本次实际应该增加多少分
            if(currentPoints + realPoint > maxPoints) {
                realPoint = maxPoints - currentPoints;
            }
        }

        //4. 保存积分
        PointsRecord record = new PointsRecord();
        record.setUserId(msg.getUserId());
        record.setType(type);
        record.setPoints(realPoint);
        this.save(record);

        //5. 累加并保存总积分值到redis 采用zset  当前赛季的排行榜
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX+ LocalDate.now().format(POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key,msg.getUserId().toString(),realPoint);
    }

    @Override
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        //1. 获取用户id
        Long userId = UserContext.getUser();
        //2. 查询points_record表 条件： user_id  今日  按照type分组 type，sum（points）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = DateUtils.getDayStartTime(now);
        LocalDateTime endTime = DateUtils.getDayEndTime(now);
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.select("type", "sum(points) as points");
        wrapper.eq("user_id",userId);
        wrapper.between("create_time", beginTime,endTime);
        wrapper.groupBy("type");
        wrapper.orderByDesc("points");
        List<PointsRecord> list = this.list(wrapper);
        if(CollUtils.isEmpty(list)) {
            throw new BizIllegalException("当前用户没有积分");
        }

        //3. 封装
        List<PointsStatisticsVO> voList = new ArrayList<>();

        for (PointsRecord r : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setMaxPoints(r.getType().getMaxPoints());          //积分类型单日获取最大值
            vo.setType(r.getType().getDesc());       //积分类型的中文
            vo.setPoints(r.getPoints());             //今日该类型获取的总积分
            voList.add(vo);

        }
        return voList;
    }

    @Override
    public void refreshMyPoints(Long userId) {
        if(!userId.equals(UserContext.getUser())) {
            throw new BizIllegalException("只允许刷新自己的积分榜单");
        }
        LocalDate now = LocalDate.now();
        LocalDateTime beginTime = DateUtils.getMonthBeginTime(now);
        LocalDateTime endTime = DateUtils.getMonthEndTime(now);
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.select("sum(points) as points");
        wrapper.eq("user_id", userId);
        wrapper.groupBy("user_id");
        wrapper.between("create_time",beginTime,endTime);
        List<PointsRecord> list = this.list(wrapper);
        if(CollUtils.isEmpty(list)) {
            throw new BizIllegalException("当前用户没有积分");
        }
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX+ LocalDate.now().format(POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().add(key,userId.toString(),list.get(0).getPoints());
    }
}

package com.tianji.learning.task;

import cn.hutool.log.Log;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.service.IPointsRecordService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.tianji.common.utils.DateUtils.POINTS_BOARD_SUFFIX_FORMATTER;
import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;
import static com.tianji.learning.constants.RedisConstants.POINTS_BOARD_KEY_PREFIX;

/**
 * ClassName: PointsBoardPersistentHandler
 * Package: com.tianji.learning.task
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/2 22:28
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PointsBoardPersistentHandler {
    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;
    /**
     * 创建上赛季（上个月） 榜单表
     */
//    @Scheduled(cron = "0 0 3 1 * ?") // 每月1号，凌晨3点执行
//    @Scheduled(cron = "0/30 * * * * ? ") // 每5分钟执行一次
    @XxlJob("createTableJob")
    public void createPointsBoardTableOfLastSeason() {

        //1. 获取上个月当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);
        //2. 查询赛季获取赛季id
        PointsBoardSeason one = pointsBoardSeasonService
                .lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息： {}", one);
        if(one == null) {
            return;
        }
        //3. 创建上赛季榜d单表  points_board_7
        pointsBoardSeasonService.createPointsBoardLatestTable(one.getId());
    }

    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB() {
        //1. 获取上个月当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);

        //2. 查询赛季表 points_board_season  获取上赛季信息
        PointsBoardSeason one = pointsBoardSeasonService
                .lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息： {}", one);
        if(one == null) {
            return;
        }
        // 3.计算动态表名 并存入threadlocal
        String tableName = POINTS_BOARD_TABLE_PREFIX + one.getId();
        log.debug("动态表名为 {}", tableName);
        TableInfoContext.setInfo(tableName);

        int shardIndex = XxlJobHelper.getShardIndex();  //当前分片的索引 从 0 开始
        int shardTotal = XxlJobHelper.getShardTotal();  //总分片数

        int pageNo = shardIndex + 1;
        int pageSize = 5;
        while(true) {
            //4. 分页获取redis上赛季积分排行榜数据
            List<PointsBoard> pointsBoards = pointsBoardService.queryCurrentBoard(POINTS_BOARD_KEY_PREFIX + time.format(POINTS_BOARD_SUFFIX_FORMATTER), pageNo, pageSize);
            if(CollUtils.isEmpty(pointsBoards)) break;
            pageNo+=shardTotal;
            //5. 持久化到db 响应的赛季表中 批量新增
            for (PointsBoard pointsBoard : pointsBoards) {
                pointsBoard.setId(pointsBoard.getRank().longValue());
                pointsBoard.setRank(null);
            }
            pointsBoardService.saveBatch(pointsBoards);
        }

        //6. 清空threadlocal中数据
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        // 3.删除
        redisTemplate.unlink(key);
    }


}


package com.tianji.remark.task;

import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ClassName: LikedTimesCheckTask
 * Package: com.tianji.remark.task
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/11/30 21:01
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikedTimesCheckTask {
    private static final List<String> BIZ_TYPES = List.of("QA", "NOTE");    //业务类型
    private static final int MAX_BIZ_SIZE = 30; ////任务每次取的biz数量, 防止一次性往mq传输过多数据
    private final ILikedRecordService likedRecordService;
    //每20秒执行一次发送到更新点赞次数到mq的定时任务
//    @Scheduled(cron = "0/20 * * * * ?")     //每间隔20秒执行后一次
    @Scheduled(fixedDelay = 40000)//    效果同上
    public void checkLikedTimes() {
        //获取某业务的点赞总数 发送消息到mq
        for (String bizType : BIZ_TYPES) {
            likedRecordService.readLikedTimesAndSendMessage(bizType, MAX_BIZ_SIZE);
        }
    }

}

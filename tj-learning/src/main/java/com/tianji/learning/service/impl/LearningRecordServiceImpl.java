package com.tianji.learning.service.impl;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.statement.drop.Drop;
import org.checkerframework.checker.units.qual.A;
import org.springframework.stereotype.Service;

import java.awt.image.FilteredImageSource;
import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-11-25
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {
    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    @Override
    public LearningLessonDTO queryLearningRecordByCourseId(Long courseId) {
        //1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        //2. 查询课表信息 获取课表Id 条件：user_id 和 course_id
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if(lesson == null) {
            throw new BizIllegalException("该课程未加入课表！");
        }
        //3. 查询学习记录 条件 lesson_id 和userId
        List<LearningRecord> list = this.lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();

        //4. 封装结果返回
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(list, LearningRecordDTO.class));
        return dto;
    }

    @Override
    public void addLearningRecord(LearningRecordFormDTO dto) {
        //1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        //2. 处理考试记录
        boolean isFinished = false; //代表本小节是否完成
        if(dto.getSectionType().equals(SectionType.VIDEO)) {
            //2.1 提交视频播放记录
            isFinished = handleVideoRecord(userId, dto);
        } else {
            //2.2 判断是否是考试
            isFinished = handleExamRecord(userId, dto);
        }
        //3. 处理课表数据
        handleLessonData(dto, isFinished);
    }

    //处理课表数据
    private void handleLessonData(LearningRecordFormDTO dto, boolean isFinished) {
         //1. 查询课表 learning_lesson 条件 lesson_id 主键
        LearningLesson lesson = lessonService.getById(dto.getLessonId());
        if(lesson == null) {
            throw new BizIllegalException("课表不存在！");
        }
        //2. 判断是否第一次学完 isFinished是不是true
        boolean allFinished = false;    //所有小节是否学完
        if(isFinished) {
            //3. 远程调用课程服务 得到课程信息 小节总数
            CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
            if(courseInfo == null) {
                throw new BizIllegalException("课程不存在");
            }
            Integer sectionNum = courseInfo.getSectionNum();    //该课程的小节总数

            //4. 如果isFInished为true 本小节是第一次学完 判断该用户对该课程下的所有小节是否学完
            Integer learnedSections = lesson.getLearnedSections();
            allFinished = learnedSections + 1 >= sectionNum;
        }
        lessonService.lambdaUpdate()
                .set(lesson.getStatus() == LessonStatus.NOT_BEGIN, LearningLesson::getStatus, LessonStatus.LEARNING)
                .set(allFinished, LearningLesson::getStatus, LessonStatus.FINISHED)
                .set(LearningLesson::getLatestSectionId, dto.getSectionId())
                .set(LearningLesson::getLatestLearnTime, dto.getCommitTime())
//                .set(isFinished, LearningLesson::getLearnedSections, lesson.getLearnedSections() + 1)
                .setSql(isFinished,"learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO dto) {
        //1. 查看学习记录是否存在
        LearningRecord learningRecord = this.lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getLessonId, dto.getLessonId())
                .eq(LearningRecord::getSectionId, dto.getSectionId())
                .one();
        if(learningRecord == null) {
            LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
            record.setUserId(userId);
            boolean result = this.save(record);
            if(!result) {
                throw new DbException("新增学习记录失败！");
            }
            return false;
        }
        // 2. 更新学习记录
        boolean isFinished = false;
        isFinished = !learningRecord.getFinished() && learningRecord.getMoment() * 2 >= dto.getDuration();
        boolean result = this.lambdaUpdate()
                .set(LearningRecord::getMoment, dto.getMoment())
                .set(isFinished, LearningRecord::getFinished, true)
                .set(isFinished, LearningRecord::getFinishTime, dto.getCommitTime())
                .eq(LearningRecord::getId, learningRecord.getId())
                .update();
        if(!result) {
            throw new DbException("学习记录更新失败！");
        }
        return isFinished;

    }

    //处理考试记录
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO dto) {
        //1. 讲dto转换po
        LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(dto.getCommitTime());

        //2. 保存学习记录learning——recode
        boolean result = this.save(record);
        if(! result) {
            throw new DbException("新增考试记录失败");
        }
        return true;
    }

}

package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.swing.event.ListSelectionEvent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-11-24
 */
@Service
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    final CourseClient courseClient;
    final CatalogueClient catalogueClient;

    @Override
    public void addUserLesson(Long userId, List<Long> courseIds) {
        //1. 通过feign远程调用课程服务 得到课程信息
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        //封装po实体类， 填充过期时间
        List<LearningLesson> list = new ArrayList<>();
        for (CourseSimpleInfoDTO cinfo : cinfos) {
            LearningLesson lesson = new LearningLesson();
            lesson.setUserId(userId);
            lesson.setCourseId(cinfo.getId());
            Integer validDuration = cinfo.getValidDuration();   //课程有效期 单位是月
            if (validDuration != null) {
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            list.add(lesson);
        }
        //批量保存
        this.saveBatch(list);
    }

    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        //1. 获取当前登录人
        Long userId = UserContext.getUser();

        //2. 分页查询我的课表数据
        Page<LearningLesson> page = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> lessons = page.getRecords();
        if(CollUtils.isEmpty(lessons)) {
            return PageDTO.empty(page);
        }
        //3. 远程调用课程服务， 给vo中的课程名 封面 章节数赋值
        Set<Long> courseIds = lessons.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> courseInfos = courseClient.getSimpleInfoList(courseIds);
        if(CollUtils.isEmpty(courseInfos)) {
            throw new BizIllegalException("课程不存在！");
        }
        Map<Long, CourseSimpleInfoDTO> map = courseInfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        List<LearningLessonVO> voList = new ArrayList<>();
        //4. 拷贝属性， 讲po数据封装到LessonVO
        for (LearningLesson lessonInfo : lessons) {
            LearningLessonVO vo = BeanUtils.copyBean(lessonInfo, LearningLessonVO.class);
            CourseSimpleInfoDTO courseDto = map.get(lessonInfo.getCourseId());
            if(courseDto !=null) {
                vo.setCourseName(courseDto.getName());
                vo.setCourseCoverUrl(courseDto.getCoverUrl());
                vo.setSections(courseDto.getSectionNum());
            }
            voList.add(vo);
        }

        //5. 返回

        return PageDTO.of(page,voList);
    }

    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        //1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        //2. 查询当前用户最近学习课程， 按，status = 1， latest_learn_time 降序排序 取第一条
        LearningLesson lesson = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING)
                .last("limit 1")
                .one();
        if(lesson == null) {
            return null;
        }
        //3. 远程调用课程服务， 给vo中的课程名 封面 章节数赋值
        CourseFullInfoDTO cinfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if(cinfo == null) {
            throw new BizIllegalException("课程不存在!");
        }
        //4. 查询当前用户课表中， 总的课程数
        Integer count = this.lambdaQuery().eq(LearningLesson::getUserId, userId).count();
        //5. 远程调用课程服务， 获取小节名称 和小节编号
        Long latestSectionId = lesson.getLatestSectionId();//获取最近学习的小节id
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(CollUtils.singletonList(latestSectionId));
        if(CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            throw new BizIllegalException("小节不存在");
        }
        //6. 封装到vo返回
        LearningLessonVO vo = BeanUtils.copyBean(lesson,LearningLessonVO.class);
        vo.setCourseName(cinfo.getName());
        vo.setCourseCoverUrl(cinfo.getCoverUrl());
        vo.setSections(cinfo.getSectionNum());
        vo.setCourseAmount(count);
        CataSimpleInfoDTO cataSimpleInfoDTO = cataSimpleInfoDTOS.get(0);
        vo.setLatestSectionName(cataSimpleInfoDTO.getName());
        vo.setLatestSectionIndex(cataSimpleInfoDTO.getCIndex());
        return vo;
    }

    @Override
    public Long isLessonValid(Long courseId) {
        // 1. 获取当前用户id
        Long userId = UserContext.getUser();

        //2. 查询该用户课表中是否有该课程
        LearningLesson lesson = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId).one();
        if(lesson == null) {
            return null;
        }
        //3. 检验课程是否过期
        LocalDateTime expireTime = lesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        if(expireTime!=null && now.isAfter(expireTime)) {
            return null;
        }
        return lesson.getId();
    }

    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        //1. 查询当前用户id
        Long userId = UserContext.getUser();
        //2. 查询课表是否有这门课
        LearningLesson lesson = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if(lesson == null) {
            return null;
        }
        return BeanUtils.copyBean(lesson, LearningLessonVO.class);
    }
}

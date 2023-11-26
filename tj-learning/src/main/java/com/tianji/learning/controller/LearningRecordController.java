package com.tianji.learning.controller;


import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.service.impl.LearningRecordServiceImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 学习记录表 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-11-25
 */
@RestController
@RequestMapping("/learning-records")
@Api(tags = "学习记录相关接口")
@RequiredArgsConstructor
public class LearningRecordController {
    private final ILearningRecordService learningRecordService;
    @GetMapping("/course/{courseId}")
    @ApiOperation("查询当前用户指定课程的学习进度")
    public LearningLessonDTO queryLearningRecordByCourse(@PathVariable("courseId") Long courseId) {
        return learningRecordService.queryLearningRecordByCourseId(courseId);
    }

    @PostMapping
    @ApiOperation("提交学习记录")
    public void addLearningRecord(@RequestBody @Validated LearningRecordFormDTO dto) {
        learningRecordService.addLearningRecord(dto);
    }

}

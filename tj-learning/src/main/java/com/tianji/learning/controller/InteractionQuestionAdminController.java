package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-11-27
 */
@Api(tags = "互动问题相关接口 - 管理端")
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
public class InteractionQuestionAdminController {
    private final IInteractionQuestionService questionService;

    @GetMapping("page")
    @ApiOperation("分页查询问题列表 - 管理端")
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        return questionService.queryQuestionAdminVOPage(query);
    }

    @PutMapping("/{id}/hidden/{hidden}")
    @ApiOperation("隐藏或显示问题-管理端")
    public void hiddenQuestion(@PathVariable Long id, @PathVariable Boolean hidden) {
        questionService.hiddenQuestion(id, hidden);
    }

    @GetMapping("/{id}")
    @ApiOperation("查看问题详情-管理端")
    public QuestionAdminVO queryQuestionAdminById(@PathVariable Long id) {
        return questionService.queryQuestionAdminById(id);
    }

}

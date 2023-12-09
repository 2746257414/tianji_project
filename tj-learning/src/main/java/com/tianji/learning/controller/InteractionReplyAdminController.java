package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-11-27
 */
@RestController
@RequestMapping("/admin/replies")
@Api(tags = "互动问题的回答或评论相关接口-管理端")
@RequiredArgsConstructor
public class InteractionReplyAdminController {
    private final IInteractionReplyService replyService;

    @ApiOperation("管理端分页查询回答或评论列表")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyAdminVoPage(ReplyPageQuery query) {
        return replyService.queryReplyAdminVoPage(query);
    }

    @ApiOperation("隐藏或显示回复-管理端")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenReply(@PathVariable Long id, @PathVariable Boolean hidden) {
        replyService.hiddenReply(id,hidden);
    }
}

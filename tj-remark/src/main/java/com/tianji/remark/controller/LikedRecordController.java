package com.tianji.remark.controller;


import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.service.ILikedRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-11-29
 */
@RestController
@RequestMapping("/likes")
@Api(tags = "点赞相关接口")
@RequiredArgsConstructor
public class LikedRecordController {
    private final ILikedRecordService likedRecordService;
    @PostMapping
    @ApiOperation("点赞或取消赞")
    public void addLikeRecord(@RequestBody @Validated LikeRecordFormDTO dto) {
        likedRecordService.addLikeRecord(dto);
    }

    @GetMapping("/list")
    @ApiOperation("查询点赞列表")
    public Set<Long> getLikesStatusByBizIds(@RequestParam("bizIds") List<Long> bizIds) {
        return likedRecordService.getLikesStatusByBizIds(bizIds);
    }
}

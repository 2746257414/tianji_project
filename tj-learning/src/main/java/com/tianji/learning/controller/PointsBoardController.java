package com.tianji.learning.controller;


import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.service.IPointsBoardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-12-01
 */
@RestController
@RequestMapping("/boards")
@Api(tags = "排行榜相关接口")
@RequiredArgsConstructor
public class PointsBoardController {
    private final IPointsBoardService pointsBoardService;
    @ApiOperation("查询积分榜-当前赛季和历史赛季都可用")
    @GetMapping
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        return pointsBoardService.queryPointsBoardList(query);
    }

}

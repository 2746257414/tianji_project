package com.tianji.learning.controller;


import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author Kaza
 * @since 2023-12-01
 */
@RestController
@RequestMapping("/boards/seasons")
@RequiredArgsConstructor
@Slf4j
@Api(tags = "赛季积分排行榜相关接口")
public class PointsBoardSeasonController {
    private final IPointsBoardSeasonService pointsBoardSeasonService;
    @GetMapping("/list")
    @ApiOperation("查询赛季列表")
    public List<PointsBoardSeason> queryBoardSeasonList() {
        return pointsBoardSeasonService.list();
    }
}

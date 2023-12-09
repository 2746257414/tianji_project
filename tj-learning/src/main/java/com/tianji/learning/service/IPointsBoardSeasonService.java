package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-01
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    List<PointsBoardSeasonVO> queryBoardSeasonList();
    //3. 创建上赛季榜d单表  points_board_7
    void createPointsBoardLatestTable(Integer id);
}

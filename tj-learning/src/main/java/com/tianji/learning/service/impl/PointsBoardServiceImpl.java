package com.tianji.learning.service.impl;

import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.common.utils.DateUtils.POINTS_BOARD_SUFFIX_FORMATTER;
import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-12-01
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {
    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;

    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        //2. 判断是查当前赛季还是历史赛季  query.season  为null或者0则代表查询当前赛季
        boolean isCurrent = query.getSeason() == null || query.getSeason() == 0;    //为true则查redis

        //3. 查询我的排名和积分 根据 query.season 判断是查redis 还是数据库、
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX+ LocalDate.now().format(POINTS_BOARD_SUFFIX_FORMATTER);
        Long season = query.getSeason();        //历史赛季id
        PointsBoard board = isCurrent ? queryMyCurrentBoard(key):queryMyHistoryBoard(season);

        //4. 分页查询赛季列表 根据query.season  判断是查redis 还是数据库、
        List<PointsBoard> list = isCurrent ? queryCurrentBoard(key, query.getPageNo(), query.getPageSize()) : queryHistoryBoard(query);
        if(CollUtils.isEmpty(list)) {
            PointsBoardVO pointsBoardVO = new PointsBoardVO();
            pointsBoardVO.setBoardList(CollUtils.emptyList());
            pointsBoardVO.setRank(null);
            pointsBoardVO.setPoints(null);
            return pointsBoardVO;
        }
        //5. 封装vo返回
        PointsBoardVO vo = new PointsBoardVO();
        if(board != null) {
            vo.setPoints(board.getPoints());
            vo.setRank(board.getRank());
        }

        //5.封装用户id集合 远程调用用户服务 获取用户信息 转map
        Set<Long> userIds = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        if(CollUtils.isEmpty(userDTOS)) {
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, String> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c.getName()));
        List<PointsBoardItemVO> voList = new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setRank(pointsBoard.getRank());
            itemVO.setPoints(pointsBoard.getPoints());
            itemVO.setName(userDTOMap.get(pointsBoard.getUserId()));
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    // 查询历史赛季列表 从DB查
    private List<PointsBoard> queryHistoryBoard(PointsBoardQuery query) {
        String tableName = POINTS_BOARD_TABLE_PREFIX +query.getSeason().toString();
        TableInfoContext.setInfo(tableName);
        List<PointsBoard> list = this.lambdaQuery()
                .select(PointsBoard::getId,PointsBoard::getUserId,PointsBoard::getPoints)
                .orderByDesc(PointsBoard::getPoints)
                .list();
        if(CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return list;
    }

    // 查询当前赛季的积分榜 redis zset
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize) {
        //1. 计算start 和 stop 分页值
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;
        //2. 利用zrevrange 会按分数倒序 分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if(CollUtils.isEmpty(typedTuples)) {
            //如果没查到数据
            return CollUtils.emptyList();
        }

        int rank = (pageNo - 1)*pageSize + 1;
        List<PointsBoard> list = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String userId = typedTuple.getValue();   //用户id
            Double score = typedTuple.getScore();   //用户总积分值
            if(StringUtils.isBlank(userId) || score == null) {
                continue;
            }
            PointsBoard pointsBoard = new PointsBoard();
            pointsBoard.setUserId(Long.parseLong(userId));
            pointsBoard.setPoints(score.intValue());
            pointsBoard.setRank(rank++);
            list.add(pointsBoard);
        }

        //3. 封装结果返回
        return list;
    }

    //查询当前赛季， 我的积分和排名 redis
    private PointsBoard queryMyCurrentBoard(String key) {
        Long userId = UserContext.getUser();    //获取当前登录用户
        //2. 获取分值
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());

        //3. 获取排名 从0开始 需要 + 1
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        PointsBoard board = new PointsBoard();
        board.setRank(rank == null ? 0 : rank.intValue() + 1);
        board.setPoints(score == null ? 0 : score.intValue());
        return board;
    }

    //查询历史赛季， 我的积分和排名 DB
    private PointsBoard queryMyHistoryBoard(Long season) {
        String tableName = POINTS_BOARD_TABLE_PREFIX +season.toString();
        TableInfoContext.setInfo(tableName);
        PointsBoard one = this.lambdaQuery()
                .select(PointsBoard::getId,PointsBoard::getUserId,PointsBoard::getPoints)
                .eq(PointsBoard::getUserId, UserContext.getUser())
                .one();
        if(one == null) {
            return null;
        }
        one.setRank(one.getId().intValue());
        return one;
    }
}

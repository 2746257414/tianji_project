package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.remark.RemarkClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.BooleanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.desktop.QuitEvent;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-11-27
 */
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {
    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;
    private final RemarkClient remarkClient;

    @Override
    public void saveReply(ReplyDTO dto) {
        //1. 获取当前用户
        Long userId = UserContext.getUser();
        //2. 保存回答或评论 interaction_reply
        InteractionReply reply = BeanUtils.copyBean(dto, InteractionReply.class);
        reply.setUserId(userId);
        this.save(reply);
        InteractionQuestion questionInfo = questionMapper.selectById(dto.getQuestionId());  // 回答
        //3. 判断是回答还是评论  dto.anwserId 为空是回答
        if (dto.getAnswerId() != null) {
            //3.1 如果不是回答（是评论） 累加评论目标的条目的停驾次数
            InteractionReply answerInfo = this.getById(dto.getAnswerId());  // 回答
            answerInfo.setReplyTimes(answerInfo.getReplyTimes() + 1);
            this.updateById(answerInfo);
        } else {
            //3.2 如果是回答 修改问题最近一次回答id 同时累加问题表回答次数
            questionInfo.setAnswerTimes(questionInfo.getAnswerTimes() + 1);
            questionInfo.setLatestAnswerId(reply.getId());

        }
        //4. 判断是否是学生提交 dto.isStudent 为true 则讲该问题的status设置为未查看
        if (BooleanUtils.isTrue(dto.getIsStudent())) {
            questionInfo.setStatus(QuestionStatus.UN_CHECK);
        }

        questionMapper.updateById(questionInfo);
//        boolean isAnswer = dto.getAnswerId() == null;
//        if(!isAnswer) {     //如果是评论
//            lambdaUpdate()
//                    .setSql("answerInfo.getReplyTimes() + 1")
//                    .eq(InteractionReply::getId, reply.getId())
//                    .update();
//        }
//        InteractionQuestion questionInfo = questionMapper.selectById(dto.getQuestionId());
//        LambdaUpdateWrapper<InteractionQuestion> wrapper = new LambdaUpdateWrapper<>();
//        wrapper.setSql(isAnswer, "answer_times = answer_times + 1");
//        wrapper.set(isAnswer,InteractionQuestion::getLatestAnswerId, reply.getAnswerId());
//        wrapper.set(dto.getIsStudent(), InteractionQuestion::getStatus, QuestionStatus.UN_CHECK);
//        questionMapper.update(questionInfo,wrapper);

    }

    @Override
    public PageDTO<ReplyVO> queryReplyVoPage(ReplyPageQuery query) {
        //1. 校验questionId和answerId是否都为空
        if (query.getAnswerId() == null && query.getQuestionId() == null) {
            throw new BadRequestException("问题id和回答id不能都为空");
        }
        //2. 分页查询Interaction_reply表
        Page<InteractionReply> page = lambdaQuery()
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                .eq(InteractionReply::getAnswerId, query.getAnswerId() == null ? 0L : query.getAnswerId())
                .eq(InteractionReply::getHidden, false)
                .page(query.toMpPage(
                        new OrderItem("liked_times", false),
                        new OrderItem("create_time", false)));
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L, 0L);
        }
        Set<Long> uids = records.stream().filter(c -> !c.getAnonymity()).map(InteractionReply::getUserId).collect(Collectors.toSet());
        uids.addAll(records.stream().filter(c -> !c.getAnonymity()).map(InteractionReply::getTargetUserId).collect(Collectors.toSet()));
        Set<Long> targetReplyIds = records.stream().filter(c -> c.getTargetReplyId() > 0).map(InteractionReply::getTargetReplyId).collect(Collectors.toSet());
        Set<Long> userLikesRepliesIds = records.stream().map(InteractionReply::getId).collect(Collectors.toSet());
        //查询目标回复， 如果目标回复不是匿名， 需要查询出目标回复的用户信息
        if (CollUtils.isNotEmpty(targetReplyIds)) {
            List<InteractionReply> targetReplyList = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplyList.stream().filter(Predicate.not(InteractionReply::getAnonymity))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            uids.addAll(targetUserIds);
        }

        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (CollUtils.isNotEmpty(userDTOS)) {
            userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));
        }

        // 查询当前用户对列表获取到的回复的业务是否点过赞 用
        Set<Long> userLikedReplyIds = remarkClient.getLikesStatusByBizIds(new ArrayList<>(userLikesRepliesIds));
        List<ReplyVO> VoList = new ArrayList<>();
        for (InteractionReply record : records) {
            ReplyVO vo = BeanUtils.copyBean(record, ReplyVO.class);
            if (!record.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserName(userDTO.getUsername());
                    vo.setUserIcon(userDTO.getIcon());
                    vo.setUserType(userDTO.getType());
                }
            }
            UserDTO targetUserDTO = userDTOMap.get(record.getTargetUserId());
            if (targetUserDTO != null) {
                vo.setTargetUserName(targetUserDTO.getName());
            }
            vo.setLiked(userLikedReplyIds.contains(record.getId()));
            VoList.add(vo);
        }
        return PageDTO.of(page, VoList);
    }

    @Override
    public PageDTO<ReplyVO> queryReplyAdminVoPage(ReplyPageQuery query) {
        //1. 校验questionId和answerId是否都为空
        if (query.getAnswerId() == null && query.getQuestionId() == null) {
            throw new BadRequestException("问题id和回答id不能都为空");
        }
        //2. 分页查询Interaction_reply表
        Page<InteractionReply> page = lambdaQuery()
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                .eq(InteractionReply::getAnswerId, query.getAnswerId() == null ? 0L : query.getAnswerId())
                .page(query.toMpPage(
                        new OrderItem("liked_times", false),
                        new OrderItem("create_time", false)));
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L, 0L);
        }
        Set<Long> uids = records.stream().map(InteractionReply::getUserId).collect(Collectors.toSet());
        uids.addAll(records.stream().map(InteractionReply::getTargetUserId).collect(Collectors.toSet()));
        Set<Long> targetReplyIds = records.stream().filter(c -> c.getTargetReplyId() > 0).map(InteractionReply::getTargetReplyId).collect(Collectors.toSet());

        //查询目标回复， 如果目标回复不是匿名， 需要查询出目标回复的用户信息
        if (CollUtils.isNotEmpty(targetReplyIds)) {
            List<InteractionReply> targetReplyList = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplyList.stream().filter(Predicate.not(InteractionReply::getAnonymity))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            uids.addAll(targetUserIds);
        }

        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (CollUtils.isNotEmpty(userDTOS)) {
            userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));
        }
        List<ReplyVO> VoList = new ArrayList<>();
        for (InteractionReply record : records) {
            ReplyVO vo = BeanUtils.copyBean(record, ReplyVO.class);

            UserDTO userDTO = userDTOMap.get(record.getUserId());
            if (userDTO != null) {
                vo.setUserName(userDTO.getUsername());
                vo.setUserIcon(userDTO.getIcon());
                vo.setUserType(userDTO.getType());
            }

            UserDTO targetUserDTO = userDTOMap.get(record.getTargetUserId());
            if (targetUserDTO != null) {
                vo.setTargetUserName(targetUserDTO.getName());
            }
            VoList.add(vo);
        }

        return PageDTO.of(page, VoList);
    }

    @Override
    public void hiddenReply(Long id, Boolean hidden) {
        InteractionReply reply = this.getById(id);
        if(reply == null) {
            throw new BadRequestException("非法参数");
        }
        this.lambdaUpdate()
                .eq(InteractionReply::getId,id)
                .set(InteractionReply::getHidden, BooleanUtils.isTrue(hidden))
                .update();
        this.lambdaUpdate()
                .eq(InteractionReply::getTargetReplyId,reply.getId())
                .set(InteractionReply::getHidden, BooleanUtils.isTrue(hidden))
                .update();
    }
}

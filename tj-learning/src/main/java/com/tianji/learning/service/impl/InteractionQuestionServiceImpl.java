package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CategoryClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.desktop.QuitEvent;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author Kaza
 * @since 2023-11-27
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {
    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;
    @Override
    public void saveQuestion(QuestionFormDTO dto) {
        //1. 获取当前登录用户id
        Long userId = UserContext.getUser();
        //2. dto转po
        InteractionQuestion interactionQuestion = BeanUtils.copyBean(dto, InteractionQuestion.class);
        interactionQuestion.setUserId(userId);
        //3. 保存到数据库
        this.save(interactionQuestion);
    }

    @Override
    public void updateQuestion(Long id, QuestionFormDTO dto) {
        //1. 校验
        if (StringUtils.isBlank(dto.getTitle()) || StringUtils.isBlank(dto.getDescription()) || dto.getAnonymity() == null) {
            throw new BadRequestException("非法参数");
        }
        // 校验id
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("非法参数");
        }
        //只能修改自己的互动问题
        Long userId = UserContext.getUser();
        if (!userId.equals(question.getUserId())) {
            throw new BadRequestException("不能修改别人的问题！");
        }

        //2. dto转po
        question.setTitle(dto.getTitle());
        question.setDescription(dto.getDescription());
        question.setAnonymity(dto.getAnonymity());
        //3. 修改
        this.updateById(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //1. 校验 参数courseId
        if (query.getCourseId() == null) {
            throw new BadRequestException("课程id不能为空");
        }
        //2. 获取登录用户id
        Long userId = UserContext.getUser();

        //3. 分页查询互动问题interaction_question 条件： courseId  onlyMine为true才会加userId 小节id不为空  hidden为false 分页查询 按提问时间倒序
        Page<InteractionQuestion> page = this.lambdaQuery()
                .select(InteractionQuestion.class, tableFieldInfo -> !tableFieldInfo.getProperty().equals("description")) //指定 不查询的字段
                .eq(InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, userId)
                .eq(query.getSectionId() != null, InteractionQuestion::getSectionId, query.getSectionId())
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();//获取提问信息列表
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        //4. 远程调用用户服务 获取用户信息 批量
//        Set<Long> latestAnswerIds = new HashSet<Long>();
//        for (InteractionQuestion record : records) {
//            if(record.getLatestAnswerId()!=null) {
//                latestAnswerIds.add(record.getLatestAnswerId());
//            }
//        }
        //4. 根据提问信息列表 查询一个个提问下面的回答
        Set<Long> latestAnswerIds = records.stream()//获取最新回答的id
                .filter(c -> c.getLatestAnswerId() != null)
                .map(InteractionQuestion::getLatestAnswerId)
                .collect(Collectors.toSet());
        Map<Long, InteractionReply> replyMap = null;
        Set<Long> userIds = records.stream()//获取所有提问学员的id
                .filter(c -> c.getUserId() != null)
                .map(InteractionQuestion::getUserId)
                .collect(Collectors.toSet());
        if(CollUtils.isNotEmpty(latestAnswerIds)) {
//            List<InteractionReply> replyList = replyService.listByIds(latestAnswerIds); //回复列表
            List<InteractionReply> replyList = replyService.list(Wrappers.<InteractionReply>lambdaQuery()
                    .in(InteractionReply::getId,latestAnswerIds)
                    .eq(InteractionReply::getHidden,false));
            replyMap = replyList.stream().collect(Collectors.toMap(InteractionReply::getId, c -> c));
            for (InteractionReply reply : replyList) {
                if(!reply.getAnonymity()) {
                    userIds.add(reply.getUserId());     //所有回复者的id集合也添加进去
                }
            }
        }

        //5. 远程调用user服务， 批量获取提问者和回复者的用户信息
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));

        List<QuestionVO> voList = new ArrayList<>();
        //6. 封装vo返回
        for (InteractionQuestion record : records) {
            QuestionVO vo = BeanUtils.copyBean(record, QuestionVO.class);
            if (!vo.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if(userDTO!=null) {
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                }
            }
            InteractionReply reply = null;
            if(replyMap!=null) {
                reply = replyMap.get(record.getLatestAnswerId());
            }
            if(reply!=null) {
                if(!reply.getAnonymity()) {
                    UserDTO userDTO = userDTOMap.get(reply.getUserId());
                    if(userDTO!=null) {
                        vo.setLatestReplyUser(userDTO.getName());    //回答者的昵称
                    }
                }
                vo.setLatestReplyContent(reply.getContent()); //最新回答信息
            }
            voList.add(vo);
        }
        return PageDTO.of(page, voList);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1. 校验
        if(id == null) {
            throw new BadRequestException("非法参数");
        }
        //2. 查询问题 按主键查询
        InteractionQuestion question = this.getById(id);
        if(question == null) {
            throw new BadRequestException("问题不存在");
        }

        //3. 如果该问题管理员设置了隐藏 返回空
        if(BooleanUtils.isTrue(question.getHidden())) {
            return null;
        }

        //5. 封装vo返回
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);
        //4. 如果用户弥明提问 不用查询提问者昵称和头像
        if(BooleanUtils.isFalse(question.getAnonymity())) {
            UserDTO userDTO = userClient.queryUserById(question.getUserId());
            if(userDTO != null) {
                vo.setUserName(userDTO.getName());
                vo.setUserIcon(userDTO.getIcon());
            }
        }
        return vo;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        //0. 如果用户穿了课程的名称参数 ， 则从es中获取该名称对应的课程id
        String courseName = query.getCourseName();
        List<Long> cids = null;
        if(StringUtils.isNotBlank(courseName)) {
            cids = searchClient.queryCoursesIdByName(query.getCourseName());
            if(CollUtils.isEmpty(cids)) {
                return PageDTO.empty(0L,0L);
            }
        }
        //1.获取问互动题列表 Interaction_question 看前端传来的参数作为查询条件
        Page<InteractionQuestion> page = this.lambdaQuery()
                .in(cids!=null,InteractionQuestion::getCourseId, cids)
                .eq(query.getStatus() != null, InteractionQuestion::getStatus, query.getStatus())
                .between(query.getBeginTime() != null && query.getEndTime() != null,
                        InteractionQuestion::getCreateTime,
                        query.getBeginTime(),
                        query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if(CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L,0L);
        }
        //获取用户id集合
        Set<Long> uids = new HashSet<>();
        //获取课程id集合
        Set<Long> courseIds = new HashSet<>();
        //获取章和节id集合
        Set<Long> chapterAndSectionIds = new HashSet<>();
        for (InteractionQuestion record : records) {
            uids.add(record.getUserId());
            courseIds.add(record.getCourseId());
            chapterAndSectionIds.add(record.getChapterId());    //章id
            chapterAndSectionIds.add(record.getSectionId());    //节id
        }
        //2. 远程调用用户服务 获取用户信息
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if(CollUtils.isEmpty(userDTOS)) {
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, user -> user));
        //3. 远程调用课程服务 获取课程信息
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseIds);
        if(CollUtils.isEmpty(simpleInfoList)) {
            throw new BizIllegalException("课程不存在");
        }
        Map<Long, CourseSimpleInfoDTO> cinfos = simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, user -> user));
        //4. 远程调用课程服务 获取章节信息
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(chapterAndSectionIds);
        if(CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            throw new BizIllegalException("章节信息不存在");
        }
        Map<Long, String> cataInfoDTOMap = cataSimpleInfoDTOS.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, c -> c.getName()));

        //6.po 转 vo 返回
        List<QuestionAdminVO> voList = new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionAdminVO adminVO = BeanUtils.copyBean(record, QuestionAdminVO.class);
            if(userDTOMap.get(record.getUserId())!=null) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                adminVO.setUserName(userDTO.getName());
            }
            CourseSimpleInfoDTO cinfo = cinfos.get(record.getCourseId());
            if(cinfo!=null) {
                adminVO.setCourseName(cinfo.getName());
                List<Long> categoryIds = cinfo.getCategoryIds();
                //5. 获取分类信息 一二三级分类id
                String categoryNam  = categoryCache.getCategoryNames(categoryIds);
                adminVO.setCategoryName(categoryNam);      //三级分类名称， 拼接字段
            }
            adminVO.setChapterName(cataInfoDTOMap.get(record.getChapterId()));       //章名称
            adminVO.setSectionName(cataInfoDTOMap.get(record.getSectionId()));       //节名称
            voList.add(adminVO);
        }
        return PageDTO.of(page,voList);
    }

    @Override
    public void deleteMyQuestionById(Long id) {
        //1. 获取当前用户
        Long userId = UserContext.getUser();

        //2. 校验
        InteractionQuestion question = this.lambdaQuery()
                .eq(InteractionQuestion::getUserId, userId)
                .eq(InteractionQuestion::getId, id)
                .one();
        if(question ==  null) {
            throw new BadRequestException("非法参数");
        }
        //只能删除自己的互动问题
        if (!userId.equals(question.getUserId())) {
            throw new BadRequestException("不能删除别人的问题！");
        }
        this.removeById(id);
        //3. 删除评论以及相关回复
        QueryWrapper<InteractionReply> wrapper = new QueryWrapper<>();
        wrapper.eq("question_id", question.getId());
        replyService.remove(wrapper);
    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        InteractionQuestion question = this.getById(id);
        if(question == null) {
            throw new BadRequestException("非法参数");
        }
        this.lambdaUpdate()
                .eq(InteractionQuestion::getId,id)
                .set(InteractionQuestion::getHidden, BooleanUtils.isTrue(hidden))
                .update();
    }

    @Override
    public QuestionAdminVO queryQuestionAdminById(Long id) {
        InteractionQuestion question = this.getById(id);
        if(question == null) {
            throw new BizIllegalException("问题不存在!");
        }
        this.lambdaUpdate()
                .eq(InteractionQuestion::getId,id)
                .set(InteractionQuestion::getStatus, true)
                .update();
        QuestionAdminVO adminVO = BeanUtils.copyBean(question, QuestionAdminVO.class);
        //设置课程名称
        CourseFullInfoDTO cinfo = courseClient.getCourseInfoById(question.getCourseId(), false, true);
        if(cinfo!=null) {
            adminVO.setCourseName(cinfo.getName());
            //设置目录
            List<Long> categoryIds = cinfo.getCategoryIds();
            String categoryNam  = categoryCache.getCategoryNames(categoryIds);
            adminVO.setCategoryName(categoryNam);      //三级分类名称， 拼接字段

            //设置教师名称
            List<Long> teacherIds = cinfo.getTeacherIds();
            List<UserDTO> userDTOS = userClient.queryUserByIds(teacherIds);
            if(!CollUtils.isEmpty(userDTOS)) {
                List<String> teachersNames = userDTOS.stream().map(UserDTO::getName).collect(Collectors.toList());
                String teachersName = String.join(",", teachersNames);
                adminVO.setTeacherName(teachersName);
            }
        }
        //设置提问者姓名
        if(!question.getAnonymity()) {
            Long userId = question.getUserId();
            UserDTO userDTO = userClient.queryUserById(userId);
            adminVO.setUserName(userDTO.getName());
            adminVO.setUserIcon(userDTO.getIcon());
        }

        Set<Long> chapterAndSectionIds = new HashSet<>();
        chapterAndSectionIds.add(question.getSectionId());
        chapterAndSectionIds.add(question.getChapterId());
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(chapterAndSectionIds);
        Map<Long, String> cataInfoDTOMap = cataSimpleInfoDTOS.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, c -> c.getName()));
        //设置章节名称
        if(!CollUtils.isEmpty(cataInfoDTOMap)) {
            adminVO.setChapterName(cataInfoDTOMap.get(question.getChapterId()));       //章名称
            adminVO.setSectionName(cataInfoDTOMap.get(question.getSectionId()));       //节名称
        }
        return adminVO;
    }
}

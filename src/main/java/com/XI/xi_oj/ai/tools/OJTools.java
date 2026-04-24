package com.XI.xi_oj.ai.tools;

import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;
import com.XI.xi_oj.model.dto.question.QuestionQueryRequest;
import com.XI.xi_oj.model.dto.question.WrongQuestionVO;
import com.XI.xi_oj.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import com.XI.xi_oj.model.vo.QuestionVO;
import com.XI.xi_oj.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OJTools类，用于在线判题系统的相关功能实现
 * 包含题目查询、代码判题和错题记录查询等功能
 */
@Component
public class OJTools {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void clearCurrentUserId() {
        CURRENT_USER_ID.remove();
    }

    @Autowired
    private QuestionService questionService;

    @Autowired
    private AiJudgeService judgeService;

    @Autowired
    private WrongQuestionService wrongQuestionService;

    @Autowired
    @Lazy
    private AiWrongQuestionService aiWrongQuestionService;

    @Autowired
    private QuestionSubmitService questionSubmitService;

    @Autowired
    @Lazy
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    /**
     * 查询题目信息的工具方法
     * @param keyword 搜索关键词，可以是题目ID或题目标题等
     * @return 返回格式化的题目信息，包括ID、标题、题干、标签、难度和参考答案
     */
    @Tool(
            name = "query_question_info",
            value = "查询题目信息，可根据题目ID或关键词检索，返回题目标题、题干、标签、难度和参考答案。"
    )
    public String queryQuestionInfo(String keyword) {
        QuestionVO question = questionService.getByKeyword(keyword); // 根据关键词获取题目信息
        if (question == null) { // 如果未找到题目，返回提示信息
            return "未找到对应题目，请检查题目ID或关键词是否正确。";
        }
        return String.format("""
                        题目ID：%d
                        标题：[%s](/view/question/%d)
                        题干：%s
                        标签：%s
                        难度：%s
                        参考答案：%s
                        """,
                question.getId(),
                question.getTitle(), question.getId(),
                question.getContent(),
                question.getTags(),
                question.getDifficulty(),
                question.getAnswer()
        );
    }

    /**
     * 执行用户代码判题的工具方法
     * @param questionId 题目ID，指定要判题的题目
     * @param code 用户提交的代码内容
     * @param language 编程语言类型，如java/python/cpp等
     * @param userId 当前用户ID，用于标识提交代码的用户
     * @return 返回格式化的判题结果，包括状态、耗时、内存使用和错误信息
     */
    @Tool(
            name = "judge_user_code",
            value = "对用户代码执行判题。参数包含：userId、questionId、code、language。userId从上下文信息中获取。"
    )
    public String judgeUserCode(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId,
            @P("题目ID，Long类型") Long questionId,
            @P("用户提交的代码内容") String code,
            @P("代码语言，例如 java / python / cpp") String language
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        JudgeResultDTO result = judgeService.submitCode(questionId, code, language, resolvedUserId);
        return String.format("""
                        判题结果：%s
                        耗时：%sms
                        内存：%sMB
                        错误信息：%s
                        """,
                result.getStatus(),
                result.getTimeUsed(),
                result.getMemoryUsed(),
                result.getErrorMsg()
        );
    }

    /**
     * 查询用户错题记录的工具方法
     * @param userId 用户ID，指定要查询的用户
     * @param questionId 题目ID，指定要查询的题目
     * @return 返回格式化的错题记录信息，包括错误代码、判题结果、历史分析和复习次数
     */
    @Tool(
            name = "query_user_wrong_question",
            value = "按题目ID查询当前用户的错题记录，返回错误代码、判题结果、历史分析和复习次数。userId从上下文信息中获取。"
    )
    public String queryUserWrongQuestion(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId,
            @P("题目ID，Long类型") Long questionId
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        WrongQuestionVO wrongQuestion = wrongQuestionService.getByUserAndQuestion(resolvedUserId, questionId);
        if (wrongQuestion == null) { // 如果未找到错题记录，返回提示信息
            return "未找到对应错题记录。";
        }
        return String.format("""
                        错误代码：%s
                        错误判题结果：%s
                        历史分析：%s
                        复习次数：%d
                        """,
                wrongQuestion.getWrongCode(),
                wrongQuestion.getWrongJudgeResult(),
                wrongQuestion.getWrongAnalysis(),
                wrongQuestion.getReviewCount()
        );
    }

    @Tool(
            name = "search_questions",
            value = "按条件搜索题目列表。支持按关键词（标题模糊匹配）、标签（如动态规划、数组、二叉树）、难度（easy/medium/hard）筛选，返回最多10道题目。"
    )
    public String searchQuestions(
            @P("搜索关键词，按标题模糊匹配，可为空") String keyword,
            @P("题目标签，如 动态规划、数组、贪心 等，可为空") String tag,
            @P("难度等级：easy / medium / hard，可为空") String difficulty
    ) {
        QuestionQueryRequest req = new QuestionQueryRequest();
        if (keyword != null && !keyword.isBlank()) {
            req.setTitle(keyword.trim());
        }
        if (tag != null && !tag.isBlank()) {
            req.setTags(Collections.singletonList(tag.trim()));
        }
        if (difficulty != null && !difficulty.isBlank()) {
            req.setDifficulty(difficulty.trim());
        }
        req.setPageSize(10);
        req.setSortField("createTime");
        req.setSortOrder("descend");

        QueryWrapper<Question> wrapper = questionService.getQueryWrapper(req);
        Page<Question> page = questionService.page(new Page<>(1, 10), wrapper);
        List<Question> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return "未找到匹配的题目，请尝试其他关键词或标签。";
        }
        StringBuilder sb = new StringBuilder("共找到 " + page.getTotal() + " 道题目（显示前 " + records.size() + " 道）：\n");
        for (Question q : records) {
            sb.append(String.format("- [%s](/view/question/%d) | 难度: %s | 标签: %s\n",
                    q.getTitle(), q.getId(), q.getDifficulty(), q.getTags()));
        }
        return sb.toString();
    }

    @Tool(
            name = "find_similar_questions",
            value = "根据题目ID查找相似题目，基于向量相似度检索，返回同类型同难度的相关题目列表。"
    )
    public String findSimilarQuestions(
            @P("题目ID，Long类型") Long questionId
    ) {
        Question question = questionService.getById(questionId);
        if (question == null || Integer.valueOf(1).equals(question.getIsDelete())) {
            return "题目不存在，请检查题目ID。";
        }
        String content = question.getTitle() + "\n" + question.getContent() + "\n" + question.getTags();
        List<Long> similarIds = ojKnowledgeRetriever.retrieveSimilarQuestions(
                questionId, content, question.getDifficulty());
        if (similarIds == null || similarIds.isEmpty()) {
            return "未找到相似题目。";
        }
        List<Question> questions = questionService.listByIds(similarIds);
        if (questions == null || questions.isEmpty()) {
            return "未找到相似题目。";
        }
        StringBuilder sb = new StringBuilder("找到 " + questions.size() + " 道相似题目：\n");
        for (Question q : questions) {
            sb.append(String.format("- [%s](/view/question/%d) | 难度: %s | 标签: %s\n",
                    q.getTitle(), q.getId(), q.getDifficulty(), q.getTags()));
        }
        return sb.toString();
    }

    @Tool(
            name = "list_user_wrong_questions",
            value = "列出当前用户的所有错题记录，返回错题列表（题目ID、判题结果、复习次数）。userId从上下文信息中获取。"
    )
    public String listUserWrongQuestions(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        List<WrongQuestionVO> wrongList = aiWrongQuestionService.listMyWrongQuestions(resolvedUserId);
        if (wrongList == null || wrongList.isEmpty()) {
            return "暂无错题记录。";
        }
        StringBuilder sb = new StringBuilder("共有 " + wrongList.size() + " 道错题：\n");
        for (WrongQuestionVO w : wrongList) {
            sb.append(String.format("- 题目 [#%d](/view/question/%d) | 判题结果: %s | 复习次数: %d\n",
                    w.getQuestionId(), w.getQuestionId(), w.getWrongJudgeResult(), w.getReviewCount()));
        }
        return sb.toString();
    }

    @Tool(
            name = "query_user_submit_history",
            value = "查询当前用户的代码提交记录，可按题目ID筛选，返回最近10条提交（语言、状态、时间）。userId从上下文信息中获取。"
    )
    public String queryUserSubmitHistory(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId,
            @P("题目ID，可为空，为空时查询所有题目的提交记录") Long questionId
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        QuestionSubmitQueryRequest req = new QuestionSubmitQueryRequest();
        req.setUserId(resolvedUserId);
        if (questionId != null) {
            req.setQuestionId(questionId);
        }
        req.setPageSize(10);
        req.setSortField("createTime");
        req.setSortOrder("descend");

        QueryWrapper<QuestionSubmit> wrapper = questionSubmitService.getQueryWrapper(req);
        Page<QuestionSubmit> page = questionSubmitService.page(new Page<>(1, 10), wrapper);
        List<QuestionSubmit> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return "暂无提交记录。";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String[] statusNames = {"等待中", "判题中", "通过", "失败"};
        StringBuilder sb = new StringBuilder("最近 " + records.size() + " 条提交记录：\n");
        for (QuestionSubmit s : records) {
            String statusName = (s.getStatus() >= 0 && s.getStatus() < statusNames.length)
                    ? statusNames[s.getStatus()] : String.valueOf(s.getStatus());
            sb.append(String.format("- 题目 [#%d](/view/question/%d) | 语言: %s | 状态: %s | 时间: %s\n",
                    s.getQuestionId(), s.getQuestionId(), s.getLanguage(), statusName,
                    s.getCreateTime() != null ? sdf.format(s.getCreateTime()) : "未知"));
        }
        return sb.toString();
    }
}

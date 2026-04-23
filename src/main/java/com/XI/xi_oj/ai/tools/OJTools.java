package com.XI.xi_oj.ai.tools;

import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;
import com.XI.xi_oj.model.dto.question.WrongQuestionVO;
import com.XI.xi_oj.model.vo.QuestionVO;
import com.XI.xi_oj.service.AiJudgeService;
import com.XI.xi_oj.service.QuestionService;
import com.XI.xi_oj.service.WrongQuestionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
                        标题：%s
                        题干：%s
                        标签：%s
                        难度：%s
                        参考答案：%s
                        """,
                question.getId(),
                question.getTitle(),
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
            value = "对用户代码执行判题。参数包含：questionId、code、language。"
    )
    public String judgeUserCode(
            @P("题目ID，Long类型") Long questionId,
            @P("用户提交的代码内容") String code,
            @P("代码语言，例如 java / python / cpp") String language
    ) {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        JudgeResultDTO result = judgeService.submitCode(questionId, code, language, userId);
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
            value = "按题目ID查询当前用户的错题记录，返回错误代码、判题结果、历史分析和复习次数。"
    )
    public String queryUserWrongQuestion(
            @P("题目ID，Long类型") Long questionId
    ) {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        WrongQuestionVO wrongQuestion = wrongQuestionService.getByUserAndQuestion(userId, questionId);
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
}

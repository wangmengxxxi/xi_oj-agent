package com.XI.xi_oj.ai.tools;

import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.model.dto.judge.CustomTestResultDTO;
import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;
import com.XI.xi_oj.model.dto.question.WrongQuestionVO;
import com.XI.xi_oj.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import com.XI.xi_oj.model.vo.QuestionVO;
import com.XI.xi_oj.mapper.QuestionSubmitMapper;
import com.XI.xi_oj.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
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
    private QuestionSubmitMapper questionSubmitMapper;

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
            value = "按条件搜索题目列表。keyword 会同时匹配标题和标签，tag 专门按标签筛选。返回最多10道题目。"
    )
    public String searchQuestions(
            @P("搜索关键词，同时在标题和标签中模糊匹配，如'队列'、'二分查找'") String keyword,
            @P("题目标签精确筛选，如 动态规划、数组、贪心、队列 等") String tag,
            @P("难度等级：easy / medium / hard，可为空") String difficulty
    ) {
        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        wrapper.eq("isDelete", false);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("title", kw).or().like("tags", kw));
        }
        if (tag != null && !tag.isBlank()) {
            wrapper.like("tags", tag.trim());
        }
        if (difficulty != null && !difficulty.isBlank()) {
            wrapper.eq("difficulty", difficulty.trim().toLowerCase(java.util.Locale.ROOT));
        }
        wrapper.orderByDesc("createTime");

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
            return "题库中暂无与该题目相似的题目，可能是题目尚未入库或向量数据库暂时不可用。";
        }
        List<Question> questions = questionService.listByIds(similarIds);
        if (questions == null || questions.isEmpty()) {
            return "题库中暂无与该题目相似的题目。";
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

    @Tool(
            name = "query_user_mastery",
            value = "分析当前用户各知识点的掌握情况，按标签维度统计AC率和错题数，薄弱知识点排在前面。userId从上下文信息中获取。"
    )
    public String queryUserMastery(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        List<Map<String, Object>> mastery = questionSubmitMapper.selectTagMastery(resolvedUserId);
        if (mastery == null || mastery.isEmpty()) {
            return "暂无提交记录，无法分析知识点掌握情况。";
        }
        StringBuilder sb = new StringBuilder("用户知识点掌握分析（按薄弱程度排序）：\n");
        for (Map<String, Object> row : mastery) {
            sb.append(String.format("- %s | 提交: %s次 | AC: %s次 | 失败: %s次 | AC率: %s%%\n",
                    row.get("tag"), row.get("totalSubmit"), row.get("acCount"),
                    row.get("failCount"), row.get("acRate")));
        }
        return sb.toString();
    }

    @Tool(
            name = "get_question_hints",
            value = "获取题目的分层提示，引导用户独立思考。hintLevel=1给考点提示，=2给解题方向，=3给伪代码框架。逐步递进，不直接给答案。"
    )
    public String getQuestionHints(
            @P("题目ID，Long类型") Long questionId,
            @P("提示级别：1=考点提示，2=解题方向，3=伪代码框架") int hintLevel
    ) {
        Question question = questionService.getById(questionId);
        if (question == null || Integer.valueOf(1).equals(question.getIsDelete())) {
            return "题目不存在，请检查题目ID。";
        }
        StringBuilder sb = new StringBuilder();
        if (hintLevel >= 1) {
            sb.append("【考点提示】\n");
            sb.append("本题涉及的知识点标签：").append(question.getTags()).append("\n");
            sb.append("难度：").append(question.getDifficulty()).append("\n");
            String content = question.getTitle() + "\n" + question.getContent() + "\n" + question.getTags();
            List<Long> similarIds = ojKnowledgeRetriever.retrieveSimilarQuestions(questionId, content, question.getDifficulty());
            if (similarIds != null && !similarIds.isEmpty()) {
                List<Question> similar = questionService.listByIds(similarIds);
                if (similar != null && !similar.isEmpty()) {
                    sb.append("类似题目（可参考思路）：\n");
                    for (Question q : similar) {
                        sb.append(String.format("  - [%s](/view/question/%d) | 难度: %s\n", q.getTitle(), q.getId(), q.getDifficulty()));
                    }
                }
            } else {
                sb.append("（题库中暂无相似题目）\n");
            }
        }
        if (hintLevel >= 2) {
            sb.append("\n【解题方向】\n");
            String answer = question.getAnswer();
            if (answer != null && !answer.isBlank()) {
                String lowerAnswer = answer.toLowerCase();
                if (lowerAnswer.contains("dp") || lowerAnswer.contains("动态规划") || lowerAnswer.contains("memo")) {
                    sb.append("- 建议使用动态规划思路，考虑状态定义和转移方程\n");
                } else if (lowerAnswer.contains("bfs") || lowerAnswer.contains("dfs") || lowerAnswer.contains("搜索")) {
                    sb.append("- 建议使用搜索算法（BFS/DFS），注意剪枝优化\n");
                } else if (lowerAnswer.contains("sort") || lowerAnswer.contains("排序") || lowerAnswer.contains("贪心")) {
                    sb.append("- 建议考虑排序或贪心策略\n");
                } else if (lowerAnswer.contains("二分") || lowerAnswer.contains("binary")) {
                    sb.append("- 建议使用二分查找思路\n");
                } else {
                    sb.append("- 仔细分析题目约束条件，选择合适的数据结构\n");
                }
            } else {
                sb.append("- 根据标签提示选择对应的算法策略\n");
            }
        }
        if (hintLevel >= 3) {
            sb.append("\n【伪代码框架】\n");
            sb.append("请先自行尝试编写，以下是通用框架提示：\n");
            sb.append("1. 读取输入并解析\n");
            sb.append("2. 初始化数据结构\n");
            sb.append("3. 核心算法处理\n");
            sb.append("4. 输出结果\n");
            sb.append("\n【边界条件提醒】\n");
            sb.append("- 注意空输入、单元素、最大值边界\n");
            sb.append("- 注意整数溢出（考虑使用long）\n");
            sb.append("- 注意时间复杂度是否满足题目限制\n");
        }
        return sb.toString();
    }

    @Tool(
            name = "run_custom_test",
            value = "用自定义输入测试用户代码，同时执行标准答案对比输出。用于验证特定边界情况或寻找反例。"
    )
    public String runCustomTest(
            @P("题目ID，Long类型") Long questionId,
            @P("用户提交的代码内容") String code,
            @P("代码语言，例如 java / python / cpp") String language,
            @P("自定义测试输入") String customInput
    ) {
        try {
            CustomTestResultDTO result = judgeService.runCustomTest(questionId, code, language, customInput);
            if (result.getErrorMsg() != null && result.getUserOutput() == null) {
                return "测试执行失败：" + result.getErrorMsg();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("自定义测试结果：\n");
            sb.append("输入：").append(customInput).append("\n");
            sb.append("用户代码输出：").append(result.getUserOutput() != null ? result.getUserOutput() : "（无输出）").append("\n");
            sb.append("标准答案输出：").append(result.getExpectedOutput() != null ? result.getExpectedOutput() : "（无法获取）").append("\n");
            sb.append("结果：").append(result.isMatch() ? "一致 ✓" : "不一致 ✗").append("\n");
            if (!result.isMatch() && result.getErrorMsg() != null) {
                sb.append("说明：").append(result.getErrorMsg()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "自定义测试执行异常：" + e.getMessage();
        }
    }

    @Tool(
            name = "diagnose_error_pattern",
            value = "分析当前用户的错题模式，按错误类型和知识点维度统计，识别系统性薄弱环节。userId从上下文信息中获取。"
    )
    public String diagnoseErrorPattern(
            @P("用户ID，从上下文信息中获取，Long类型") Long userId
    ) {
        Long resolvedUserId = userId != null ? userId : CURRENT_USER_ID.get();
        if (resolvedUserId == null) {
            return "无法获取当前用户信息，请重新登录。";
        }
        List<WrongQuestionVO> wrongList = aiWrongQuestionService.listMyWrongQuestions(resolvedUserId);
        if (wrongList == null || wrongList.isEmpty()) {
            return "暂无错题记录，无法进行错误模式诊断。";
        }

        Map<String, Long> errorTypeCount = wrongList.stream()
                .filter(w -> {
                    String raw = w.getWrongJudgeResult();
                    return raw != null && !raw.isBlank() && !raw.equalsIgnoreCase("Accepted");
                })
                .collect(Collectors.groupingBy(
                        w -> w.getWrongJudgeResult().trim(),
                        Collectors.counting()));

        long totalWrong = errorTypeCount.values().stream().mapToLong(Long::longValue).sum();
        if (totalWrong == 0) {
            return "暂无失败的错题记录，无法进行错误模式诊断。";
        }

        StringBuilder sb = new StringBuilder("错误模式诊断报告：\n\n");
        sb.append("【错误类型分布】共 ").append(totalWrong).append(" 道错题\n");
        errorTypeCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("- %s: %d道\n", e.getKey(), e.getValue())));

        sb.append("\n【错题知识点分布】\n");
        Map<String, Long> tagCount = new java.util.HashMap<>();
        for (WrongQuestionVO w : wrongList) {
            Question q = questionService.getById(w.getQuestionId());
            if (q != null && q.getTags() != null) {
                try {
                    List<String> tags = cn.hutool.json.JSONUtil.toList(q.getTags(), String.class);
                    for (String tag : tags) {
                        tagCount.merge(tag, 1L, Long::sum);
                    }
                } catch (Exception ignored) {}
            }
        }
        tagCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("- %s: %d道错题\n", e.getKey(), e.getValue())));

        String ragContext = ojKnowledgeRetriever.retrieveByType(
                "常见编程错误模式 " + String.join(" ", errorTypeCount.keySet()),
                "错题分析", 3, 0.7);
        if (ragContext != null && !ragContext.equals("无相关知识点")) {
            sb.append("\n【相关知识库参考】\n").append(ragContext).append("\n");
        }

        return sb.toString();
    }
}

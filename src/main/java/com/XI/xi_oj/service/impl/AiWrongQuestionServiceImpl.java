package com.XI.xi_oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.ai.observability.AiObservationModule;
import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.mapper.AiWrongQuestionMapper;
import com.XI.xi_oj.model.dto.question.WrongQuestionContext;
import com.XI.xi_oj.model.dto.question.WrongQuestionVO;
import com.XI.xi_oj.model.entity.AiWrongQuestion;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.service.AiWrongQuestionService;
import com.XI.xi_oj.service.QuestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiWrongQuestionServiceImpl implements AiWrongQuestionService {

    private static final String DEFAULT_WRONG_ANALYSIS_PROMPT = """
            你是一位编程学习辅导专家，请基于错题上下文输出结构化分析：
            1. 【错误分类】明确归类错误类型：
               - 逻辑错误（算法思路偏差）
               - 边界遗漏（未处理空输入、最大值、特殊字符等）
               - 复杂度超限（时间/空间复杂度不满足要求）
               - 格式错误（输出格式不符合要求）
               - 运行时错误（数组越界、空指针、栈溢出等）
            2. 【错误根因】精确定位代码中的问题行或逻辑段，解释为什么会出错；
            3. 【反例推测】推测可能导致错误的边界输入场景（如空数组、全相同元素、极大值等）；
            4. 【修正步骤】给出可执行的修正步骤，不直接给出完整正确代码；
            5. 【解题套路】总结这类题的通用解题套路与排错清单；
            6. 【针对性练习】基于错误类型推荐 2-3 道同类型练习题（如有相似题信息）；
            7. 【复习建议】结合遗忘曲线给出 1 天 / 3 天 / 7 天复习建议。
            回答语言：中文，表达清晰，适合初学者。
            """;

    @Resource
    private AiWrongQuestionMapper aiWrongQuestionMapper;

    @Resource
    private QuestionService questionService;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private AiObservationRecorder aiObservationRecorder;

    @Override
    public List<WrongQuestionVO> listMyWrongQuestions(Long userId) {
        List<AiWrongQuestion> rows = aiWrongQuestionMapper.selectListByUser(userId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(WrongQuestionVO::objToVo).collect(Collectors.toList());
    }

    @Override
    public List<WrongQuestionVO> listDueReviewQuestions(Long userId) {
        List<AiWrongQuestion> rows = aiWrongQuestionMapper.selectDueReviewList(userId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(WrongQuestionVO::objToVo).collect(Collectors.toList());
    }

    @Override
    public String analyzeWrongQuestion(Long userId, Long wrongQuestionId) {
        long start = System.currentTimeMillis();
        try {
            AiWrongQuestion wrong = requireOwnedWrongQuestion(userId, wrongQuestionId);
            WrongQuestionContext context = buildContext(userId, wrong);
            List<Long> similarQuestionIds = findSimilarQuestionIds(context);
            String ragContext = ojKnowledgeRetriever.retrieveByType(
                    buildRagQuery(context),
                    "错题分析",
                    3,
                    0.7
            );
            String prompt = buildPrompt(context, ragContext, formatSimilarQuestions(similarQuestionIds));
            String answer = aiModelHolder.getChatModel().chat(prompt);
            persistAnalysis(wrong, answer, similarQuestionIds);
            aiObservationRecorder.recordCall(AiObservationModule.WRONG_QUESTION, userId, null,
                    System.currentTimeMillis() - start, true);
            return answer;
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.WRONG_QUESTION, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public Flux<String> analyzeWrongQuestionStream(Long userId, Long wrongQuestionId) {
        long start = System.currentTimeMillis();
        try {
            AiWrongQuestion wrong = requireOwnedWrongQuestion(userId, wrongQuestionId);
            WrongQuestionContext context = buildContext(userId, wrong);
            List<Long> similarQuestionIds = findSimilarQuestionIds(context);
            String ragContext = ojKnowledgeRetriever.retrieveByType(
                    buildRagQuery(context),
                    "错题分析",
                    3,
                    0.7
            );
            String prompt = buildPrompt(context, ragContext, formatSimilarQuestions(similarQuestionIds));

            StringBuilder buffer = new StringBuilder();
            return aiModelHolder.getOjStreamingService().stream(prompt)
                    .doOnNext(buffer::append)
                    .doOnComplete(() -> {
                        persistAnalysis(wrong, buffer.toString(), similarQuestionIds);
                        aiObservationRecorder.recordCall(AiObservationModule.WRONG_QUESTION, userId, null,
                                System.currentTimeMillis() - start, true);
                    })
                    .doOnError(e -> {
                        aiObservationRecorder.recordCall(AiObservationModule.WRONG_QUESTION, userId, null,
                                System.currentTimeMillis() - start, false);
                        log.error("[AI Wrong] stream analyze failed, wrongQuestionId={}", wrongQuestionId, e);
                    });
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.WRONG_QUESTION, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public void markReviewed(Long userId, Long wrongQuestionId) {
        AiWrongQuestion wrong = requireOwnedWrongQuestion(userId, wrongQuestionId);
        int oldCount = wrong.getReviewCount() == null ? 0 : wrong.getReviewCount();
        int newCount = oldCount + 1;
        wrong.setIsReviewed(1);
        wrong.setReviewCount(newCount);
        wrong.setNextReviewTime(calcNextReviewTime(newCount));
        aiWrongQuestionMapper.updateById(wrong);
    }

    private AiWrongQuestion requireOwnedWrongQuestion(Long userId, Long wrongQuestionId) {
        AiWrongQuestion wrong = aiWrongQuestionMapper.selectByIdAndUser(wrongQuestionId, userId);
        if (wrong == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "错题记录不存在");
        }
        return wrong;
    }

    private WrongQuestionContext buildContext(Long userId, AiWrongQuestion wrong) {
        Question question = questionService.getById(wrong.getQuestionId());
        if (question == null || Objects.equals(question.getIsDelete(), 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在或已删除");
        }

        return WrongQuestionContext.builder()
                .wrongQuestionId(wrong.getId())
                .questionId(question.getId())
                .title(question.getTitle())
                .content(question.getContent())
                .tags(question.getTags())
                .difficulty(defaultIfBlank(question.getDifficulty(), "未知"))
                .wrongCode(wrong.getWrongCode())
                .language(defaultIfBlank(wrong.getLanguage(), "未知"))
                .wrongJudgeResult(wrong.getWrongJudgeResult())
                .errorMsg("无详细错误信息")
                .userId(userId)
                .build();
    }

    private List<Long> findSimilarQuestionIds(WrongQuestionContext context) {
        String difficulty = "未知".equals(context.getDifficulty()) ? null : context.getDifficulty();
        List<Long> ids = ojKnowledgeRetriever.retrieveSimilarQuestions(
                context.getQuestionId(),
                context.getContent(),
                difficulty
        );
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream().limit(4).collect(Collectors.toList());
    }

    private String buildRagQuery(WrongQuestionContext context) {
        return String.join(" ",
                safeText(context.getTitle()),
                safeText(context.getTags()),
                safeText(context.getWrongJudgeResult()));
    }

    private String formatSimilarQuestions(List<Long> similarQuestionIds) {
        if (similarQuestionIds == null || similarQuestionIds.isEmpty()) {
            return "暂无推荐";
        }
        List<Question> questions = questionService.listByIds(similarQuestionIds);
        Map<Long, String> titleMap = new LinkedHashMap<>();
        for (Question question : questions) {
            titleMap.put(question.getId(), question.getTitle());
        }
        return similarQuestionIds.stream()
                .map(id -> id + " - " + titleMap.getOrDefault(id, "未知标题"))
                .collect(Collectors.joining("\n"));
    }

    private String buildPrompt(WrongQuestionContext context, String ragContext, String similarQuestionText) {
        String prompt = aiConfigService.getPrompt("ai.prompt.wrong_analysis", DEFAULT_WRONG_ANALYSIS_PROMPT);
        return prompt + "\n\n" + String.format("""
                【当前错题信息】
                题目ID：%d
                标题：%s
                题干：%s
                考点：%s
                难度：%s

                【用户错误代码】
                语言：%s
                %s

                【错误判题结果】
                状态：%s
                错误信息：%s

                【RAG检索的典型错误分析】
                %s

                【可参考的同类题目】
                %s

                请完成：
                1. 详细分析该代码错误原因，指出关键逻辑问题或边界遗漏；
                2. 给出分步骤修正思路，不直接给完整正确代码；
                3. 给出简短复习计划（建议包含1天、3天、7天复习节奏）；
                4. 最后用3条以内总结本题最容易踩坑点。
                """,
                context.getQuestionId(),
                safeText(context.getTitle()),
                safeText(context.getContent()),
                safeText(context.getTags()),
                safeText(context.getDifficulty()),
                safeText(context.getLanguage()),
                safeText(context.getWrongCode()),
                safeText(context.getWrongJudgeResult()),
                safeText(context.getErrorMsg()),
                safeText(ragContext),
                safeText(similarQuestionText)
        );
    }

    private void persistAnalysis(AiWrongQuestion wrong, String analysis, List<Long> similarQuestionIds) {
        wrong.setWrongAnalysis(analysis);
        wrong.setReviewPlan("建议按 1 天、3 天、7 天节奏复习同类题型。");
        wrong.setSimilarQuestions(JSONUtil.toJsonStr(similarQuestionIds));
        if (wrong.getReviewCount() == null || wrong.getReviewCount() == 0) {
            wrong.setIsReviewed(0);
            wrong.setReviewCount(0);
            wrong.setNextReviewTime(calcNextReviewTime(1));
        }
        aiWrongQuestionMapper.updateById(wrong);
    }

    private Date calcNextReviewTime(int reviewCount) {
        int days;
        if (reviewCount <= 1) {
            days = 1;
        } else if (reviewCount == 2) {
            days = 3;
        } else {
            days = 7;
        }
        LocalDateTime next = LocalDateTime.now().plusDays(days);
        return Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

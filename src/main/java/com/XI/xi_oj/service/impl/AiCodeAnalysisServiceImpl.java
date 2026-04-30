package com.XI.xi_oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.ai.observability.AiObservationModule;
import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.judge.codesandbox.model.JudgeInfo;
import com.XI.xi_oj.mapper.AiCodeAnalysisMapper;
import com.XI.xi_oj.model.dto.judge.AiCodeAnalysisRequest;
import com.XI.xi_oj.model.dto.judge.CodeAnalysisContext;
import com.XI.xi_oj.model.entity.AiCodeAnalysis;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import com.XI.xi_oj.model.enums.QuestionSubmitStatusEnum;
import com.XI.xi_oj.service.AiCodeAnalysisService;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.service.QuestionService;
import com.XI.xi_oj.service.QuestionSubmitService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiCodeAnalysisServiceImpl implements AiCodeAnalysisService {

    private static final String DEFAULT_CODE_ANALYSIS_PROMPT = """
            你是一位资深 Java/算法教学助手，请对以下代码进行多维度分析：
            1. 代码风格与规范评分（10分制）；
            2. 逻辑正确性与边界情况；
            3. 时间/空间复杂度分析；
            4. 针对错误给出改进建议，不直接给出完整答案。
            回答语言：中文，格式清晰，适合编程初学者。
            """;

    private static final Pattern SCORE_PATTERN_1 = Pattern.compile("(?i)(?:综合|总体|总评|最终)\\s*评分\\s*[:：]?\\s*(\\d{1,2})");
    private static final Pattern SCORE_PATTERN_2 = Pattern.compile("(\\d{1,2})\\s*/\\s*10");

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AiCodeAnalysisMapper aiCodeAnalysisMapper;

    @Resource
    private AiObservationRecorder aiObservationRecorder;

    @Override
    public String analyzeCode(Long userId, AiCodeAnalysisRequest request) {
        long start = System.currentTimeMillis();
        try {
            CodeAnalysisContext context = buildContext(userId, request);
            String prompt = buildPrompt(context);
            String analysis = aiModelHolder.getChatModel().chat(prompt);
            saveAnalysis(context, analysis);
            aiObservationRecorder.recordCall(AiObservationModule.CODE_ANALYSIS, userId, null,
                    System.currentTimeMillis() - start, true);
            return analysis;
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.CODE_ANALYSIS, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public Flux<String> analyzeCodeStream(Long userId, Long questionId, Long questionSubmitId) {
        long start = System.currentTimeMillis();
        AiCodeAnalysisRequest request = new AiCodeAnalysisRequest();
        try {
            request.setQuestionId(questionId);
            request.setQuestionSubmitId(questionSubmitId);
            CodeAnalysisContext context = buildContext(userId, request);
            String prompt = buildPrompt(context);

            StringBuilder buffer = new StringBuilder();
            return aiModelHolder.getOjStreamingService().stream(prompt)
                    .doOnNext(token -> buffer.append(token == null ? "" : token))
                    .doOnComplete(() -> {
                        saveAnalysis(context, buffer.toString());
                        aiObservationRecorder.recordCall(AiObservationModule.CODE_ANALYSIS, userId, null,
                                System.currentTimeMillis() - start, true);
                    })
                    .doOnError(e -> {
                        aiObservationRecorder.recordCall(AiObservationModule.CODE_ANALYSIS, userId, null,
                                System.currentTimeMillis() - start, false);
                        log.error("[AI Code] stream analyze failed, userId={}, questionId={}, submitId={}",
                                userId, questionId, questionSubmitId, e);
                    });
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.CODE_ANALYSIS, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public List<AiCodeAnalysis> listMyHistory(Long userId, Long questionId, Integer pageSize) {
        int limit = (pageSize == null || pageSize <= 0) ? 20 : Math.min(pageSize, 50);
        QueryWrapper<AiCodeAnalysis> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq(questionId != null, "question_id", questionId);
        queryWrapper.orderByDesc("createTime").orderByDesc("id");
        queryWrapper.last("limit " + limit);
        List<AiCodeAnalysis> records = aiCodeAnalysisMapper.selectList(queryWrapper);
        return records == null ? Collections.emptyList() : records;
    }

    private CodeAnalysisContext buildContext(Long userId, AiCodeAnalysisRequest request) {
        if (request == null || request.getQuestionId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "questionId 不能为空");
        }
        Question question = questionService.getById(request.getQuestionId());
        if (question == null || Integer.valueOf(1).equals(question.getIsDelete())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        String code;
        String language;
        String judgeStatus;
        String errorMsg;

        if (request.getQuestionSubmitId() != null) {
            QuestionSubmit submit = questionSubmitService.getById(request.getQuestionSubmitId());
            if (submit == null || Integer.valueOf(1).equals(submit.getIsDelete())) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
            }
            if (!Objects.equals(submit.getUserId(), userId)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该提交记录");
            }
            if (!Objects.equals(submit.getQuestionId(), request.getQuestionId())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "questionId 与 questionSubmitId 不匹配");
            }

            code = submit.getCode();
            language = submit.getLanguage();
            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
            judgeStatus = resolveJudgeStatus(submit, judgeInfo, request.getJudgeStatus());
            errorMsg = resolveErrorMsg(judgeStatus, judgeInfo, request.getErrorMsg());
        } else {
            code = request.getCode();
            language = request.getLanguage();
            if (isBlank(code) || isBlank(language)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不传 questionSubmitId 时，code 和 language 不能为空");
            }
            judgeStatus = defaultIfBlank(request.getJudgeStatus(), "未知");
            errorMsg = defaultIfBlank(request.getErrorMsg(), "无");
        }

        return CodeAnalysisContext.builder()
                .questionId(question.getId())
                .title(safe(question.getTitle()))
                .content(safe(question.getContent()))
                .tags(formatTags(question.getTags()))
                .difficulty(defaultIfBlank(question.getDifficulty(), "未知"))
                .answer(safe(question.getAnswer()))
                .userCode(safe(code))
                .language(safe(language))
                .judgeStatus(defaultIfBlank(judgeStatus, "未知"))
                .errorMsg(defaultIfBlank(errorMsg, "无"))
                .userId(userId)
                .build();
    }

    private String buildPrompt(CodeAnalysisContext context) {
        String prompt = aiConfigService.getPrompt("ai.prompt.code_analysis", DEFAULT_CODE_ANALYSIS_PROMPT);
        String ragContext = ojKnowledgeRetriever.retrieveByType(
                context.getTitle() + " " + context.getTags() + " " + context.getDifficulty(),
                "代码模板,错题分析",
                3,
                0.7
        );

        return prompt + "\n\n" + String.format("""
                【当前题目信息】
                标题：%s
                题干：%s
                考点：%s
                难度：%s
                标准答案：%s

                【用户提交代码】
                语言：%s
                代码内容：
                %s

                【判题结果】
                状态：%s
                错误信息：%s

                【相关知识点参考】
                %s

                请你完成以下分析：
                1. 代码风格与规范评分（10分制），列出优点与改进建议；
                2. 代码质量与可读性评分（10分制），分析逻辑优缺点；
                3. 针对判题结果，说明错误原因并给出修正思路，不直接提供完整正确代码；
                4. 结合题目考点，给出优化方向与学习建议。
                回答格式清晰、分点说明、语言通俗，适配编程新手。
                """,
                context.getTitle(),
                context.getContent(),
                context.getTags(),
                context.getDifficulty(),
                context.getAnswer(),
                context.getLanguage(),
                context.getUserCode(),
                context.getJudgeStatus(),
                context.getErrorMsg(),
                safe(ragContext)
        );
    }

    private void saveAnalysis(CodeAnalysisContext context, String analysis) {
        AiCodeAnalysis record = new AiCodeAnalysis();
        record.setUserId(context.getUserId());
        record.setQuestionId(context.getQuestionId());
        record.setCode(context.getUserCode());
        record.setLanguage(context.getLanguage());
        record.setJudgeResult(context.getJudgeStatus());
        record.setAnalysisResult(analysis);
        record.setScore(extractScore(analysis));
        aiCodeAnalysisMapper.insert(record);
    }

    private Integer extractScore(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return null;
        }
        Matcher matcher1 = SCORE_PATTERN_1.matcher(analysis);
        if (matcher1.find()) {
            Integer score = parseScore(matcher1.group(1));
            if (score != null) {
                return score;
            }
        }
        Matcher matcher2 = SCORE_PATTERN_2.matcher(analysis);
        if (matcher2.find()) {
            return parseScore(matcher2.group(1));
        }
        return null;
    }

    private Integer parseScore(String value) {
        try {
            int score = Integer.parseInt(value);
            if (score >= 0 && score <= 10) {
                return score;
            }
        } catch (Exception e) {
            log.debug("[AI Code] parse score failed, value={}", value, e);
        }
        return null;
    }

    private JudgeInfo parseJudgeInfo(String judgeInfoJson) {
        if (isBlank(judgeInfoJson)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoJson, JudgeInfo.class);
        } catch (Exception e) {
            log.warn("[AI Code] parse judgeInfo failed, raw={}", judgeInfoJson, e);
            return null;
        }
    }

    private String resolveJudgeStatus(QuestionSubmit submit, JudgeInfo judgeInfo, String requestStatus) {
        if (judgeInfo != null && !isBlank(judgeInfo.getMessage())) {
            return judgeInfo.getMessage();
        }
        if (!isBlank(requestStatus)) {
            return requestStatus;
        }
        if (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(submit.getStatus())) {
            return "Accepted";
        }
        if (QuestionSubmitStatusEnum.FAILED.getValue().equals(submit.getStatus())) {
            return "Failed";
        }
        if (QuestionSubmitStatusEnum.RUNNING.getValue().equals(submit.getStatus())) {
            return "Running";
        }
        return "Waiting";
    }

    private String resolveErrorMsg(String judgeStatus, JudgeInfo judgeInfo, String requestErrorMsg) {
        if ("Accepted".equalsIgnoreCase(judgeStatus) || "成功".equals(judgeStatus)) {
            return "无";
        }
        if (judgeInfo != null && !isBlank(judgeInfo.getMessage())) {
            return judgeInfo.getMessage();
        }
        return defaultIfBlank(requestErrorMsg, "无");
    }

    private String formatTags(String tagsJson) {
        if (isBlank(tagsJson)) {
            return "无";
        }
        try {
            List<String> tags = JSONUtil.toList(tagsJson, String.class);
            if (tags == null || tags.isEmpty()) {
                return "无";
            }
            return tags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .collect(Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("[AI Code] parse tags failed, rawTags={}", tagsJson, e);
            return tagsJson;
        }
    }

    private String safe(String value) {
        return isBlank(value) ? "无" : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

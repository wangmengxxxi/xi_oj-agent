package com.XI.xi_oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.ai.observability.AiObservationModule;
import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.model.dto.question.AiQuestionParseResponse;import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.service.AiQuestionParseService;
import com.XI.xi_oj.service.QuestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiQuestionParseServiceImpl implements AiQuestionParseService {

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    @Resource
    private QuestionService questionService;

    @Resource
    private AiObservationRecorder aiObservationRecorder;

    @Override
    public AiQuestionParseResponse parseQuestion(Long userId, Long questionId) {
        long start = System.currentTimeMillis();
        try {
            Question question = requireQuestion(questionId);
            String context = buildQuestionContext(question);
            String analysis = aiModelHolder.getOjQuestionParseAgent().parse(context);
            List<Long> similarQuestionIds = getSimilarQuestionIds(question);
            log.info("[AI Question Parse] parsed questionId={}, userId={}, similarCount={}",
                    questionId, userId, similarQuestionIds.size());
            aiObservationRecorder.recordCall(AiObservationModule.QUESTION_PARSE, userId, null,
                    System.currentTimeMillis() - start, true);
            return AiQuestionParseResponse.builder()
                    .questionId(questionId)
                    .analysis(analysis)
                    .similarQuestionIds(similarQuestionIds)
                    .build();
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.QUESTION_PARSE, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public Flux<String> parseQuestionStream(Long userId, Long questionId) {
        long start = System.currentTimeMillis();
        try {
            Question question = requireQuestion(questionId);
            String context = buildQuestionContext(question);
            log.info("[AI Question Parse] stream parse start, questionId={}, userId={}", questionId, userId);
            return aiModelHolder.getOjQuestionParseAgent().parseStream(context)
                    .doOnComplete(() -> aiObservationRecorder.recordCall(AiObservationModule.QUESTION_PARSE, userId, null,
                            System.currentTimeMillis() - start, true))
                    .doOnError(e -> {
                        aiObservationRecorder.recordCall(AiObservationModule.QUESTION_PARSE, userId, null,
                                System.currentTimeMillis() - start, false);
                        log.error("[AI Question Parse] stream parse failed, questionId={}, userId={}",
                                questionId, userId, e);
                    });
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.QUESTION_PARSE, userId, null,
                    System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    @Override
    public List<Long> listSimilarQuestionIds(Long userId, Long questionId) {
        Question question = requireQuestion(questionId);
        List<Long> similarQuestionIds = getSimilarQuestionIds(question);
        log.info("[AI Question Parse] similar questions queried, questionId={}, userId={}, similarCount={}",
                questionId, userId, similarQuestionIds.size());
        return similarQuestionIds;
    }

    private Question requireQuestion(Long questionId) {
        Question question = questionService.getById(questionId);
        if (question == null || Integer.valueOf(1).equals(question.getIsDelete())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        return question;
    }

    private List<Long> getSimilarQuestionIds(Question question) {
        List<Long> similarQuestionIds = ojKnowledgeRetriever.retrieveSimilarQuestions(
                question.getId(),
                buildSimilarityQuery(question),
                question.getDifficulty()
        );
        if (similarQuestionIds == null || similarQuestionIds.isEmpty()) {
            return Collections.emptyList();
        }
        return similarQuestionIds;
    }

    private String buildQuestionContext(Question question) {
        return String.format("""
                当前题目信息：
                题目ID：%d
                标题：%s
                题干：%s
                标签：%s
                难度：%s
                """,
                question.getId(),
                safe(question.getTitle()),
                safe(question.getContent()),
                formatTags(question.getTags()),
                defaultIfBlank(question.getDifficulty(), "未知")
        );
    }

    private String buildSimilarityQuery(Question question) {
        return String.join("\n",
                "题目标题：" + safe(question.getTitle()),
                "题目内容：" + safe(question.getContent()),
                "题目标签：" + formatTags(question.getTags()),
                "题目难度：" + defaultIfBlank(question.getDifficulty(), "未知"));
    }

    private String formatTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
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
            log.warn("[AI Question Parse] parse tags failed, rawTags={}", tagsJson, e);
            return tagsJson;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

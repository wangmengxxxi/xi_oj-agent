package com.XI.xi_oj.service.impl;

import com.XI.xi_oj.mapper.AiWrongQuestionMapper;
import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;
import com.XI.xi_oj.model.entity.AiWrongQuestion;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WrongQuestionCollector {

    @Resource
    private AiWrongQuestionMapper wrongQuestionMapper;

    /**
     * 自动收集错题：
     * - source=ai_tool 的测试提交不入错题本
     * - Accepted 不入错题本
     * - 同一用户同一题已存在则更新，不重复新增
     */
    public void collect(Long userId, Long questionId, String code, String language,
                        JudgeResultDTO judgeResult, String source) {
        if ("ai_tool".equalsIgnoreCase(source)) {
            return;
        }
        if (judgeResult == null) {
            return;
        }
        String status = judgeResult.getStatus();
        if (isAccepted(status)) {
            return;
        }
        try {
            AiWrongQuestion existing = wrongQuestionMapper.selectByUserAndQuestion(userId, questionId);
            if (existing != null) {
                existing.setWrongCode(code);
                existing.setLanguage(language);
                existing.setWrongJudgeResult(status);
                existing.setIsReviewed(0);
                wrongQuestionMapper.updateById(existing);
                log.info("[WrongQuestion] updated userId={}, questionId={}, status={}", userId, questionId, status);
            } else {
                AiWrongQuestion wrong = new AiWrongQuestion();
                wrong.setUserId(userId);
                wrong.setQuestionId(questionId);
                wrong.setWrongCode(code);
                wrong.setLanguage(language);
                wrong.setWrongJudgeResult(status);
                wrong.setIsReviewed(0);
                wrong.setReviewCount(0);
                wrongQuestionMapper.insert(wrong);
                log.info("[WrongQuestion] created userId={}, questionId={}, status={}", userId, questionId, status);
            }
        } catch (Exception e) {
            // 错题收集失败不影响主判题流程
            log.error("[WrongQuestion] collect failed userId={}, questionId={}", userId, questionId, e);
        }
    }

    private boolean isAccepted(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return normalized.equals("accepted")
                || normalized.contains("accept")
                || status.contains("成功");
    }
}

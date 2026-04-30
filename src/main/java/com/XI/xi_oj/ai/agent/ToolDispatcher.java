package com.XI.xi_oj.ai.agent;

import com.XI.xi_oj.ai.tools.OJTools;
import com.XI.xi_oj.service.AiConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class ToolDispatcher {

    @Resource
    private OJTools ojTools;

    @Resource
    private AiConfigService aiConfigService;

    public ToolResult execute(String toolName, Map<String, Object> params) {
        int maxRetry = intConfig("ai.agent.tool_max_retry", 2);
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                String output = doExecute(toolName, params);
                return ToolResult.success(output, attempt);
            } catch (Exception e) {
                log.warn("[AgentTool] failed, tool={}, attempt={}/{}", toolName, attempt + 1, maxRetry + 1, e);
                if (attempt >= maxRetry) {
                    return ToolResult.failure("工具调用失败：" + e.getMessage(), attempt);
                }
                sleep(500L * (attempt + 1));
            }
        }
        return ToolResult.failure("工具调用失败：超过最大重试次数", maxRetry);
    }

    private String doExecute(String toolName, Map<String, Object> params) {
        return switch (toolName) {
            case "query_question_info" -> ojTools.queryQuestionInfo(str(params.get("keyword")));
            case "judge_user_code" -> ojTools.judgeUserCode(
                    toLong(params.get("userId")),
                    toLong(params.get("questionId")),
                    str(params.get("code")),
                    str(params.get("language")));
            case "query_user_wrong_question" -> ojTools.queryUserWrongQuestion(
                    toLong(params.get("userId")),
                    toLong(params.get("questionId")));
            case "search_questions" -> ojTools.searchQuestions(
                    str(params.get("keyword")),
                    str(params.get("tag")),
                    str(params.get("difficulty")));
            case "find_similar_questions" -> ojTools.findSimilarQuestions(toLong(params.get("questionId")));
            case "list_user_wrong_questions" -> ojTools.listUserWrongQuestions(toLong(params.get("userId")));
            case "query_user_submit_history" -> ojTools.queryUserSubmitHistory(
                    toLong(params.get("userId")),
                    toLong(params.get("questionId")));
            case "query_user_mastery" -> ojTools.queryUserMastery(toLong(params.get("userId")));
            case "get_question_hints" -> ojTools.getQuestionHints(
                    toLong(params.get("questionId")),
                    toInt(params.get("hintLevel"), 1));
            case "run_custom_test" -> ojTools.runCustomTest(
                    toLong(params.get("questionId")),
                    str(params.get("code")),
                    str(params.get("language")),
                    str(params.get("customInput")));
            case "diagnose_error_pattern" -> ojTools.diagnoseErrorPattern(toLong(params.get("userId")));
            case "recommend_learning_path" -> ojTools.recommendLearningPath(toLong(params.get("userId")));
            default -> throw new IllegalArgumentException("未知工具：" + toolName);
        };
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int toInt(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = aiConfigService.getConfigValue(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record ToolResult(String output, boolean success, int retryCount) {
        static ToolResult success(String output, int retryCount) {
            return new ToolResult(output, true, retryCount);
        }

        static ToolResult failure(String output, int retryCount) {
            return new ToolResult(output, false, retryCount);
        }
    }
}

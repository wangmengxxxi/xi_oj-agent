package com.XI.xi_oj.ai.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图预路由器：在进入模型自由决策前，对高置信度意图直接路由到对应工具。
 */
@Component
public class IntentRouter {

    private static final Pattern QUESTION_ID_PATTERN =
            Pattern.compile("(?:当前题目ID|题目ID|题目|题号|#|questionId)\\s*[：:=#]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```");

    public record Route(String toolName, boolean requiresQuestionId) {
    }

    public String tryRoute(String userQuery) {
        Route route = route(userQuery);
        return route == null ? null : route.toolName();
    }

    public Route route(String userQuery) {
        String normalized = stripContext(userQuery);
        boolean hasQuestionId = extractQuestionId(userQuery) != null;

        if (matchesWrongQuestionDetail(normalized) && hasQuestionId) {
            return new Route("query_user_wrong_question", true);
        }

        if (hasSpecificTarget(normalized)) {
            return null;
        }

        if (matchesWrongQuestionList(normalized)) {
            return new Route("list_user_wrong_questions", false);
        }
        if (matchesLearningPath(normalized)) {
            return new Route("recommend_learning_path", false);
        }
        return null;
    }

    public Long extractQuestionId(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher matcher = QUESTION_ID_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean matchesLearningPath(String q) {
        if (containsAny(q, "学习路径", "学习计划", "推荐练习", "该练什么", "该做什么题")) {
            return true;
        }
        if (contains(q, "薄弱") && containsAny(q, "分析", "诊断", "推荐", "练习", "怎么提升", "提升")) {
            return true;
        }
        if (containsAny(q, "弱点", "弱项", "短板") && containsAny(q, "推荐", "练习", "提升")) {
            return true;
        }
        return containsAny(q, "哪里薄弱", "哪里不行", "哪里不好", "我哪儿薄弱");
    }

    private boolean matchesWrongQuestionList(String q) {
        return containsAny(q, "错题本", "错题列表", "所有错题", "我的错题", "有哪些错题", "错题记录")
                || (contains(q, "错题") && containsAny(q, "查询", "查一下", "看看", "列出", "列表", "汇总"));
    }

    private boolean matchesWrongQuestionDetail(String q) {
        return contains(q, "错题")
                && (containsAny(q, "这道题", "当前题", "本题")
                || QUESTION_ID_PATTERN.matcher(q).find());
    }

    private boolean hasSpecificTarget(String q) {
        return QUESTION_ID_PATTERN.matcher(q).find() || CODE_BLOCK_PATTERN.matcher(q).find();
    }

    private String stripContext(String q) {
        if (q == null) {
            return "";
        }
        if (q.startsWith("【上下文信息】") || q.startsWith("銆愪笂涓嬫枃淇℃伅銆?")) {
            int idx = q.indexOf("\n");
            if (idx > 0) {
                return q.substring(idx + 1).trim();
            }
        }
        return q.trim();
    }

    private boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}

package com.XI.xi_oj.ai.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 意图预路由器：在进入 Agent ReAct Loop 之前，对高置信度意图直接路由到对应工具。
 * 命中时跳过 agent 推理，确保工具一定被调用；未命中时走正常 agent loop。
 */
@Component
public class IntentRouter {

    private static final Pattern QUESTION_ID_PATTERN = Pattern.compile("(?:题目|题号|#)\\s*\\d+");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```");

    public String tryRoute(String userQuery) {
        String normalized = stripContext(userQuery);
        if (hasSpecificTarget(normalized)) {
            return null;
        }
        if (matchesLearningPath(normalized)) {
            return "recommend_learning_path";
        }
        return null;
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
        if (containsAny(q, "哪里薄弱", "哪里不行", "哪里不好", "我哪里薄弱")) {
            return true;
        }
        return false;
    }

    private boolean hasSpecificTarget(String q) {
        return QUESTION_ID_PATTERN.matcher(q).find() || CODE_BLOCK_PATTERN.matcher(q).find();
    }

    private String stripContext(String q) {
        if (q.startsWith("【上下文信息】")) {
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

package com.XI.xi_oj.model.enums;
/**
 * 限流类型枚举
 */
public enum RateLimitTypeEnum {
    /** 全局秒级限流（保护代码沙箱） */
    GLOBAL_SECOND("submit:global:second"),
    /** IP 分钟级限流（防代理刷题） */
    IP_MINUTE("submit:ip:minute"),
    /** 用户分钟级限流（防用户快速连刷） */
    USER_MINUTE("submit:user:minute"),
    /** 用户每日限流（防日级刷量） */
    USER_DAY("submit:user:day"),
    /** 用户同题冷却（防同题重复提交） */
    USER_QUESTION_COOLDOWN("submit:user:question:cooldown"),

    /** AI接口 IP 分钟级限流（防代理滥用） */
    AI_IP_MINUTE("ai:ip:minute"),
    /** AI接口用户分钟级限流（全部AI功能共享，防突发调用） */
    AI_USER_MINUTE("ai:user:minute"),
    /** AI问答 用户每日限流 */
    AI_CHAT_USER_DAY("ai:chat:user:day"),
    /** AI代码分析 用户每日限流 */
    AI_CODE_USER_DAY("ai:code:user:day"),
    /** AI题目解析 用户每日限流 */
    AI_QUESTION_USER_DAY("ai:question:user:day"),
    /** AI错题分析 用户每日限流 */
    AI_WRONG_USER_DAY("ai:wrong:user:day"),
    /** AI全局令牌桶限流（保护AI API配额，所有用户共享） */
    AI_GLOBAL_TOKEN_BUCKET("ai:global:token_bucket");
    private final String ruleKey;
    RateLimitTypeEnum(String ruleKey) {
        this.ruleKey = ruleKey;
    }
    public String getRuleKey() {
        return ruleKey;
    }
}
package com.XI.xi_oj.aop;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.model.entity.RateLimitRule;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.model.enums.RateLimitTypeEnum;
import com.XI.xi_oj.service.RateLimitRuleService;
import com.XI.xi_oj.service.UserService;
import com.XI.xi_oj.utils.RateLimitRedisUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Aspect
@Component
@Slf4j
public class RateLimitInterceptor {

    @Resource
    private RateLimitRedisUtil rateLimitRedisUtil;

    @Resource
    private RateLimitRuleService rateLimitRuleService;
    @Resource
    private UserService userService;
    @Resource
    private AiObservationRecorder aiObservationRecorder;
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    @Around("@annotation(rateLimit)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        // 获取当前登录用户（题目提交必须登录，此处直接获取）
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();
        // 获取客户端真实 IP（Hutool Jakarta 版本工具）
        String clientIp = JakartaServletUtil.getClientIP(request);
        // 获取提交请求中的 questionId（从方法参数中提取）
        Long questionId = extractQuestionId(joinPoint);
        // 按 types() 声明的维度顺序依次检查限流
        for (RateLimitTypeEnum type : rateLimit.types()) {
            checkRateLimit(type, userId, clientIp, questionId, rateLimit.message());
        }
        return joinPoint.proceed();
    }
    /**
     * 按限流类型执行对应的 Redis 检查
     */
    private void checkRateLimit(RateLimitTypeEnum type, Long userId, String clientIp,
                                Long questionId, String customMessage) {
        RateLimitRule rule = rateLimitRuleService.getRuleByKey(type.getRuleKey());
        // 规则不存在或已禁用，直接放行
        if (rule == null || rule.getIs_enable() != 1) {
            return;
        }
        boolean allowed;
        String redisKey;
        switch (type) {
            case GLOBAL_SECOND -> {
                redisKey = "rl:global:submit";
                allowed = rateLimitRedisUtil.slidingWindowAllow(redisKey,
                        rule.getWindow_seconds(), rule.getLimit_count());
                if (!allowed) {
                    log.warn("[RateLimit] 全局限流触发，clientIp={}", clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage, "当前提交人数过多，请稍后再试"));
                }
            }
            case IP_MINUTE -> {
                redisKey = "rl:ip:" + clientIp + ":submit";
                allowed = rateLimitRedisUtil.slidingWindowAllow(redisKey,
                        rule.getWindow_seconds(), rule.getLimit_count());
                if (!allowed) {
                    log.warn("[RateLimit] IP限流触发，ip={}", clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage, "提交过于频繁，请稍后再试"));
                }
            }
            case USER_MINUTE -> {
                redisKey = "rl:user:" + userId + ":submit:min";
                allowed = rateLimitRedisUtil.slidingWindowAllow(redisKey,
                        rule.getWindow_seconds(), rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] 用户分钟级限流触发，userId={}", userId);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "提交太频繁了，每分钟最多提交 " + rule.getLimit_count() + " 次，请稍后再试"));
                }
            }
            case USER_DAY -> {
                String today = LocalDate.now().format(DAY_FORMATTER);
                redisKey = "rl:user:" + userId + ":submit:day:" + today;
                allowed = rateLimitRedisUtil.dailyCountAllow(redisKey, rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] 用户每日限流触发，userId={}", userId);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "今日提交次数已达上限（" + rule.getLimit_count() + " 次），明日再来吧"));
                }
            }
            case USER_QUESTION_COOLDOWN -> {
                if (questionId == null) {
                    break;
                }
                redisKey = "rl:user:" + userId + ":q:" + questionId + ":cd";
                allowed = rateLimitRedisUtil.cooldownAllow(redisKey, rule.getWindow_seconds());
                if (!allowed) {
                    log.info("[RateLimit] 同题冷却限流触发，userId={}，questionId={}", userId, questionId);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "同一题目提交太频繁，请等待 " + rule.getWindow_seconds() + " 秒后再试"));
                }
            }
            case AI_IP_MINUTE -> {
                redisKey = "rl:ip:" + clientIp + ":ai";
                allowed = rateLimitRedisUtil.slidingWindowAllow(redisKey,
                        rule.getWindow_seconds(), rule.getLimit_count());
                if (!allowed) {
                    log.warn("[RateLimit] AI IP限流触发，ip={}", clientIp);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage, "AI接口请求过于频繁，请稍后再试"));
                }
            }
            case AI_USER_MINUTE -> {
                redisKey = "rl:user:" + userId + ":ai:min";
                allowed = rateLimitRedisUtil.slidingWindowAllow(redisKey,
                        rule.getWindow_seconds(), rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] AI用户分钟级限流触发，userId={}", userId);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "AI调用太频繁，每分钟最多调用 " + rule.getLimit_count() + " 次，请稍后再试"));
                }
            }
            case AI_CHAT_USER_DAY -> {
                String today = LocalDate.now().format(DAY_FORMATTER);
                redisKey = "rl:user:" + userId + ":ai:chat:day:" + today;
                allowed = rateLimitRedisUtil.dailyCountAllow(redisKey, rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] AI问答每日限流触发，userId={}", userId);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "今日AI问答次数已达上限（" + rule.getLimit_count() + " 次），明日再来吧"));
                }
            }
            case AI_CODE_USER_DAY -> {
                String today = LocalDate.now().format(DAY_FORMATTER);
                redisKey = "rl:user:" + userId + ":ai:code:day:" + today;
                allowed = rateLimitRedisUtil.dailyCountAllow(redisKey, rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] AI代码分析每日限流触发，userId={}", userId);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "今日AI代码分析次数已达上限（" + rule.getLimit_count() + " 次），明日再来吧"));
                }
            }
            case AI_QUESTION_USER_DAY -> {
                String today = LocalDate.now().format(DAY_FORMATTER);
                redisKey = "rl:user:" + userId + ":ai:question:day:" + today;
                allowed = rateLimitRedisUtil.dailyCountAllow(redisKey, rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] AI题目解析每日限流触发，userId={}", userId);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "今日AI题目解析次数已达上限（" + rule.getLimit_count() + " 次），明日再来吧"));
                }
            }
            case AI_WRONG_USER_DAY -> {
                String today = LocalDate.now().format(DAY_FORMATTER);
                redisKey = "rl:user:" + userId + ":ai:wrong:day:" + today;
                allowed = rateLimitRedisUtil.dailyCountAllow(redisKey, rule.getLimit_count());
                if (!allowed) {
                    log.info("[RateLimit] AI错题分析每日限流触发，userId={}", userId);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage,
                                    "今日AI错题分析次数已达上限（" + rule.getLimit_count() + " 次），明日再来吧"));
                }
            }
            case AI_GLOBAL_TOKEN_BUCKET -> {
                redisKey = "rl:global:ai:bucket";
                allowed = rateLimitRedisUtil.tokenBucketAllow(redisKey,
                        rule.getLimit_count(), rule.getWindow_seconds());
                if (!allowed) {
                    log.warn("[RateLimit] AI全局令牌桶限流触发，clientIp={}", clientIp);
                    recordAiRateLimited(type, userId, clientIp);
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            buildMessage(customMessage, "AI系统繁忙，请稍后再试"));
                }
            }
            default -> log.warn("[RateLimit] 未知限流类型：{}", type);
        }
    }
    /**
     * 从方法参数中提取 questionId
     * 优先从 QuestionSubmitAddRequest 类型参数中获取
     */
    private Long extractQuestionId(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null) continue;
            try {
                // 利用反射兼容性获取 questionId 字段（不依赖具体类型，避免循环依赖）
                java.lang.reflect.Method getter = arg.getClass().getMethod("getQuestionId");
                Object value = getter.invoke(arg);
                if (value instanceof Long) {
                    return (Long) value;
                }
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (NoSuchMethodException ignored) {
                // 当前参数类型不含 getQuestionId，继续下一个参数
            } catch (Exception e) {
                log.warn("[RateLimit] 提取 questionId 异常", e);
            }
        }
        return null;
    }
    private String buildMessage(String customMessage, String defaultMessage) {
        return (customMessage != null && !customMessage.isBlank()) ? customMessage : defaultMessage;
    }

    private void recordAiRateLimited(RateLimitTypeEnum type, Long userId, String clientIp) {
        aiObservationRecorder.recordRateLimited(type.getRuleKey(), userId, "ip=" + clientIp);
    }
}

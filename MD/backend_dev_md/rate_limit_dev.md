# XI OJ OJ平台——题目提交限流功能设计与实施规划

文档版本：V1.0
适用范围：XI OJ OJ平台基础项目（代码沙箱开发完成后）
更新日期：2026年04月

---

## 一、概述与背景

### 1.1 功能定位

题目提交限流是基础项目（用户体系 + 题库管理 + 代码沙箱判题）完成后的第一个安全增强功能。  
代码沙箱资源开销高（CPU、内存隔离容器），不加限制的提交行为会导致：
- 沙箱被恶意刷题占满，正常用户无法提交；
- 判题队列堆积，系统响应变慢；
- 服务器资源瞬时打满，引发雪崩。

### 1.2 限流时机

```
基础项目开发顺序：
  用户体系 → 题库管理 → 代码沙箱 → [当前：题目提交限流] → AIGC功能 → AI接口限流
```

> AI接口限流为独立阶段，将在 AIGC 全部功能落地后，单独规划设计文档。

### 1.3 核心目标

1. 防止同一用户恶意刷题占用判题资源；
2. 防止相同题目被同一用户短时间内反复重复提交（同题冷却）；
3. 防止 IP 代理批量刷题；
4. 保障平台整体提交吞吐量可控；
5. 所有限流规则支持管理员动态调整，无需重启服务。

---

## 二、设计方案选型

### 2.1 限流维度规划

| 维度 | 说明 | 默认规则 | 场景 |
|------|------|----------|------|
| 用户-分钟级 | 同一用户每分钟最多提交N次（全题目汇总） | 5次/分钟 | 防止用户快速连刷 |
| 用户-每日 | 同一用户每日最多提交N次 | 200次/天 | 防止刷量占用资源 |
| 用户+题目冷却 | 同一用户对同一题目的提交冷却时间 | 30秒冷却 | 防止同题重复无效提交 |
| IP-分钟级 | 同一IP每分钟最多提交N次 | 10次/分钟 | 防代理批量刷题 |
| 全局-秒级 | 平台每秒最多处理N次提交（保护沙箱） | 50次/秒 | 防沙箱雪崩 |

### 2.2 算法选型：滑动窗口

| 算法 | 优点 | 缺点 | 是否选用 |
|------|------|------|----------|
| 固定窗口计数 | 实现简单 | 临界突刺问题（窗口边界两端各打满一次，实际2倍流量） | 否 |
| 滑动窗口（Redis SortedSet） | 精准、无临界突刺、支持按时间精确查询 | 需 Lua 脚本保证原子性 | **是** |
| 令牌桶 | 支持突发流量 | 实现复杂，与"同题冷却"语义不匹配 | 否（可后续扩展） |
| 漏桶 | 平滑流量 | 不允许突发，体验差 | 否 |

**选用理由**：  
Redis `ZSET`（有序集合）天然支持以时间戳为score的滑动窗口计数；  
配合 Lua 脚本保证"计数检查-写入"原子操作，避免并发竞态；  
无需引入额外依赖，利用项目已有的 `spring-boot-starter-data-redis`。

### 2.3 实现方式：AOP + 自定义注解

参照项目现有 `AuthCheck` 注解 + `AuthInterceptor` AOP 的设计模式，新增：
- `@RateLimit` 注解：标注在需要限流的方法上，声明限流策略；
- `RateLimitInterceptor` AOP：切点拦截 `@RateLimit`，执行 Redis 滑动窗口检查；
- `RateLimitRedisUtil`：封装 Redis Lua 脚本调用，提供滑动窗口和冷却检查能力；
- `RateLimitRuleService`：从 DB 动态读取限流规则配置，支持管理员后台修改。

### 2.4 依赖说明

本方案**不引入任何新 Maven 依赖**，完全利用现有依赖：

| 依赖 | 版本 | 用途 |
|------|------|------|
| `spring-boot-starter-data-redis` | (Boot 3.3.6 托管) | Redis 操作、RedisTemplate |
| `spring-boot-starter-aop` | (Boot 3.3.6 托管) | AOP 拦截器 |
| `hutool-all` | 5.8.26 | IP 解析（`HttpUtil`/`ServletUtil`）、工具方法 |
| `mybatis-plus-spring-boot3-starter` | 3.5.11 | 限流规则配置表的 CRUD |

---

## 三、整体架构设计

### 3.1 请求链路

```
用户发起提交请求
    │
    ▼
QuestionSubmitController.doQuestionSubmit()
    │
    ▼  （AOP 前置切入，@RateLimit 注解触发）
RateLimitInterceptor.doInterceptor()
    ├── [1] 全局限流检查      → Redis ZSET: rate_limit:global:submit
    ├── [2] IP 限流检查       → Redis ZSET: rate_limit:ip:{ip}:submit
    ├── [3] 用户分钟限流检查  → Redis ZSET: rate_limit:user:{userId}:submit:min
    ├── [4] 用户每日限流检查  → Redis String: rate_limit:user:{userId}:submit:day:{date}
    └── [5] 用户同题冷却检查  → Redis String: rate_limit:user:{userId}:q:{questionId}:cooldown
    │
    ▼  （通过限流检查）
QuestionSubmitService.doQuestionSubmit()
    │
    ▼
代码沙箱判题
```

### 3.2 模块结构

```
com.XI.xi_oj
├── annotation
│   └── AuthCheck.java          （已有）
│   └── RateLimit.java          【新增】限流注解
├── aop
│   └── AuthInterceptor.java    （已有）
│   └── RateLimitInterceptor.java  【新增】限流AOP
├── config
│   └── RedisConfig.java        【新增】Redis序列化配置
├── manager
│   └── RateLimitRedisUtil.java  【新增】Redis限流工具
├── model
│   ├── entity
│   │   └── RateLimitRule.java   【新增】限流规则实体
│   ├── dto
│   │   └── ratelimit
│   │       └── RateLimitRuleUpdateRequest.java  【新增】
│   └── enums
│       └── RateLimitTypeEnum.java   【新增】限流类型枚举
├── mapper
│   └── RateLimitRuleMapper.java     【新增】
├── service
│   ├── RateLimitRuleService.java    【新增】
│   └── impl
│       └── RateLimitRuleServiceImpl.java  【新增】
└── controller
    └── admin
        └── AdminRateLimitController.java  【新增】管理员限流配置接口
```

---

## 四、数据模型设计

### 4.1 数据库表：限流规则配置表（rate_limit_rule）

**用途**：持久化存储各维度限流规则，支持管理员后台动态修改，服务启动时加载到 Redis 缓存，修改后实时刷新。

```sql
-- ============================================================
-- 题目提交限流规则配置表
-- 创建时间：2026-04-01
-- ============================================================
CREATE TABLE IF NOT EXISTS rate_limit_rule
(
    id          bigint auto_increment comment 'id' primary key,
    rule_key    varchar(128)  NOT NULL comment '规则唯一键，对应 RateLimitTypeEnum 的 key',
    rule_name   varchar(256)  NOT NULL comment '规则名称，便于管理员理解',
    limit_count int           NOT NULL default 5 comment '时间窗口内最大允许次数（冷却类型时此字段无效）',
    window_seconds int        NOT NULL default 60 comment '时间窗口大小（秒），冷却类型表示冷却时长',
    is_enable   tinyint       NOT NULL default 1 comment '是否启用：1-启用，0-禁用',
    description varchar(512)  comment '规则描述',
    createTime  datetime      NOT NULL default CURRENT_TIMESTAMP comment '创建时间',
    updateTime  datetime      NOT NULL default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP comment '更新时间',
    UNIQUE KEY uk_rule_key (rule_key)
) comment '题目提交限流规则配置表' collate = utf8mb4_unicode_ci;

-- 初始化默认限流规则
INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description) VALUES
('submit:global:second',   '全局提交-秒级限流',     50,  1,    1, '平台每秒最多处理50次提交，超出保护代码沙箱'),
('submit:ip:minute',       'IP提交-分钟级限流',      10,  60,   1, '同一IP每分钟最多提交10次，防代理批量刷题'),
('submit:user:minute',     '用户提交-分钟级限流',    5,   60,   1, '同一用户每分钟最多提交5次'),
('submit:user:day',        '用户提交-每日限流',       200, 86400,1, '同一用户每天最多提交200次'),
('submit:user:question:cooldown', '用户同题提交冷却', 1,   30,   1, '同一用户对同一题目30秒内只能提交1次');
```

### 4.2 Redis Key 设计规范

| 限流维度 | Redis Key 格式 | 数据结构 | TTL |
|----------|----------------|----------|-----|
| 全局秒级 | `rl:global:submit:{yyyyMMddHHmmss}` | ZSET（滑动窗口） | 2秒（窗口1s） |
| IP分钟级 | `rl:ip:{ip}:submit:{yyyyMMddHHmm}` | ZSET（滑动窗口） | 120秒（窗口60s） |
| 用户分钟级 | `rl:user:{userId}:submit:min:{yyyyMMddHHmm}` | ZSET（滑动窗口） | 120秒（窗口60s） |
| 用户每日 | `rl:user:{userId}:submit:day:{yyyyMMdd}` | String（计数器） | 86400秒（1天） |
| 用户同题冷却 | `rl:user:{userId}:q:{questionId}:cd` | String（存在即冷却） | 冷却时间（30s） |
| 规则配置缓存 | `rl:rule:{rule_key}` | String（JSON） | 300秒（定期刷新） |

> **Key 前缀说明**：统一使用 `rl:` 前缀（rate_limit 缩写），便于 Redis 管理和清理。  
> **注意**：`map-underscore-to-camel-case: false`，实体类字段名与数据库字段名保持一致。

---

## 五、核心功能模块实现

### 5.1 限流类型枚举（RateLimitTypeEnum）

```java
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
    USER_QUESTION_COOLDOWN("submit:user:question:cooldown");

    private final String ruleKey;

    RateLimitTypeEnum(String ruleKey) {
        this.ruleKey = ruleKey;
    }

    public String getRuleKey() {
        return ruleKey;
    }
}
```

### 5.2 自定义限流注解（@RateLimit）

参照现有 `AuthCheck.java` 的设计风格：

```java
package com.XI.xi_oj.annotation;

import com.XI.xi_oj.model.enums.RateLimitTypeEnum;

import java.lang.annotation.*;

/**
 * 题目提交限流注解
 * 支持同时声明多个限流维度，AOP 拦截器将按顺序依次检查
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 需要开启的限流维度列表
     * 默认同时开启：用户分钟级 + 用户每日 + 用户同题冷却
     */
    RateLimitTypeEnum[] types() default {
            RateLimitTypeEnum.USER_MINUTE,
            RateLimitTypeEnum.USER_DAY,
            RateLimitTypeEnum.USER_QUESTION_COOLDOWN
    };

    /**
     * 触发限流时的提示信息，为空时使用各维度默认提示
     */
    String message() default "";
}
```

### 5.3 Redis 配置类（RedisConfig）

确保 `RedisTemplate` 使用 String 序列化，避免乱码：

```java
package com.XI.xi_oj.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 * 统一设置 key/value 序列化方式，避免乱码
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> stringStringRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
```

### 5.4 Redis 限流工具类（RateLimitRedisUtil）

核心工具类，封装两种限流检查：滑动窗口计数 和 冷却时间检查。  
使用 Lua 脚本保证原子性，直接使用 `RedisTemplate` 执行，无需额外依赖。

```java
package com.XI.xi_oj.manager;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 限流工具类
 * 提供滑动窗口计数限流 和 冷却时间限流 两种能力
 */
@Component
public class RateLimitRedisUtil {

    @Resource
    private RedisTemplate<String, String> stringStringRedisTemplate;

    /**
     * 滑动窗口限流 Lua 脚本
     * 逻辑：
     *   1. 移除窗口之外的过期元素（score < 当前时间戳 - 窗口大小）
     *   2. 统计当前窗口内的元素数量
     *   3. 若 count < maxCount，则写入当前时间戳（value=唯一UUID防重），设置key过期时间，返回1（放行）
     *   4. 否则返回0（拒绝）
     *
     * KEYS[1] = redis key
     * ARGV[1] = 当前时间戳（毫秒）
     * ARGV[2] = 窗口大小（毫秒）
     * ARGV[3] = 最大次数
     * ARGV[4] = key的TTL（秒）
     */
    private static final String SLIDING_WINDOW_SCRIPT =
            "local key = KEYS[1] " +
                    "local now = tonumber(ARGV[1]) " +
                    "local window = tonumber(ARGV[2]) " +
                    "local maxCount = tonumber(ARGV[3]) " +
                    "local ttl = tonumber(ARGV[4]) " +
                    "redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window) " +
                    "local count = redis.call('ZCARD', key) " +
                    "if count < maxCount then " +
                    "  redis.call('ZADD', key, now, now .. '-' .. math.random(1, 999999)) " +
                    "  redis.call('EXPIRE', key, ttl) " +
                    "  return 1 " +
                    "else " +
                    "  return 0 " +
                    "end";

    /**
     * 冷却时间限流 Lua 脚本
     * 逻辑：
     *   1. 检查 key 是否存在
     *   2. 不存在则 SET key NX EX cooldownSeconds，返回1（放行）
     *   3. 存在则返回0（冷却中，拒绝）
     *
     * KEYS[1] = redis key
     * ARGV[1] = 冷却时长（秒）
     */
    private static final String COOLDOWN_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 0 then " +
                    "  redis.call('SET', KEYS[1], '1', 'EX', tonumber(ARGV[1])) " +
                    "  return 1 " +
                    "else " +
                    "  return 0 " +
                    "end";

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_REDIS_SCRIPT;
    private static final DefaultRedisScript<Long> COOLDOWN_REDIS_SCRIPT;

    static {
        SLIDING_WINDOW_REDIS_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_REDIS_SCRIPT.setScriptText(SLIDING_WINDOW_SCRIPT);
        SLIDING_WINDOW_REDIS_SCRIPT.setResultType(Long.class);

        COOLDOWN_REDIS_SCRIPT = new DefaultRedisScript<>();
        COOLDOWN_REDIS_SCRIPT.setScriptText(COOLDOWN_SCRIPT);
        COOLDOWN_REDIS_SCRIPT.setResultType(Long.class);
    }

    /**
     * 滑动窗口限流检查
     *
     * @param redisKey      Redis Key
     * @param windowSeconds 窗口大小（秒）
     * @param maxCount      窗口内最大请求数
     * @return true=放行，false=被限流
     */
    public boolean slidingWindowAllow(String redisKey, long windowSeconds, int maxCount) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        // TTL 设置为窗口大小的 2 倍，防止 key 提前过期导致计数丢失
        long ttlSeconds = windowSeconds * 2;

        List<String> keys = Collections.singletonList(redisKey);
        Long result = stringStringRedisTemplate.execute(
                SLIDING_WINDOW_REDIS_SCRIPT,
                keys,
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(maxCount),
                String.valueOf(ttlSeconds)
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 冷却时间限流检查
     *
     * @param redisKey        Redis Key
     * @param cooldownSeconds 冷却时间（秒）
     * @return true=放行（不在冷却中），false=被限流（冷却中）
     */
    public boolean cooldownAllow(String redisKey, long cooldownSeconds) {
        List<String> keys = Collections.singletonList(redisKey);
        Long result = stringStringRedisTemplate.execute(
                COOLDOWN_REDIS_SCRIPT,
                keys,
                String.valueOf(cooldownSeconds)
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 用户每日计数限流检查（固定窗口，按自然日重置）
     *
     * @param redisKey 格式：rl:user:{userId}:submit:day:{yyyyMMdd}
     * @param maxCount 每日最大次数
     * @return true=放行，false=被限流
     */
    public boolean dailyCountAllow(String redisKey, int maxCount) {
        // INCR 原子自增，若超出 maxCount 则拒绝
        Long count = stringStringRedisTemplate.opsForValue().increment(redisKey);
        if (count == null) {
            return true;
        }
        if (count == 1L) {
            // 首次写入，设置到当天结束过期（86400秒，简化处理）
            stringStringRedisTemplate.expire(redisKey, Duration.ofSeconds(86400));
        }
        return count <= maxCount;
    }
}
```

### 5.5 限流规则实体与 Mapper

**实体类（注意 `map-underscore-to-camel-case: false`，字段名与数据库列名保持一致）：**

```java
package com.XI.xi_oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 限流规则实体
 */
@Data
@TableName("rate_limit_rule")
public class RateLimitRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则唯一键，对应 RateLimitTypeEnum 的 ruleKey */
    private String rule_key;

    /** 规则名称 */
    private String rule_name;

    /** 时间窗口内最大允许次数 */
    private Integer limit_count;

    /** 时间窗口大小（秒），冷却类型表示冷却时长 */
    private Integer window_seconds;

    /** 是否启用：1-启用，0-禁用 */
    private Integer is_enable;

    /** 规则描述 */
    private String description;

    private Date createTime;

    private Date updateTime;
}
```

**Mapper：**

```java
package com.XI.xi_oj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.XI.xi_oj.model.entity.RateLimitRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RateLimitRuleMapper extends BaseMapper<RateLimitRule> {
}
```

### 5.6 限流规则服务（RateLimitRuleService）

**接口：**

```java
package com.XI.xi_oj.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.XI.xi_oj.model.entity.RateLimitRule;

/**
 * 限流规则服务
 */
public interface RateLimitRuleService extends IService<RateLimitRule> {

    /**
     * 根据 ruleKey 获取规则（优先从 Redis 缓存读取）
     */
    RateLimitRule getRuleByKey(String ruleKey);

    /**
     * 刷新指定 ruleKey 的 Redis 缓存（管理员修改后调用）
     */
    void refreshRuleCache(String ruleKey);

    /**
     * 启动时预热：将所有规则加载到 Redis 缓存
     */
    void warmUpCache();
}
```

**实现类：**

```java
package com.XI.xi_oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.XI.xi_oj.mapper.RateLimitRuleMapper;
import com.XI.xi_oj.model.entity.RateLimitRule;
import com.XI.xi_oj.service.RateLimitRuleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class RateLimitRuleServiceImpl extends ServiceImpl<RateLimitRuleMapper, RateLimitRule>
        implements RateLimitRuleService {

    /** Redis 缓存的限流规则 key 前缀 */
    private static final String RULE_CACHE_PREFIX = "rl:rule:";
    /** 规则缓存 TTL：5 分钟 */
    private static final Duration RULE_CACHE_TTL = Duration.ofMinutes(5);

    @Resource
    private RedisTemplate<String, String> stringStringRedisTemplate;

    @PostConstruct
    @Override
    public void warmUpCache() {
        List<RateLimitRule> rules = list();
        for (RateLimitRule rule : rules) {
            String cacheKey = RULE_CACHE_PREFIX + rule.getRule_key();
            stringStringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(rule), RULE_CACHE_TTL);
        }
        log.info("[RateLimit] 限流规则缓存预热完成，共 {} 条规则", rules.size());
    }

    @Override
    public RateLimitRule getRuleByKey(String ruleKey) {
        String cacheKey = RULE_CACHE_PREFIX + ruleKey;
        String cached = stringStringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSONUtil.toBean(cached, RateLimitRule.class);
        }
        // 缓存未命中，从 DB 查询
        RateLimitRule rule = getOne(new QueryWrapper<RateLimitRule>().eq("rule_key", ruleKey));
        if (rule != null) {
            stringStringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(rule), RULE_CACHE_TTL);
        }
        return rule;
    }

    @Override
    public void refreshRuleCache(String ruleKey) {
        String cacheKey = RULE_CACHE_PREFIX + ruleKey;
        stringStringRedisTemplate.delete(cacheKey);
        getRuleByKey(ruleKey); // 触发重新加载
        log.info("[RateLimit] 规则缓存已刷新：{}", ruleKey);
    }
}
```

### 5.7 限流 AOP 拦截器（RateLimitInterceptor）

参照现有 `AuthInterceptor.java` 的风格，切点为 `@RateLimit` 注解：

```java
package com.XI.xi_oj.aop;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.manager.RateLimitRedisUtil;
import com.XI.xi_oj.model.entity.RateLimitRule;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.model.enums.RateLimitTypeEnum;
import com.XI.xi_oj.service.RateLimitRuleService;
import com.XI.xi_oj.service.UserService;
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

/**
 * 题目提交限流 AOP 拦截器
 * 切点：标注了 @RateLimit 的方法
 */
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
}
```

### 5.8 ErrorCode 扩展

在现有 `ErrorCode.java` 中新增 `TOO_MANY_REQUESTS` 错误码：

```java
// 在 ErrorCode 枚举中新增以下条目
TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试");
```

**修改后的完整 ErrorCode（仅展示新增部分位置）：**
```java
// 原有...
OPERATION_ERROR(50001, "操作失败"),
// 新增：
TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试");
```

> **HTTP 状态码说明**：业务码 `42900` 对应语义 HTTP 429 Too Many Requests，前端可根据此 code 展示对应的友好提示（"提交太频繁了"）。

### 5.9 题目提交接口集成（QuestionSubmitController 修改）

在现有 `doQuestionSubmit` 方法上添加 `@RateLimit` 注解，**其他代码不变**：

```java
/**
 * 提交题目
 * 新增 @RateLimit 注解，开启用户分钟级 + 每日 + 同题冷却三维限流
 */
@PostMapping("/")
@RateLimit  // 使用默认策略：USER_MINUTE + USER_DAY + USER_QUESTION_COOLDOWN
public BaseResponse<Long> doQuestionSubmit(@RequestBody QuestionSubmitAddRequest questionSubmitAddRequest,
                                           HttpServletRequest request) {
    if (questionSubmitAddRequest == null || questionSubmitAddRequest.getQuestionId() <= 0) {
        throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }
    final User loginUser = userService.getLoginUser(request);
    long questionSubmitId = questionSubmitService.doQuestionSubmit(questionSubmitAddRequest, loginUser);
    return ResultUtils.success(questionSubmitId);
}
```

> **扩展用法**：若后续需要对特定接口开启 IP 或全局限流，可灵活指定：
> ```java
> @RateLimit(types = {
>     RateLimitTypeEnum.GLOBAL_SECOND,
>     RateLimitTypeEnum.IP_MINUTE,
>     RateLimitTypeEnum.USER_MINUTE,
>     RateLimitTypeEnum.USER_QUESTION_COOLDOWN
> })
> ```

---

## 六、管理员动态配置接口

管理员可通过后台接口动态修改限流规则，修改后立即刷新 Redis 缓存，无需重启服务。

### 6.1 请求 DTO

```java
package com.XI.xi_oj.model.dto.ratelimit;

import lombok.Data;

/**
 * 限流规则更新请求
 */
@Data
public class RateLimitRuleUpdateRequest {

    /** 规则唯一键 */
    private String rule_key;

    /** 时间窗口内最大允许次数 */
    private Integer limit_count;

    /** 时间窗口大小（秒）*/
    private Integer window_seconds;

    /** 是否启用：1-启用，0-禁用 */
    private Integer is_enable;
}
```

### 6.2 管理员控制器

```java
package com.XI.xi_oj.controller.admin;

import com.XI.xi_oj.annotation.AuthCheck;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.constant.UserConstant;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.model.dto.ratelimit.RateLimitRuleUpdateRequest;
import com.XI.xi_oj.model.entity.RateLimitRule;
import com.XI.xi_oj.service.RateLimitRuleService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员限流规则配置接口
 */
@RestController
@RequestMapping("/admin/rate-limit")
public class AdminRateLimitController {

    @Resource
    private RateLimitRuleService rateLimitRuleService;

    /**
     * 获取所有限流规则
     */
    @GetMapping("/rules")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<RateLimitRule>> listRules() {
        return ResultUtils.success(rateLimitRuleService.list());
    }

    /**
     * 更新限流规则（修改后自动刷新缓存）
     */
    @PostMapping("/rule/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateRule(@RequestBody RateLimitRuleUpdateRequest updateRequest) {
        if (updateRequest == null || updateRequest.getRule_key() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 使用 MyBatis-Plus updateWrapper 按 rule_key 更新
        RateLimitRule updateEntity = new RateLimitRule();
        updateEntity.setRule_key(updateRequest.getRule_key());
        updateEntity.setLimit_count(updateRequest.getLimit_count());
        updateEntity.setWindow_seconds(updateRequest.getWindow_seconds());
        updateEntity.setIs_enable(updateRequest.getIs_enable());

        boolean success = rateLimitRuleService.update(updateEntity,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<RateLimitRule>()
                        .eq("rule_key", updateRequest.getRule_key()));

        if (success) {
            // 刷新 Redis 缓存
            rateLimitRuleService.refreshRuleCache(updateRequest.getRule_key());
        }
        return ResultUtils.success(success);
    }

    /**
     * 重新预热所有规则缓存
     */
    @PostMapping("/cache/warm-up")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<String> warmUpCache() {
        rateLimitRuleService.warmUpCache();
        return ResultUtils.success("缓存预热完成");
    }
}
```

---

## 七、配置文件扩展

在 `application.yml` 中补充 Redis 配置（取消注释并填写实际值）：

```yaml
spring:
  redis:
    database: 1
    host: localhost
    port: 6379
    timeout: 5000
    password: 123456   # 无密码则删除此行
  session:
    store-type: redis  # 开启分布式session，限流需要 Redis，配套开启
```

---

## 八、完整 SQL（汇总）

```sql
-- ============================================================
-- 题目提交限流功能相关 SQL
-- 适用数据库：oj_db
-- 创建日期：2026-04-01
-- ============================================================

USE oj_db;

-- 1. 限流规则配置表
CREATE TABLE IF NOT EXISTS rate_limit_rule
(
    id             bigint        NOT NULL auto_increment comment 'id' primary key,
    rule_key       varchar(128)  NOT NULL comment '规则唯一键',
    rule_name      varchar(256)  NOT NULL comment '规则名称',
    limit_count    int           NOT NULL default 5 comment '时间窗口内最大允许次数',
    window_seconds int           NOT NULL default 60 comment '时间窗口大小（秒），冷却类型表示冷却时长',
    is_enable      tinyint       NOT NULL default 1 comment '是否启用：1-启用，0-禁用',
    description    varchar(512)  NULL comment '规则描述',
    createTime     datetime      NOT NULL default CURRENT_TIMESTAMP comment '创建时间',
    updateTime     datetime      NOT NULL default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP comment '更新时间',
    UNIQUE KEY uk_rule_key (rule_key)
) comment '题目提交限流规则配置表' collate = utf8mb4_unicode_ci;

-- 2. 初始化默认限流规则
INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description)
VALUES
('submit:global:second',            '全局提交-秒级限流',     50,  1,     1, '平台每秒最多处理50次提交，超出返回429，保护代码沙箱'),
('submit:ip:minute',                'IP提交-分钟级限流',      10,  60,    1, '同一IP每分钟最多提交10次，防代理批量刷题'),
('submit:user:minute',              '用户提交-分钟级限流',    5,   60,    1, '同一用户每分钟最多提交5次，防快速连刷'),
('submit:user:day',                 '用户提交-每日限流',       200, 86400, 1, '同一用户每天最多提交200次，防日级刷量'),
('submit:user:question:cooldown',   '用户同题提交冷却',       1,   30,    1, '同一用户对同一题目30秒内只能提交1次，防重复无效提交');
```

---

## 九、实施计划

### 9.1 前置条件

| 条件 | 状态 |
|------|------|
| 代码沙箱开发完成（基础项目完工） | 待完成 |
| Redis 服务部署并可连接 | 需配置 |
| `application.yml` 中 Redis 配置取消注释 | 需配置 |

### 9.2 开发步骤（建议顺序）

| 步骤 | 任务 | 涉及文件 |
|------|------|----------|
| 1 | 执行 SQL，创建 `rate_limit_rule` 表并插入初始规则 | `sql/create_table.sql`（追加） |
| 2 | 新增 `ErrorCode.TOO_MANY_REQUESTS` | `common/ErrorCode.java` |
| 3 | 新增 `RateLimitTypeEnum` | `model/enums/RateLimitTypeEnum.java` |
| 4 | 新增 `RateLimitRule` 实体 + `RateLimitRuleMapper` | `model/entity/`、`mapper/` |
| 5 | 新增 `RedisConfig`（Redis序列化配置） | `config/RedisConfig.java` |
| 6 | 新增 `RateLimitRedisUtil` | `manager/RateLimitRedisUtil.java` |
| 7 | 新增 `RateLimitRuleService` + `RateLimitRuleServiceImpl` | `service/` |
| 8 | 新增 `@RateLimit` 注解 | `annotation/RateLimit.java` |
| 9 | 新增 `RateLimitInterceptor` AOP | `aop/RateLimitInterceptor.java` |
| 10 | `QuestionSubmitController` 添加 `@RateLimit` 注解 | `controller/QuestionSubmitController.java` |
| 11 | 新增管理员配置接口 `AdminRateLimitController` | `controller/admin/` |
| 12 | 配置 `application.yml`，开启 Redis 连接 | `resources/application.yml` |

### 9.3 测试方案

| 测试场景 | 验证方式 | 预期结果 |
|----------|----------|----------|
| 用户1分钟内第6次提交 | 连续提交6次，第6次 | 返回 `{"code": 42900, "message": "提交太频繁了..."}` |
| 同题30秒内二次提交 | 30秒内对同题提交2次，第2次 | 返回 `{"code": 42900, "message": "同一题目提交太频繁..."}` |
| 同题30秒后再提交 | 等待30秒后再提交 | 正常返回提交ID |
| 管理员修改规则 | PUT `/api/admin/rate-limit/rule/update`，改分钟上限为3 | 3次后即触发限流 |
| 关闭某条规则 | PUT `is_enable=0` | 对应维度不再限流 |
| 多用户并发 | 50个用户同时提交 | 全局限流兜底，超出 50/s 的请求返回 429 |

---

## 十、AI接口限流预留说明

本文档仅覆盖**题目提交限流**。AI接口限流将在 AIGC 功能全部落地后单独设计，但基础设施可复用本方案：

| 复用内容 | AI限流扩展点 |
|----------|-------------|
| `RateLimitTypeEnum` | 新增 `AI_CHAT_USER_DAY`、`AI_ANALYSIS_USER_HOUR` 等枚举值 |
| `@RateLimit` 注解 | 直接标注在 AI 接口方法上，声明 AI 维度的限流类型 |
| `RateLimitRedisUtil` | 不需要改动，直接复用 `slidingWindowAllow` / `dailyCountAllow` |
| `rate_limit_rule` 表 | 新增 AI 相关规则行即可，表结构无需变更 |
| `RateLimitInterceptor` | 在 `switch` 块中新增 AI 限流类型的 case 分支 |
| 管理员配置接口 | 直接复用，只需在前端管理页加 AI 规则的筛选展示 |

> **AI限流特殊需求**（预研）：AI接口还需考虑 Token 消耗量限流（每日最大 Token 上限），该逻辑需在 AI 调用层单独实现，不属于本方案范畴，将在 AI限流设计文档中详细描述。

---

## 十一、风险与注意事项

| 风险 | 说明 | 规避方案 |
|------|------|----------|
| Redis 不可用 | Redis 宕机导致限流工具类抛异常，影响正常提交 | `RateLimitInterceptor` 捕获 Redis 异常，记录日志后**放行**（降级策略：Redis故障时不限流，保证可用性优先） |
| Lua 脚本加载失败 | `DefaultRedisScript` 在高版本Redis有兼容问题 | 测试时使用 `SCRIPT LOAD` 验证；生产使用 Redis 6.0+（项目backend_dev.md要求） |
| 每日计数与自然日不对齐 | `dailyCountAllow` 使用 86400s TTL，不严格按自然日0点重置 | 简化实现可接受；如需精确可改为计算到次日0点的剩余秒数作为TTL |
| 滑动窗口 Key 过多 | 用户量大时 ZSET key 数量增多 | TTL 已设置为窗口2倍，自动过期；Redis 内存按实际用量评估 |
| `map-underscore-to-camel-case: false` | 实体字段名须与列名完全一致（含下划线） | 已在实体类中使用下划线命名（如 `rule_key`、`limit_count`），与 DB 列名对齐 |

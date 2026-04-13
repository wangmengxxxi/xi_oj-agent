# XI OJ 题目提交限流 — 执行步骤

> 版本：V1.0 | 日期：2026-04-10
> 参考文档：`rate_limit_dev.md` / `前端方案设计.md` / `前端页面拓展文档.md`
> 前置条件：代码沙箱已完成，Redis 服务已部署

---

## 执行顺序总览

```
阶段一：数据库建表 + 初始化规则（P0）
    ↓
阶段二：ErrorCode 扩展（P0）
    ↓
阶段三：后端限流核心类（P0）
    ├── 枚举 + 实体 + Mapper
    ├── RedisConfig
    └── RateLimitRedisUtil
    ↓
阶段四：规则服务层（P0）
    ↓
阶段五：AOP 注解 + 拦截器（P0）
    ↓
阶段六：集成到提交接口 + 管理员接口（P0）
    ↓
阶段七：配置文件更新（P0）
    ↓
阶段八：前端适配（P1）
    ↓
阶段九：测试与验收（P1）
```

---

## 阶段一：数据库准备（P0）

### 步骤 1.1 — 建限流规则配置表

在 `sql/` 目录下新建 `rate_limit_tables.sql`，执行以下 SQL：

```sql
USE oj_db;

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

INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description)
VALUES
('submit:global:second',          '全局提交-秒级限流',     50,  1,     1, '平台每秒最多处理50次提交'),
('submit:ip:minute',              'IP提交-分钟级限流',      10,  60,    1, '同一IP每分钟最多提交10次'),
('submit:user:minute',            '用户提交-分钟级限流',    5,   60,    1, '同一用户每分钟最多提交5次'),
('submit:user:day',               '用户提交-每日限流',       200, 86400, 1, '同一用户每天最多提交200次'),
('submit:user:question:cooldown', '用户同题提交冷却',       1,   30,    1, '同一用户同一题目30秒内只能提交1次');
```

**验收标准**：`SELECT * FROM rate_limit_rule` 返回5条记录。

---

## 阶段二：ErrorCode 扩展（P0）

### 步骤 2.1 — 新增 TOO_MANY_REQUESTS 错误码

文件：`src/main/java/com/XI/xi_oj/common/ErrorCode.java`

在已有 `OPERATION_ERROR` 之后追加：

```java
TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试");
```

> **业务含义**：HTTP 语义对应 429 Too Many Requests。前端拦截器对 code=42900 不弹通用 Toast，将 message 原文透传给各页面处理。

---

## 阶段三：后端限流核心类（P0）

### 步骤 3.1 — 创建 RateLimitTypeEnum

位置：`src/main/java/com/XI/xi_oj/model/enums/RateLimitTypeEnum.java`

定义5个限流类型枚举值（§5.1 完整代码），ruleKey 与数据库 rule_key 字段对应：

```
GLOBAL_SECOND  → "submit:global:second"
IP_MINUTE      → "submit:ip:minute"
USER_MINUTE    → "submit:user:minute"
USER_DAY       → "submit:user:day"
USER_QUESTION_COOLDOWN → "submit:user:question:cooldown"
```

### 步骤 3.2 — 创建 RateLimitRule 实体 + Mapper

位置：
- `src/main/java/com/XI/xi_oj/model/entity/RateLimitRule.java`
- `src/main/java/com/XI/xi_oj/mapper/RateLimitRuleMapper.java`

**注意**：项目 `application.yml` 中若配置了 `map-underscore-to-camel-case: true`，须临时关闭或为该实体单独处理。因为限流规则实体字段名含下划线（如 `rule_key`、`limit_count`），与数据库列名完全一致，不做驼峰转换（§5.5 代码）。

### 步骤 3.3 — 创建 RedisConfig

位置：`src/main/java/com/XI/xi_oj/config/RedisConfig.java`

注册一个 `RedisTemplate<String, String>`，使用 `StringRedisSerializer` 序列化 key 和 value（§5.3 代码）。

> 若项目已有 RedisConfig，确认是否已有 `RedisTemplate<String, String>` 的 Bean，有则跳过。

### 步骤 3.4 — 创建 RateLimitRedisUtil

位置：`src/main/java/com/XI/xi_oj/manager/RateLimitRedisUtil.java`

封装3个方法（§5.4 完整代码）：

| 方法 | 限流类型 | 底层实现 |
|------|---------|---------|
| `slidingWindowAllow()` | 全局/IP/用户分钟级 | Lua + ZSET 滑动窗口 |
| `cooldownAllow()` | 同题冷却 | Lua + String SET NX EX |
| `dailyCountAllow()` | 用户每日 | String INCR |

---

## 阶段四：规则服务层（P0）

### 步骤 4.1 — 实现 RateLimitRuleService

位置：`src/main/java/com/XI/xi_oj/service/`

接口定义（§5.6 代码）：

```java
public interface RateLimitRuleService extends IService<RateLimitRule> {
    RateLimitRule getRuleByKey(String ruleKey);    // 优先从 Redis 缓存读取
    void refreshRuleCache(String ruleKey);          // 管理员修改后刷新缓存
    void warmUpCache();                             // 启动时预热所有规则
}
```

`RateLimitRuleServiceImpl` 实现要点：
- `@PostConstruct` 标注 `warmUpCache()`，启动时自动将所有规则写入 Redis（key：`rl:rule:{rule_key}`，TTL 5分钟）
- `getRuleByKey()` 先查 Redis，miss 再查 DB 并回填缓存
- `refreshRuleCache()` 删除 Redis key 后再调 `getRuleByKey()` 重新写入

---

## 阶段五：AOP 注解 + 拦截器（P0）

### 步骤 5.1 — 创建 @RateLimit 注解

位置：`src/main/java/com/XI/xi_oj/annotation/RateLimit.java`

参照 `AuthCheck.java` 风格（§5.2 代码）：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    RateLimitTypeEnum[] types() default {
        USER_MINUTE, USER_DAY, USER_QUESTION_COOLDOWN
    };
    String message() default "";
}
```

### 步骤 5.2 — 实现 RateLimitInterceptor

位置：`src/main/java/com/XI/xi_oj/aop/RateLimitInterceptor.java`

切点 `@Around("@annotation(rateLimit)")`，按 `rateLimit.types()` 顺序依次检查（§5.7 完整代码）。

关键实现细节：
- 使用 `JakartaServletUtil.getClientIP(request)` 获取客户端真实 IP（Hutool Jakarta 版）
- `extractQuestionId()` 通过反射从方法参数中取 `questionId`，不引入对具体类的依赖
- 任意维度触发限流即抛 `BusinessException(ErrorCode.TOO_MANY_REQUESTS, message)`
- 规则 `is_enable=0` 时直接放行，不做检查

---

## 阶段六：集成接口（P0）

### 步骤 6.1 — 在 QuestionSubmitController 添加 @RateLimit

文件：`src/main/java/com/XI/xi_oj/controller/QuestionSubmitController.java`

仅在 `doQuestionSubmit` 方法上新增注解，其他代码不变：

```java
@PostMapping("/")
@RateLimit   // 使用默认策略：USER_MINUTE + USER_DAY + USER_QUESTION_COOLDOWN
public BaseResponse<Long> doQuestionSubmit(...) { ... }
```

### 步骤 6.2 — 创建 AdminRateLimitController

位置：`src/main/java/com/XI/xi_oj/controller/admin/AdminRateLimitController.java`

提供3个接口（§6.2 完整代码，路径前缀 `/admin/rate-limit`）：

| 接口 | 说明 |
|------|------|
| `GET /rules` | 获取全部限流规则 |
| `POST /rule/update` | 更新规则（修改后立即刷新 Redis） |
| `POST /cache/warm-up` | 手动触发全量缓存预热 |

所有接口加 `@AuthCheck(mustRole = "admin")`。

---

## 阶段七：配置文件更新（P0）

### 步骤 7.1 — 更新 application.yml

文件：`src/main/resources/application.yml`

取消注释并填写 Redis 连接配置（§7 代码）：

```yaml
spring:
  redis:
    database: 1
    host: localhost
    port: 6379
    timeout: 5000
    # password: xxx  # 有密码则取消注释
  session:
    store-type: redis
```

> **重要**：确认 `mybatis-plus.configuration.map-underscore-to-camel-case` 的值。若为 `true`，RateLimitRule 实体字段名（`rule_key`、`limit_count` 等含下划线）会被自动映射为 `ruleKey`、`limitCount`，导致查询 DB 时字段找不到。可为该实体类所在 Mapper 单独配置，或将实体字段改为驼峰命名后用 `@TableField("rule_key")` 注解映射。

---

## 阶段八：前端适配（P1）

### 步骤 8.1 — 全局拦截器处理限流响应

文件：`frontend/OJ_frontend/src/plugins/axios.ts`（或现有请求拦截器）

在响应拦截器中对 `code === 42900` 单独处理（参考前端方案设计 §8）：

```typescript
if (code === 42900) {
  // 不弹通用 Toast，将 message 原文透传给调用方
  return Promise.reject({ isRateLimit: true, message });
}
```

### 步骤 8.2 — 做题页集成限流提示

文件：`frontend/OJ_frontend/src/views/question/ViewQuestionView.vue`

在 `doQuestionSubmit()` 的 catch 块中处理限流：

```typescript
if (error.isRateLimit) {
  // 在结果区展示限流提示，不进入轮询
  rateLimitMessage.value = error.message;
  // 同题冷却场景：展示倒计时
  if (error.message.includes('同一题目')) {
    startCooldownCountdown(30);
  }
}
```

### 步骤 8.3 — 创建限流规则管理页（管理员）

文件：`frontend/OJ_frontend/src/views/admin/RateLimitManageView.vue`

参考前端页面拓展文档 §8，实现：
- 表格展示所有规则（rule_name、window_seconds、limit_count、is_enable）
- 点击「编辑」弹出 Modal，修改后调 `POST /api/admin/rate-limit/rule/update`
- 「刷新缓存」按钮确认后调 `POST /api/admin/rate-limit/cache/warm-up`
- `is_enable=0` 的行灰显（`opacity: 0.5`）

### 步骤 8.4 — 路由 + 导航更新

文件：`frontend/OJ_frontend/src/router/routes.ts`

在管理员路由中追加：

```typescript
{ path: '/admin/rate-limit', name: 'RateLimitManage',
  component: () => import('@/views/admin/RateLimitManageView.vue'),
  meta: { requiresRole: 'admin', title: '限流管理', showInMenu: true } }
```

文件：`frontend/OJ_frontend/src/layouts/BasicLayout.vue`

在管理员下拉菜单中追加「限流管理」入口。

---

## 阶段九：测试与验收（P1）

### 步骤 9.1 — 后端测试场景

| 测试场景 | 验证点 |
|---------|-------|
| 用户1分钟内第6次提交 | 返回 `code=42900`，message 含「每分钟最多提交5次」 |
| 同题30秒内二次提交 | 返回 `code=42900`，message 含「请等待30秒」 |
| 同题30秒后提交 | 正常返回提交 ID |
| Redis 断开后提交 | 限流拦截器捕获异常，记录 log，放行（降级策略） |
| 管理员修改分钟上限为3 | 3次后即触发限流 |
| 管理员禁用某规则（is_enable=0） | 对应维度不再限流 |

### 步骤 9.2 — 前端验证

1. 快速点击提交5次 → 第6次展示限流提示（不弹 Toast，结果区显示）
2. 同题30秒内再提交 → 展示冷却倒计时
3. 管理员访问 `/admin/rate-limit` → 能查看并修改规则

---

## 开发优先级汇总

| 优先级 | 任务 | 阶段 |
|--------|------|------|
| P0 | 建表 + 初始化5条规则 | 阶段一 |
| P0 | TOO_MANY_REQUESTS 错误码 | 阶段二 |
| P0 | RateLimitTypeEnum + RateLimitRule + Mapper | 阶段三 |
| P0 | RedisConfig + RateLimitRedisUtil | 阶段三 |
| P0 | RateLimitRuleService（含 @PostConstruct 预热） | 阶段四 |
| P0 | @RateLimit 注解 + RateLimitInterceptor | 阶段五 |
| P0 | QuestionSubmitController 加注解 | 阶段六 |
| P0 | AdminRateLimitController | 阶段六 |
| P0 | application.yml Redis 配置 | 阶段七 |
| P1 | axios 拦截器限流处理 | 阶段八 |
| P1 | ViewQuestionView 限流提示 UI | 阶段八 |
| P1 | RateLimitManageView（管理页）+ 路由导航 | 阶段八 |
| P1 | 后端测试 + 前端验证 | 阶段九 |

---

*文档版本：V1.0 | 生成日期：2026-04-10*

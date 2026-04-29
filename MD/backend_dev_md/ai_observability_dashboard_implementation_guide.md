# XI OJ AI 可观测性简易看板执行文档

更新时间：2026-04-29

适用范围：当前 XI OJ 后端已有 AI 问答、代码分析、题目解析、错题分析、RAG、Rerank、自定义 Agent Loop、全局 AI 限流等模块。本方案目标是在不引入 Micrometer / Prometheus / Grafana 的前提下，先做一个轻量后台可观测性看板。

---

## 一、目标

本次看板展示以下核心内容：

1. 今日 AI 调用次数
2. 今日被限流次数
3. 各 AI 模块调用分布：问答 / 代码分析 / 题目解析 / 错题分析
4. 平均响应耗时 / 最大响应耗时
5. Agent Loop 平均步数
6. 工具调用次数 TopN
7. 工具失败次数
8. RAG 检索为空次数
9. Rerank 调用次数 / 失败次数
10. LinkValidationFilter 移除假链接次数

设计原则：

- 先做业务级可观测性，不引入复杂监控平台。
- 先满足后台排查和演示需要，再考虑 Prometheus / Grafana。
- 指标写入要低侵入，不能影响 AI 主链路稳定性。
- 允许少量异步或 Redis 计数，避免每次请求都落复杂日志。

---

## 二、当前项目已有基础

### 2.1 已有 AI 限流

AI 接口已经通过 `@RateLimit` 接入全局令牌桶限流：

- `AiChatController`
- `AiCodeAnalysisController`
- `AiQuestionParseController`
- `AiWrongQuestionController`

典型注解：

```java
@RateLimit(types = {AI_GLOBAL_TOKEN_BUCKET, AI_USER_MINUTE, AI_IP_MINUTE, AI_CHAT_USER_DAY})
```

全局 AI 令牌桶对应规则：

```text
rate_limit_rule.rule_key = ai:global:token_bucket
```

只要该规则 `is_enable = 1`，限流就已生效。

### 2.2 已有 AI 问答记录

已有表：

```text
ai_chat_record
```

可用于统计：

- AI 问答调用次数
- 问答历史
- 用户维度调用分布

不足：

- 没有保存响应耗时
- 只覆盖 AI Chat，不覆盖代码分析 / 题目解析 / 错题分析

### 2.3 已有 Agent Trace

已有或已规划表：

```text
ai_agent_trace_log
```

可用于统计：

- Agent Loop 平均步数
- 工具调用次数 TopN
- 工具失败次数
- 每一步 thought / action / observation
- 单步耗时

不足：

- 只在 advanced Agent Loop 下有数据
- simple AiServices 模式下工具调用过程不可见

### 2.4 已有控制台日志

当前已有或可新增日志点：

- `[AgentLoop] step=...`
- `[Rerank] start / success / failed`
- `[LinkFilter] removed fake link`
- `[RateLimit] ... 限流触发`

不足：

- 日志不适合做页面统计
- 需要结构化计数器或指标表

---

## 三、推荐方案总览

采用“两层数据源”：

```text
业务明细表：ai_observation_event
Redis 今日计数器：metrics:ai:{date}:*
```

### 3.1 为什么同时用表和 Redis

数据库表用于：

- 可追溯
- 可按时间范围查询
- 可排查具体失败原因

Redis 计数器用于：

- 今日实时统计
- 降低查询成本
- 快速展示看板数字卡片

如果想先简单落地，也可以只做数据库表；但考虑看板性能，建议保留 Redis counter。

---

## 四、指标定义

### 4.1 今日 AI 调用次数

定义：当天所有 AI 业务接口成功进入 Controller 的次数。

统计范围：

- `chat`
- `code_analysis`
- `question_parse`
- `wrong_question`

建议事件类型：

```text
AI_CALL
```

Redis Key：

```text
metrics:ai:call:{yyyyMMdd}
metrics:ai:call:{yyyyMMdd}:{module}
```

模块枚举：

```text
chat
code_analysis
question_parse
wrong_question
```

### 4.2 今日被限流次数

定义：`RateLimitInterceptor` 中任一 AI 限流规则触发拒绝的次数。

建议事件类型：

```text
AI_RATE_LIMITED
```

Redis Key：

```text
metrics:ai:rate_limited:{yyyyMMdd}
metrics:ai:rate_limited:{yyyyMMdd}:{ruleKey}
```

来源：

```java
RateLimitInterceptor.checkRateLimit(...)
```

### 4.3 各 AI 模块调用分布

来自：

```text
metrics:ai:call:{yyyyMMdd}:chat
metrics:ai:call:{yyyyMMdd}:code_analysis
metrics:ai:call:{yyyyMMdd}:question_parse
metrics:ai:call:{yyyyMMdd}:wrong_question
```

前端展示：

- 饼图
- 柱状图

### 4.4 平均响应耗时 / 最大响应耗时

定义：每次 AI 业务请求从 Service 开始到返回结果的耗时。

建议事件字段：

```text
duration_ms
```

统计方式：

```sql
SELECT
  AVG(duration_ms) AS avgDurationMs,
  MAX(duration_ms) AS maxDurationMs
FROM ai_observation_event
WHERE event_type = 'AI_CALL'
  AND createTime >= CURDATE();
```

注意：

- 流式接口可统计到流结束时的总耗时。
- advanced Agent Loop 流式目前是一次性返回，统计逻辑较简单。

### 4.5 Agent Loop 平均步数

来源：

```text
ai_agent_trace_log
```

统计方式：

```sql
SELECT AVG(step_count) AS avgAgentSteps
FROM (
  SELECT chat_id, COUNT(*) AS step_count
  FROM ai_agent_trace_log
  WHERE createTime >= CURDATE()
  GROUP BY chat_id
) t;
```

注意：

- 当前只覆盖 `ai.agent.mode = advanced` 的请求。
- simple 模式没有显式 step。

### 4.6 工具调用次数 TopN

来源：

```text
ai_agent_trace_log.tool_name
```

统计方式：

```sql
SELECT tool_name, COUNT(*) AS callCount
FROM ai_agent_trace_log
WHERE createTime >= CURDATE()
  AND tool_name IS NOT NULL
GROUP BY tool_name
ORDER BY callCount DESC
LIMIT 10;
```

### 4.7 工具失败次数

来源：

```text
ai_agent_trace_log.tool_success
```

统计方式：

```sql
SELECT COUNT(*) AS failedToolCalls
FROM ai_agent_trace_log
WHERE createTime >= CURDATE()
  AND tool_name IS NOT NULL
  AND tool_success = 0;
```

### 4.8 RAG 检索为空次数

定义：RAG 检索执行后，没有返回有效上下文。

建议事件类型：

```text
RAG_EMPTY
```

埋点位置：

- `OJKnowledgeRetriever.retrieve(...)`
- `OJKnowledgeRetriever.retrieveAsContents(...)`
- `AgentLoopService.retrieveRagContext(...)`

判定条件：

```text
matches 为空
或拼接后的 context 为空
或返回“无相关知识点”
```

### 4.9 Rerank 调用次数 / 失败次数

来源：

```java
RerankService.rerank(...)
```

建议事件类型：

```text
RERANK_CALL
RERANK_FAILED
```

计数规则：

- 进入真实 API 调用前记录 `RERANK_CALL`
- HTTP 非 2xx 或异常时记录 `RERANK_FAILED`

### 4.10 LinkValidationFilter 移除假链接次数

来源：

```java
LinkValidationFilter
```

当前已有日志：

```text
[LinkFilter] removed fake link
```

建议新增事件类型：

```text
LINK_REMOVED
```

每移除一个假链接，计数 +1。

---

## 五、数据库设计

新增通用观测事件表：

```sql
CREATE TABLE IF NOT EXISTS ai_observation_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL COMMENT '事件类型',
    module       VARCHAR(64)  COMMENT 'AI模块：chat/code_analysis/question_parse/wrong_question/rag/rerank/link_filter',
    user_id      BIGINT       COMMENT '用户ID',
    chat_id      VARCHAR(64)  COMMENT '会话ID',
    request_id   VARCHAR(64)  COMMENT '请求ID，可与日志链路关联',
    target_key   VARCHAR(128) COMMENT '目标对象，如 toolName/ruleKey/questionId',
    success      TINYINT      DEFAULT 1 COMMENT '是否成功',
    duration_ms  BIGINT       DEFAULT 0 COMMENT '耗时',
    count_value  INT          DEFAULT 1 COMMENT '事件计数值',
    detail       TEXT         COMMENT '补充信息，JSON或短文本',
    createTime   DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    INDEX idx_type_time (event_type, createTime),
    INDEX idx_module_time (module, createTime),
    INDEX idx_user_time (user_id, createTime),
    INDEX idx_chat_id (chat_id)
) COMMENT 'AI业务可观测事件表' COLLATE = utf8mb4_unicode_ci;
```

说明：

- 不建议为每个指标单独建表，早期维护成本高。
- `detail` 保存短 JSON 即可，不要塞完整 prompt / answer，避免数据膨胀。
- 大文本仍然由 `ai_chat_record` 和 `ai_agent_trace_log` 保存。

---

## 六、后端新增文件建议

```text
src/main/java/com/XI/xi_oj/ai/observability/
├── AiObservationEventType.java
├── AiObservationModule.java
├── AiObservationRecorder.java
├── AiMetricsSnapshot.java
├── AiToolMetric.java
└── AiModuleCallMetric.java

src/main/java/com/XI/xi_oj/model/entity/
└── AiObservationEvent.java

src/main/java/com/XI/xi_oj/mapper/
└── AiObservationEventMapper.java

src/main/java/com/XI/xi_oj/controller/
└── AiObservabilityController.java
```

---

## 七、核心组件设计

### 7.1 AiObservationRecorder

职责：

- 写 Redis 今日计数器
- 异步写数据库事件
- 对业务代码提供统一埋点入口

建议方法：

```java
public void recordCall(String module, Long userId, String chatId, long durationMs, boolean success);

public void recordRateLimited(String ruleKey, Long userId, String detail);

public void recordRagEmpty(String module, String query);

public void recordRerankCall(String query, int candidateCount);

public void recordRerankFailed(String query, String reason);

public void recordLinkRemoved(Long questionId, String label);
```

### 7.2 Redis Key 设计

```text
metrics:ai:call:{yyyyMMdd}
metrics:ai:call:{yyyyMMdd}:{module}
metrics:ai:rate_limited:{yyyyMMdd}
metrics:ai:rate_limited:{yyyyMMdd}:{ruleKey}
metrics:ai:rag_empty:{yyyyMMdd}
metrics:ai:rerank_call:{yyyyMMdd}
metrics:ai:rerank_failed:{yyyyMMdd}
metrics:ai:link_removed:{yyyyMMdd}
```

TTL：

```text
3 天
```

原因：

- 看板主要看今日和最近几天。
- 长期趋势后续从数据库聚合。

---

## 八、埋点位置

### 8.1 AI 调用次数和响应耗时

推荐在 Service 层统计，而不是 Controller 层。

原因：

- 能覆盖同步和流式实际执行耗时。
- 更接近业务完成时间。

建议位置：

- `AiChatServiceImpl.chat`
- `AiChatServiceImpl.chatStream`
- `AiCodeAnalysisServiceImpl`
- `AiQuestionParseServiceImpl`
- `AiWrongQuestionServiceImpl`

示例：

```java
long start = System.currentTimeMillis();
try {
    // 原业务逻辑
    recorder.recordCall("chat", userId, chatId, System.currentTimeMillis() - start, true);
} catch (Exception e) {
    recorder.recordCall("chat", userId, chatId, System.currentTimeMillis() - start, false);
    throw e;
}
```

流式接口注意：

```java
return rawStream
        .doOnComplete(() -> recorder.recordCall(...))
        .doOnError(e -> recorder.recordCall(..., false));
```

### 8.2 限流次数

位置：

```java
RateLimitInterceptor.checkRateLimit(...)
```

在即将抛出 `TOO_MANY_REQUESTS` 前记录：

```java
recorder.recordRateLimited(type.getRuleKey(), userId, clientIp);
```

### 8.3 RAG 检索为空

位置：

```java
OJKnowledgeRetriever.retrieve(...)
OJKnowledgeRetriever.retrieveAsContents(...)
AgentLoopService.retrieveRagContext(...)
```

建议优先在 `OJKnowledgeRetriever` 内记录，避免重复。

### 8.4 Rerank 调用和失败

位置：

```java
RerankService.rerank(...)
```

记录点：

- API 调用前：`RERANK_CALL`
- HTTP 非 2xx：`RERANK_FAILED`
- catch 异常：`RERANK_FAILED`

### 8.5 假链接移除

位置：

```java
LinkValidationFilter
```

当前已有 warning 日志，新增 recorder 即可。

---

## 九、看板接口设计

新增控制器：

```text
GET /api/admin/ai/observability/summary
GET /api/admin/ai/observability/tool-top
GET /api/admin/ai/observability/recent-events
GET /api/admin/ai/observability/agent-trace
```

全部需要管理员权限：

```java
@AuthCheck(mustRole = "admin")
```

### 9.1 总览接口

```text
GET /api/admin/ai/observability/summary?date=2026-04-29
```

返回示例：

```json
{
  "date": "2026-04-29",
  "todayAiCalls": 128,
  "todayRateLimited": 6,
  "avgDurationMs": 1830,
  "maxDurationMs": 9200,
  "avgAgentSteps": 2.4,
  "toolFailedCount": 3,
  "ragEmptyCount": 12,
  "rerankCallCount": 31,
  "rerankFailedCount": 2,
  "linkRemovedCount": 8,
  "moduleDistribution": [
    {"module": "chat", "count": 80},
    {"module": "code_analysis", "count": 20},
    {"module": "question_parse", "count": 18},
    {"module": "wrong_question", "count": 10}
  ]
}
```

### 9.2 工具 TopN

```text
GET /api/admin/ai/observability/tool-top?date=2026-04-29&limit=10
```

来源：

```text
ai_agent_trace_log
```

返回示例：

```json
[
  {"toolName": "search_questions", "callCount": 42, "failedCount": 1},
  {"toolName": "query_question_info", "callCount": 28, "failedCount": 0}
]
```

### 9.3 最近事件

```text
GET /api/admin/ai/observability/recent-events?limit=50
```

用途：

- 排查最近的 RAG 空检索
- 排查 Rerank 失败
- 排查限流触发
- 排查假链接移除

---

## 十、前端页面建议

新增页面：

```text
frontend/OJ_frontend/src/views/admin/AiObservabilityView.vue
```

路由：

```text
/admin/ai-observability
```

页面布局：

```text
顶部：日期选择器 + 刷新按钮

第一行数字卡片：
- 今日 AI 调用次数
- 今日被限流次数
- 平均响应耗时
- 最大响应耗时
- RAG 空检索次数
- 假链接移除次数

第二行图表：
- AI 模块调用分布
- 工具调用 TopN

第三行表格：
- 最近观测事件
- Agent Trace 最近会话
```

图表库：

- 如果项目已接入 ECharts，使用 ECharts。
- 如果没有，先用 Arco Table + Statistic + Progress 做轻量版。

---

## 十一、实施步骤

### 阶段 1：后端指标事件表

1. 新增 `ai_observation_event` SQL。
2. 新增 `AiObservationEvent` 实体。
3. 新增 `AiObservationEventMapper`。
4. 新增 `AiObservationRecorder`。

验收：

- 调用 recorder 后数据库有事件。
- Redis 今日计数器递增。

### 阶段 2：核心埋点

按优先级接入：

1. `RateLimitInterceptor`：记录限流次数。
2. `AiChatServiceImpl`：记录 chat 调用次数和耗时。
3. `RerankService`：记录 rerank 调用 / 失败。
4. `OJKnowledgeRetriever`：记录 RAG 空检索。
5. `LinkValidationFilter`：记录假链接移除。
6. 代码分析 / 题目解析 / 错题分析 Service：记录模块调用和耗时。

验收：

- 看板 summary 能展示非 0 数据。
- 手动触发限流后，限流次数 +1。
- 手动让 RAG 搜不到内容后，RAG 空检索 +1。

### 阶段 3：后台接口

1. 新增 `AiObservabilityController`。
2. 新增 summary 接口。
3. 新增 tool-top 接口。
4. 新增 recent-events 接口。
5. 新增 agent-trace 查询接口。

验收：

- 管理员能访问。
- 普通用户不能访问。
- 接口能按日期过滤。

### 阶段 4：前端看板

1. 新增 `AiObservabilityView.vue`。
2. 新增 API 文件或扩展 `aiConfig.ts`。
3. 后台菜单加入入口。
4. 接入数字卡片和表格。

验收：

- 能看到今日 AI 调用次数。
- 能看到模块分布。
- 能看到工具调用 TopN。
- 能看到最近 RAG / Rerank / LinkFilter 事件。

---

## 十二、注意事项

### 12.1 不要记录敏感内容

不建议写入：

- API Key
- 完整用户代码
- 完整 Prompt
- 完整大模型回答

可以写入：

- 模块名
- 耗时
- 成功/失败
- 工具名
- 规则 key
- 简短错误信息

### 12.2 事件表需要定期清理

建议保留：

```text
30 天
```

后续可加定时任务：

```sql
DELETE FROM ai_observation_event
WHERE createTime < DATE_SUB(NOW(), INTERVAL 30 DAY);
```

### 12.3 不要让监控影响主链路

Recorder 内部必须 catch 异常：

```java
try {
    // write metrics
} catch (Exception e) {
    log.warn("[AiMetrics] record failed", e);
}
```

监控失败不能导致 AI 调用失败。

---

## 十三、面试表达

可以这样描述：

> 我没有一开始引入 Prometheus/Grafana 这类完整监控体系，而是先基于业务数据库和 Redis 计数器实现了一套轻量 AI 可观测性看板。它能展示今日 AI 调用量、限流次数、模块分布、响应耗时、Agent 平均步数、工具调用 TopN、RAG 空检索、Rerank 失败和虚假链接过滤次数。这样既能满足线上排查和后台管理，又避免在项目早期引入过重的监控组件。后续如果部署到生产环境，可以平滑接入 Micrometer，将这些业务指标暴露给 Prometheus，再用 Grafana 做系统级监控。

---

## 十四、推荐落地优先级

最小可用版本：

1. `ai_observation_event` 表
2. `AiObservationRecorder`
3. `RateLimitInterceptor` 限流埋点
4. `AiChatServiceImpl` 调用次数和耗时埋点
5. `AiObservabilityController.summary`
6. 前端数字卡片

增强版本：

1. Agent Trace 查询
2. 工具调用 TopN
3. RAG 空检索
4. Rerank 调用 / 失败
5. LinkFilter 假链接移除
6. 最近事件表格

最终版本：

1. 近 7 天趋势
2. 用户维度 TopN
3. 慢请求明细
4. 错误原因分布
5. Micrometer + Prometheus + Grafana

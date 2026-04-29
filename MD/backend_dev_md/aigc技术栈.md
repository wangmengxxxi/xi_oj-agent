# XI OJ AIGC 技术栈速查

更新时间：2026-04-22（基于代码实际实现校准）

---

## 场景一：简历/项目介绍（一行流）

AIGC 模块技术栈：LangChain4j + 多供应商热切换（百炼/DeepSeek/OpenAI/智谱等，OpenAI 兼容协议）· Tool Calling（查题/判题/查错题）· Redis + MySQL 双层 Chat Memory 持久化 · 多实例 Agent 分工 · SSE 流式统一协议 · RAG 双向量库隔离检索（content_type / difficulty 元数据精准过滤 + Redis 缓存）· AOP 全局开关 + 分层限流 · ai_config 配置中心与 Prompt 动态治理 · API Key AES 加密存储 · 艾宾浩斯遗忘曲线错题复习

---

## 场景二：文档/README 展开描述

### 核心框架

- LangChain4j 1.0.0-beta3（AiServices / ChatMemoryStore / EmbeddingStore / Tool 注解体系）
- 阿里 DashScope（通义千问）：Chat Model + Streaming Chat Model + Embedding Model（text-embedding-v3，1024 维）
- Milvus 向量数据库（COSINE 度量）
- Spring WebFlux Reactor（Flux<ServerSentEvent>）

### Tool Calling（工具调用）

- 3 个业务工具：`query_question_info`（题目查询）/ `judge_user_code`（代码评测）/ `query_user_wrong_question`（错题查询）
- 使用 `@Tool` + `@P` 注解声明工具名称、描述和参数，LLM 自动决策调用时机
- 仅 `OJChatAgent` 绑定工具，其余 Agent 无工具能力，职责隔离
- AI 判题通过 `source="ai_tool"` 标记隔离，不污染用户提交统计

### Chat Memory（多轮对话记忆持久化）

- Redis L1 热记忆窗口（TTL 120 分钟）+ MySQL L2 冷历史回源，双层持久化
- `MessageWindowChatMemory` 滑动窗口上限 20 条消息，控制 token 增长
- Redis miss 时自动从 MySQL 回源最近 10 轮对话，重建记忆上下文
- `memoryId = userId:chatId`，同 chatId 不同用户记忆隔离
- 流式场景 `@Async` 异步写库，不阻塞 SSE 推送
- 仅 `OJChatAgent` 携带记忆，其余 Agent 无状态

### 多实例 Agent 分工

| Bean | 能力 | 场景 |
|---|---|---|
| `OJChatAgent` | Memory + RAG + Tools + SSE | AI 多轮问答 |
| `OJQuestionParseAgent` | RAG + SSE（无状态） | 题目解析 + 相似题推荐 |
| `OJStreamingService` | 纯流式适配（无状态） | 代码分析 / 错题分析 |

- `AiAgentFactory` 统一生产所有 AI Bean，模型参数从 `ai_config` 表动态读取
- `OJStreamingService` 通过 Lambda + `Flux.create()` + `StreamingChatResponseHandler` 桥接 Reactor 响应式流

### SSE 流式统一协议

- 所有 AI 流式接口统一输出 `Flux<ServerSentEvent<String>>`
- 普通 token：`data: {"d":"<token>"}`
- 结束事件：`data: {"done":true}`
- 错误事件：`event: error` + `data: {"error":"<msg>"}`
- 前端一个 SSE 解析器复用所有 AI 流式接口

### RAG 检索增强生成

- 双向量库隔离：`oj_knowledge`（知识库）+ `oj_question`（题目向量），避免检索污染
- 三种检索方法：
  - `retrieve()`：通用知识检索，用于 Agent 自动 RAG
  - `retrieveByType()`：按 `content_type` 精准过滤（如"代码模板,错题分析"），采用"请求 topK×2 再客户端筛"策略
  - `retrieveSimilarQuestions()`：相似题推荐，支持 `difficulty` 过滤 + 排除自身，最多返回 4 题
- RAG 结果 Redis 缓存（TTL 60 分钟），cache key 使用 MD5 哈希保证固定长度
- 知识导入 / 向量重建后自动清除 RAG 缓存
- 知识库全生命周期：启动自动导入（CommandLineRunner）+ 管理员 API 导入 + 每日 2:00 题目向量全量重建

### AI 配置中心与 Prompt 动态治理

- `ai_config` 表存储模型参数、RAG 参数、Prompt 模板，管理员后台实时修改
- Redis 缓存配置值（TTL 5 分钟），`__NULL__` 占位符防缓存穿透
- Prompt 读取三层防护：空值回退 → 乱码检测（`looksLikeMojibake` 检测 GBK/UTF-8 误解码）→ 代码默认值兜底
- API Key 仅从环境变量注入，禁止通过接口修改

### 全局开关 + 分层限流

- AOP 切面（`AiGlobalSwitchAspect`）通配拦截所有 `Ai*Controller`，一键关闭全部 AI 功能
- 六维限流策略（`@RateLimit` + `RateLimitInterceptor`）：
  - 分钟级共享：`AI_USER_MINUTE`（10次）、`AI_IP_MINUTE`（30次）
  - 日级按模块：`AI_CHAT_USER_DAY`（100次）、`AI_CODE_USER_DAY`（30次）、`AI_QUESTION_USER_DAY`（50次）、`AI_WRONG_USER_DAY`（30次）
- 限流规则存储在 `rate_limit_rule` 表，可动态调整

### 错题集与复习机制

- 判题后自动收集错题（`WrongQuestionCollector`），排除 Accepted 和 `source=ai_tool`
- 同用户同题 Upsert，更新时重置分析状态
- 用户按需触发 AI 错题分析，输出结构化分析 + 复习计划 + 相似题推荐
- 艾宾浩斯遗忘曲线复习调度：第 1 次 → 1 天后，第 2 次 → 3 天后，第 3 次+ → 7 天后

### ReAct 推理规范

- `OJChatAgent` 的 `@SystemMessage` 内置 ReAct 思考链
- 引导模型：明确信息需求 → 决策是否调用工具 → 基于工具结果生成回答
- 减少幻觉跳结论，提升工具调用准确率

### 其他工程化优化

- 聊天历史游标分页：`(createTime, id)` 复合游标，替代 OFFSET 翻页
- 代码分析自动评分提取：双正则模式匹配 AI 输出中的评分（"综合评分：N" / "N/10"）
- 异步持久化：流式场景 `doOnComplete` + `@Async` 写库，不阻塞用户体验
- 错题收集异常隔离：try-catch 包裹，失败不影响主判题流程

---

## 场景三：面试口述版（2 分钟）

项目的 AIGC 模块基于 LangChain4j 和阿里 DashScope 构建，核心做了这几件事：

第一，多 Agent 分工。OJChatAgent 是完整交互型 Agent，带记忆、RAG 和三个工具（查题、判题、查错题）；OJQuestionParseAgent 是无状态的题目解析 Agent；OJStreamingService 是纯流式适配层。所有 Bean 由 AiAgentFactory 统一生产，模型参数从数据库动态读取。

第二，聊天记忆持久化。不是简单的内存态，而是 Redis + MySQL 双层架构。Redis 做热记忆窗口（TTL 2 小时），MySQL 做冷历史回源。Redis miss 时自动从 MySQL 加载最近 10 轮恢复上下文，滑动窗口上限 20 条控制 token 增长。流式场景用 @Async 异步写库不阻塞 SSE。

第三，RAG 检索做了几个优化。双向量库隔离（知识库和题目分开存），metadata 精准过滤（content_type 和 difficulty），检索结果 Redis 缓存（TTL 1 小时）降低重复检索开销。相似题推荐支持难度过滤和排除自身。

第四，全局治理。AOP 切面一键关闭所有 AI 功能；六维限流（分钟级共享 + 日级按模块）控制成本；ai_config 配置中心支持 Prompt 热更新，还做了乱码检测防护和缓存穿透防护。

第五，SSE 统一协议。所有流式接口统一输出 d/done/error 三种 JSON 事件，前端一个解析器复用全部接口。

第六，错题集。判题后自动收集，AI 工具调用的测试提交通过 source 字段隔离不入错题本，分析后基于艾宾浩斯遗忘曲线（1/3/7 天）安排复习节奏。

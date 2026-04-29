# XI OJ AIGC 进阶优化方案：RAG 深度优化 + 自定义 Agent Loop

更新时间：2026-04-29
前置依赖：已完成 AIGC 基础模块（参见 `aigc_engineering_implementation_guide.md`）

---

## 〇、优化相关概念速查（先读这里）

本章解释文档中涉及的所有非基础概念。如果你已经熟悉，可以跳过直接看第一章。

---

### 0.1 Bi-Encoder vs Cross-Encoder — 两种语义匹配架构

这是理解 Rerank 的前置知识。语义匹配（判断"query 和 document 有多相关"）有两种主流架构：

**Bi-Encoder（双塔模型）— 你现在用的向量检索就是这个**

```mermaid
flowchart LR
    Q["query 文本"] --> E1["Encoder A"]
    D["document 文本"] --> E2["Encoder B"]
    E1 --> V1["query 向量"]
    E2 --> V2["document 向量"]
    V1 --> COS["余弦相似度"]
    V2 --> COS
    COS --> S["相似度分数"]
```

工作方式：query 和 document 分别独立编码成向量，然后算余弦相似度。两边互不干扰。

优点：document 的向量可以提前算好存到 Milvus，检索时只需要算一次 query 向量，然后做向量近邻搜索，速度极快（毫秒级从百万文档中找 TopK）。

缺点：因为 query 和 document 是分开编码的，模型看不到它们之间的交互关系。比如 query 是"背包问题边界怎么处理"，document 是"二分查找边界条件"，两者都有"边界"这个词，向量会比较接近，但实际上一个是 DP 一个是二分，并不相关。

**Cross-Encoder（交叉编码器）— Rerank 用的就是这个**

```mermaid
flowchart LR
    Q["query 文本"] --> CONCAT["拼接"]
    D["document 文本"] --> CONCAT
    CONCAT --> E["单个 Encoder<br/>同时看 query + document"]
    E --> S["相关性分数<br/>（0~1）"]
```

工作方式：把 query 和 document 拼在一起，送进同一个模型，模型同时看到两段文本的所有 token，输出一个相关性分数。

优点：因为模型能同时看到 query 和 document 的每个词，能理解它们之间的细粒度语义关系。上面那个"背包边界" vs "二分边界"的例子，Cross-Encoder 能分辨出来它们讨论的是不同算法。

缺点：每对 (query, document) 都要过一次模型，不能提前算。如果有 10 万篇文档，就要跑 10 万次，太慢了。

**所以实际工程中两者配合使用：**

```
Bi-Encoder（快但粗）→ 从 10 万篇中取 Top10 → Cross-Encoder（慢但准）→ 从 10 个中精选 Top3
```

这就是"粗排 + 精排"的两阶段检索架构。

---

### 0.2 Rerank（重排序）

Rerank 就是"对已有的检索结果重新排序"。

你现在的 RAG 流程：向量检索返回 Top5，按向量相似度从高到低排，直接用。问题是向量相似度不等于真正的相关性（上面解释过了）。

加了 Rerank 之后：向量检索先多取一些（比如 Top10），然后用 Cross-Encoder 对这 10 个结果重新打分排序，取最相关的 Top3。

类比：你在搜索引擎搜"Java 面试题"，搜索引擎先用倒排索引快速找到 1000 个候选页面（粗排），然后用更精细的排序模型对这 1000 个重新排序（精排），最后展示给你排名最高的 10 个。Rerank 就是这里的"精排"步骤。

DashScope 提供了现成的 Rerank API（模型名 `gte-rerank`），你不需要自己训练模型，直接调 API 就行。

---

### 0.3 Query Rewrite（查询改写）

用户输入的 query 通常是口语化的、信息稀疏的。Query Rewrite 就是在检索之前，用一个 LLM 把用户的 query 改写成更适合检索的形式。

```
原始 query:  "dp 背包"
改写后:      "动态规划 0-1 背包问题的状态转移方程、边界初始化与空间优化方法"
```

为什么有用：
- "dp" 是缩写，Embedding 模型可能不认识或者编码质量低。改写成"动态规划"后向量质量更高。
- "背包"太笼统。改写后补充了"状态转移方程"、"边界初始化"等关键术语，让向量包含更多语义信息，检索时更容易匹配到相关文档。

为什么用"小模型"：改写任务很简单（就是扩写一句话），不需要用贵的大模型。用 `qwen-turbo` 这种便宜快速的模型就够了，单次调用成本大约是主模型的 1/10，延迟也只有 100~200ms。

---

### 0.4 Recall@K 和 MRR — RAG 评估指标

这两个是信息检索领域最常用的评估指标，用来量化"检索结果好不好"。

**Recall@K（召回率@K）**

含义：在检索返回的前 K 个结果中，有多少比例的"正确答案"被找到了。

```
假设某个 query 有 3 个正确答案（ground truth）：文档 A、B、C
你的检索返回了 Top5：[A, X, B, Y, Z]

命中了 A 和 B（2 个），漏了 C
Recall@5 = 2 / 3 = 0.67
```

Recall@K 越高越好，1.0 表示所有正确答案都被找到了。

**MRR（Mean Reciprocal Rank，平均倒数排名）**

含义：第一个正确结果出现在第几位，取倒数，然后对所有 query 求平均。

```
query 1：第一个正确结果在第 1 位 → 1/1 = 1.0
query 2：第一个正确结果在第 3 位 → 1/3 = 0.33
query 3：第一个正确结果在第 2 位 → 1/2 = 0.5

MRR = (1.0 + 0.33 + 0.5) / 3 = 0.61
```

MRR 越高越好，1.0 表示每次检索第一个结果就是正确的。MRR 关注的是"用户最先看到的结果好不好"。

**为什么需要这两个指标：**
- 没有指标，优化就是"感觉变好了"，面试时说不出具体数据。
- 有了指标，可以说"加了 Query Rewrite 后 Recall@5 从 0.62 提升到 0.78"，这比"效果不错"有说服力得多。

---

### 0.5 Ground Truth（标准答案集）

在 RAG 评估中，ground truth 就是"对于这个 query，哪些文档/题目是真正相关的"。

你需要人工标注一批数据：

```json
{
  "query": "二分查找边界条件",
  "relevant_document_ids": ["知识点_二分查找", "错题分析_二分边界"],
  "relevant_question_ids": [5, 12, 23]
}
```

这批数据就是 ground truth。有了它，才能计算 Recall@K 和 MRR。

好消息是你的 OJ 项目天然适合构造 ground truth — 题目有标签（tags）、有难度（difficulty），可以按标签分组，每组选几个代表性 query，标注对应的相关题目 ID。不需要标注几千条，30~50 条就够用了。

---

### 0.6 ReAct（Reasoning + Acting）

ReAct 是一种让 LLM 交替进行"思考"和"行动"的推理模式。

```
用户: 帮我看看第5题我哪里写错了

Thought: 用户想分析第5题的错误，我需要先查题目信息。
Action: query_question_info("5")
Observation: 题目ID：5, 标题：两数之和, ...

Thought: 题目信息拿到了，接下来查用户的错题记录。
Action: query_user_wrong_question(12345, 5)
Observation: 错误代码：..., 判题结果：Wrong Answer

Thought: 已有题目信息和错题记录，可以分析错误原因了。
Answer: 你的代码在边界条件处理上有问题...
```

每一步都是：Think（想清楚需要什么）→ Act（调用工具获取信息）→ Observe（看工具返回了什么）→ 再 Think → ...直到信息足够给出最终回答。

你现在的 `OJChatAgent` 通过 `@SystemMessage` 里的提示词要求模型用 ReAct 模式，但推理循环本身是 LangChain4j 框架自动控制的（黑盒）。方向 A 的优化就是把这个循环自己写出来，这样可以加入重试、步数限制、日志记录等控制逻辑。

---

### 0.7 Agent Loop（Agent 推理循环）

Agent Loop 就是 Agent 的"主循环"。一个 Agent 本质上就是一个 while 循环：

```python
# 伪代码
while step < MAX_STEPS:
    llm_output = llm.chat(history)       # 让 LLM 思考
    if llm_output.has_answer():           # 如果 LLM 给出了最终回答
        return llm_output.answer          # 结束
    if llm_output.has_tool_call():        # 如果 LLM 想调用工具
        result = execute_tool(...)        # 执行工具
        history.append(result)            # 把结果加入历史
    step += 1
return "信息不足，基于已有信息回答..."      # 超过最大步数，强制结束
```

LangChain4j 的 `AiServices` 帮你封装了这个循环，你只需要声明接口和工具，框架自动跑。好处是简单，坏处是你控制不了中间过程（工具失败了怎么办？模型陷入死循环了怎么办？）。

自定义 Agent Loop 就是自己写这个 while 循环，好处是完全可控。

---

### 0.8 Tool Calling（工具调用 / Function Calling）

让 LLM 在对话过程中调用外部函数获取信息或执行操作。

你的项目里有 11 个工具：
- `query_question_info`：按关键词或 ID 查题目信息
- `judge_user_code`：提交代码判题
- `query_user_wrong_question`：查某道题的错题记录
- `search_questions`：按关键词/标签/难度搜索题目列表
- `find_similar_questions`：基于向量检索查找相似题目
- `list_user_wrong_questions`：查询用户所有错题列表
- `query_user_submit_history`：查询用户提交历史
- `query_user_mastery`：分析用户各知识点掌握情况（AC率、错题数）
- `get_question_hints`：获取题目分层提示（考点→方向→框架），用于引导式教学
- `run_custom_test`：用自定义输入测试用户代码并与标准答案对比
- `diagnose_error_pattern`：分析用户错题的系统性错误模式

LLM 不是直接执行这些函数，而是输出"我想调用 xxx 工具，参数是 yyy"，然后由你的代码解析这个意图、执行函数、把结果返回给 LLM。

LangChain4j 的 `@Tool` 注解自动完成了这个过程。自定义 Agent Loop 里你需要自己解析 LLM 输出中的工具调用意图。

> 注意：自定义 Agent Loop 只是替换了 LangChain4j 最上层的 AiServices 自动调度，底层的 `ChatModel`（模型调用）、`EmbeddingModel` + `EmbeddingStore`（向量检索）等基础设施仍然使用 LangChain4j。这不是"脱离框架"，而是"在框架基础设施之上自己控制编排逻辑"。

---

### 0.9 可观测性（Observability）

在 Agent 场景下，可观测性指的是"能看到 Agent 每一步做了什么决策"。

为什么重要：Agent 的行为不像普通代码那样确定性执行，LLM 每次可能做出不同的决策。如果用户反馈"AI 回答不对"，你需要能追溯：
- 它思考了什么？
- 调用了哪个工具？为什么选这个工具？
- 工具返回了什么？
- 它是怎么基于工具结果得出最终回答的？

没有可观测性，Agent 就是一个黑盒，出了问题只能猜。有了可观测性（决策链路日志），可以精确定位是"检索结果不好"还是"模型推理错误"还是"工具执行失败"。

---

### 0.10 退避重试（Exponential Backoff）

工具调用可能因为网络抖动、服务超时等原因失败。退避重试就是"失败后等一会儿再试，每次等的时间越来越长"。

```
第 1 次失败 → 等 500ms → 重试
第 2 次失败 → 等 1000ms → 重试
第 3 次失败 → 放弃，返回错误信息给 LLM
```

等待时间递增是为了避免"服务刚挂了你就疯狂重试把它打得更挂"。在 `ToolDispatcher` 里实现为 `sleep(500L * (attempt + 1))`。

---

### 0.11 灰度切换（Gradual Rollout）

不是一刀切地把旧方案换成新方案，而是通过配置控制，让新旧方案共存，逐步切换。

在本项目中：通过 `ai_config` 表的 `ai.agent.mode` 配置项，可以在 `simple`（现有 AiServices）和 `advanced`（自定义 Agent Loop）之间切换。先让少量用户用 `advanced`，观察稳定性，没问题再全量切换。

好处：新方案有 bug 时可以秒级回滚到旧方案，不影响线上用户。

---

### 0.12 概念关系总览

```mermaid
flowchart TD
    subgraph RAG优化["方向 B：RAG 深度优化"]
        QR["Query Rewrite<br/>口语 query → 检索友好 query"]
        BE["Bi-Encoder<br/>（你现在的向量检索）"]
        CE["Cross-Encoder<br/>（Rerank 精排用的模型）"]
        RR["Rerank<br/>= 用 Cross-Encoder 重排序"]
        GT["Ground Truth<br/>人工标注的正确答案"]
        RK["Recall@K / MRR<br/>评估指标"]

        QR --> BE
        BE -->|"粗排 Top10"| RR
        CE -.->|"提供精排能力"| RR
        RR -->|"精排 Top3"| PROMPT["注入 Prompt"]
        GT --> RK
    end

    subgraph Agent优化["方向 A：自定义 Agent Loop"]
        REACT["ReAct 模式<br/>Think → Act → Observe"]
        AL["Agent Loop<br/>自己写 while 循环"]
        TC["Tool Calling<br/>调用外部工具"]
        RETRY["退避重试<br/>失败后递增等待"]
        OBS["可观测性<br/>决策链路日志"]
        GRAY["灰度切换<br/>新旧方案共存"]

        REACT --> AL
        AL --> TC
        TC --> RETRY
        AL --> OBS
        AL --> GRAY
    end
```

---

## 一、优化全景

```mermaid
flowchart TB
    subgraph 方向B["方向 B：RAG 深度优化"]
        B1["Query Rewrite<br/>轻量模型改写用户 query"]
        B2["Cross-Encoder Rerank<br/>向量粗排 → 精排"]
        B3["离线评估体系<br/>Recall@K / MRR 量化"]
        B1 --> B2 --> B3
    end

    subgraph 方向A["方向 A：自定义 Agent Loop"]
        A1["手写 ReAct 推理循环<br/>think → act → observe"]
        A2["工具调用容错<br/>重试 / 降级 / 步数限制"]
        A3["Agent 可观测性<br/>决策链路日志"]
        A1 --> A2 --> A3
    end

    方向B --> 方向A
```

建议顺序：先 B 后 A。B 在现有 `OJKnowledgeRetriever` 上自然扩展，改动面小、效果可量化；A 需要重构 Agent 层，改动面大但面试区分度高。

---

## 二、方向 B：RAG 深度优化

### 2.1 当前 RAG 链路（已落地）

```mermaid
flowchart LR
    A["用户 query（原始）"] --> QR["QueryRewriteTransformer<br/>轻量模型改写"]
    QR --> B["DefaultQueryRouter<br/>路由到两个 Retriever"]
    B --> C1["ImageAwareContentRetriever<br/>oj_knowledge 知识点检索"]
    B --> C2["oj_question<br/>题目检索"]
    C1 --> D["RerankingContentAggregator<br/>Cross-Encoder 精排合并"]
    C2 --> D
    D --> E["Redis 缓存"]
    E --> F["注入 Prompt"]
    F --> G["LLM 生成回答"]
    G --> H["LinkValidationFilter<br/>链接真实性校验"]
```

> **架构说明**：`AiModelHolder.buildRetrievalAugmentor()` 使用 LangChain4j 内置的 `DefaultRetrievalAugmentor`，通过三个扩展点组装 RAG pipeline：
>
> 1. **QueryTransformer** → `QueryRewriteTransformer`：将用户口语化 query 改写为信息更丰富的检索 query（委托 `QueryRewriter`）
> 2. **QueryRouter** → `DefaultQueryRouter`：将改写后的 query 同时发给两个 `ContentRetriever`（`oj_knowledge` + `oj_question`）
> 3. **ContentAggregator** → `RerankingContentAggregator`：合并两个 Retriever 的结果后，调用 DashScope Rerank API 精排，取 TopN
>
> `oj_knowledge` 的 retriever 被 `ImageAwareContentRetriever` 装饰器包装：当检索到的 chunk 包含 `image_urls` metadata 时，自动在上下文中追加 `[RAG_SOURCE_IMAGES]` 段和 markdown 图片引用。
>
> `oj_question` 集合的向量文本包含题目 ID 和链接（如 `/view/question/42`），LLM 可以直接引用真实链接，避免编造。

**防幻觉机制（已落地）**：

当前架构在三个层面防止 LLM 编造虚假题目信息：

| 层面 | 机制 | 实现 |
|------|------|------|
| Prompt 层 | System Prompt 中 6 条硬约束 | 禁止编造题目名称/ID/链接，推荐题目必须先调用工具，搜不到则说"平台暂无相关练习题" |
| 架构层 | 双集合 RAG + 图片感知 | `oj_question` 向量文本内嵌真实题目 ID 和链接；`ImageAwareContentRetriever` 自动携带图片引用 |
| 输出层 | `LinkValidationFilter` 后置过滤 | 正则匹配回答中所有 `/view/question/{id}` 链接，逐一查库验证，不存在的链接自动剥离（流式场景下缓冲处理避免链接被截断） |

问题（优化前的原始架构，已全部修复）：
- ~~RAG 只查 `oj_knowledge` 集合，不查 `oj_question`，导致 LLM 推荐题目时编造不存在的题目名和 ID。~~（已通过 `DefaultQueryRouter` 双集合检索修复）
- ~~用户 query 通常是口语化短句（"二分怎么写"），语义信息稀疏，Embedding 质量低。~~（已通过 `QueryRewriteTransformer` + `QueryRewriter` 改写修复）
- ~~向量检索只做了相似度阈值过滤，没有精排，TopK 结果质量参差不齐。~~（已通过 `RerankingContentAggregator` + `RerankService` Cross-Encoder 精排修复）
- 没有量化指标，无法衡量优化效果。（离线评估体系待建设，见 2.5 节）

### 2.2 RAG Pipeline 组装代码（`AiModelHolder.buildRetrievalAugmentor()`）

以下是 `AiModelHolder` 中实际的 pipeline 组装逻辑，展示了 Query Rewrite、双集合路由、Rerank 三个扩展点如何串联：

```java
private RetrievalAugmentor buildRetrievalAugmentor() {
    int topK = Integer.parseInt(aiConfigService.getConfigValue("ai.rag.top_k"));
    double minScore = Double.parseDouble(aiConfigService.getConfigValue("ai.rag.similarity_threshold"));

    // 知识点检索器（带图片感知装饰器）
    EmbeddingStoreContentRetriever baseKnowledgeRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(this.embeddingModel)
            .maxResults(topK)
            .minScore(minScore)
            .build();
    ImageAwareContentRetriever knowledgeRetriever =
            new ImageAwareContentRetriever(baseKnowledgeRetriever);

    // 题目检索器
    EmbeddingStoreContentRetriever questionRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(questionEmbeddingStore)
            .embeddingModel(this.embeddingModel)
            .maxResults(topK)
            .minScore(minScore)
            .build();

    return DefaultRetrievalAugmentor.builder()
            .queryRouter(new DefaultQueryRouter(knowledgeRetriever, questionRetriever))
            .queryTransformer(new QueryRewriteTransformer(queryRewriter))       // Query Rewrite
            .contentAggregator(new RerankingContentAggregator(rerankService, topK)) // Rerank
            .build();
}
```

三个扩展点的执行顺序：`queryTransformer`（改写 query）→ `queryRouter`（路由到多个 retriever）→ `contentAggregator`（合并 + 精排）。

---

### 2.3 Query Rewrite 实现（已落地）

#### 2.3.1 设计思路

用一个便宜快速的模型（如 `qwen-turbo`）将用户口语化 query 改写为信息更丰富的检索 query。改写模型的 Prompt 固定且简单，不需要 RAG 和记忆。

实际接入方式：通过 LangChain4j 的 `QueryTransformer` 扩展点，在 `buildRetrievalAugmentor()` 中注册 `QueryRewriteTransformer`，自动作用于所有检索路径。

#### 2.3.2 文件清单

```
src/main/java/com/XI/xi_oj/ai/rag/
├── QueryRewriter.java              ← 核心改写逻辑 + 模型管理
├── QueryRewriteTransformer.java    ← LangChain4j QueryTransformer 适配器
└── ...
```

#### 2.3.3 QueryRewriteTransformer（适配器）

```java
@RequiredArgsConstructor
public class QueryRewriteTransformer implements QueryTransformer {

    private final QueryRewriter queryRewriter;

    @Override
    public Collection<Query> transform(Query query) {
        String rewritten = queryRewriter.rewrite(query.text());
        if (rewritten == null || rewritten.isBlank() || rewritten.equals(query.text())) {
            return List.of(query);
        }
        return List.of(Query.from(rewritten, query.metadata()));
    }
}
```

实现 LangChain4j 的 `QueryTransformer` 接口，将改写逻辑桥接到 RAG pipeline。改写失败或无变化时返回原始 query，保证降级安全。

#### 2.3.4 QueryRewriter（核心实现）

```java
@Component
@Slf4j
public class QueryRewriter {

    private volatile ChatModel rewriteModel;  // 缓存模型实例，配置变更时重建

    @PostConstruct
    private void init() {
        this.rewriteModel = buildRewriteModel();
    }

    @EventListener
    public void onConfigChanged(AiConfigChangedEvent event) {
        String key = event.getConfigKey();
        if (key.startsWith("ai.rewrite.") || key.equals("ai.provider.api_key_encrypted")
                || key.equals("ai.model.base_url")) {
            this.rewriteModel = buildRewriteModel();
            log.info("[QueryRewriter] rewriteModel rebuilt due to config change: {}", key);
        }
    }

    public String rewrite(String originalQuery) {
        // 长度过滤：< 4 字符或 > 50 字符不改写
        // Redis 缓存（TTL 2 小时）
        // 调用 rewriteModel.chat() 改写
        // sanitize() 清理输出（去前缀、截断、取首行）
    }

    private ChatModel buildRewriteModel() {
        // 从 ai_config 读取 api key（AES 解密）、base_url、model_name
        // 使用 OpenAiChatModel.builder() 构建，temperature=0.1 保证稳定性
    }
}
```

关键设计：
- **模型实例缓存**：`volatile ChatModel rewriteModel`，避免每次改写都新建模型实例
- **事件驱动重建**：监听 `AiConfigChangedEvent`，供应商/密钥/改写参数变更时自动重建
- **自管理模型**：不依赖 `AiModelHolder`，独立管理自己的轻量模型实例，避免循环依赖
- **输出清理**：`sanitize()` 去除模型可能输出的前缀（"改写后："）、多行内容、超长文本
- **Redis 缓存**：改写结果缓存 2 小时，相同 query 不重复调用模型

#### 2.3.5 Query Rewrite 效果示例

| 原始 query | 改写后 |
|---|---|
| 二分怎么写 | 二分查找算法的实现思路、边界条件处理与常见错误 |
| dp 背包 | 动态规划 0-1 背包问题的状态转移方程与空间优化 |
| 超时了怎么办 | 算法时间复杂度优化方法与常见 TLE 超时原因分析 |
| 递归栈溢出 | 递归算法栈溢出的原因分析与尾递归/迭代改写方案 |

---

### 2.4 Cross-Encoder Rerank 实现（已落地）

#### 2.4.1 设计思路

向量检索是"双塔模型"（query 和 document 分别编码再算相似度），速度快但精度有限。Cross-Encoder 是"交互模型"（query 和 document 拼接后一起编码），精度高但速度慢。

最佳实践：向量检索做粗排（快速从万级候选中取 Top10），Cross-Encoder 做精排（从 10 个里选最好的 3 个）。

DashScope 提供了 Rerank API（`gte-rerank`），可以直接调用。

实际接入方式：通过 LangChain4j 的 `ContentAggregator` 扩展点，在 `buildRetrievalAugmentor()` 中注册 `RerankingContentAggregator`，自动对所有 Retriever 的合并结果做精排。

#### 2.4.2 文件清单

```
src/main/java/com/XI/xi_oj/ai/rag/
├── RerankService.java              ← 核心 Rerank API 调用
├── RerankingContentAggregator.java ← LangChain4j ContentAggregator 适配器
└── ...
```

#### 2.4.3 RerankingContentAggregator（适配器）

```java
@RequiredArgsConstructor
public class RerankingContentAggregator implements ContentAggregator {

    private final RerankService rerankService;
    private final int fallbackTopN;
    private final DefaultContentAggregator delegate = new DefaultContentAggregator();

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> merged = delegate.aggregate(queryToContents);  // 先用默认策略合并
        String query = queryToContents.keySet().stream()
                .findFirst().map(Query::text).orElse("");
        return rerankService.rerank(query, merged, rerankService.topN(fallbackTopN));  // 再精排
    }
}
```

先委托 `DefaultContentAggregator` 合并多个 Retriever 的结果，再调用 `RerankService` 精排。这样 Rerank 自动作用于 `oj_knowledge` + `oj_question` 的合并结果。

#### 2.4.4 RerankService（核心实现）

```java
@Component
@Slf4j
public class RerankService {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final int MAX_DOCUMENT_CHARS = 1800;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public boolean enabled() {
        return Boolean.parseBoolean(config("ai.rerank.enabled", "false"));
    }

    public List<Content> rerank(String query, List<Content> contents, int topN) {
        if (!enabled() || contents == null || contents.size() <= 1) {
            return limit(contents, topN);  // 未启用或结果太少，直接截断返回
        }
        // 1. 提取文本 + 截断保护（MAX_DOCUMENT_CHARS）
        // 2. 构建 DashScope Rerank API 请求（Hutool JSONUtil）
        // 3. 调用 API，解析 relevance_score + index
        // 4. 按 score 降序排列，取 topN
        // 5. 失败时 fallback 到原始顺序截断
    }
}
```

关键设计：
- **操作 `List<Content>` 而非 `List<String>`**：rerank 后 metadata（`image_urls`、`question_id`）不会丢失
- **开关控制**：`ai.rerank.enabled` 配置项，可在后台一键关闭 Rerank
- **截断保护**：每个 document 最多 1800 字符，避免超出 Rerank API 限制
- **全链路降级**：API 调用失败、未启用、结果太少等场景都 fallback 到原始顺序截断
- **JDK HttpClient**：使用 `java.net.http.HttpClient`，连接超时 3s，读取超时 8s

#### 2.4.5 Rerank 前后对比（示意）

```
用户 query: "动态规划背包问题边界怎么处理"

【粗排 Top5（仅向量相似度）】
1. DP 0-1 背包状态转移方程        score=0.87
2. 贪心算法与 DP 的区别            score=0.85  ← 不太相关
3. DP 背包问题边界初始化与滚动数组  score=0.84
4. 二分查找边界条件处理            score=0.83  ← 不相关（"边界"语义干扰）
5. DP 完全背包与多重背包对比        score=0.82

【精排 Top3（Cross-Encoder）】
1. DP 背包问题边界初始化与滚动数组  relevance=0.95  ← 最相关
2. DP 0-1 背包状态转移方程        relevance=0.91
3. DP 完全背包与多重背包对比        relevance=0.78
```

精排把真正相关的"边界初始化"提到了第一位，过滤掉了"贪心算法"和"二分查找"这两个被向量相似度误召回的结果。

---

### 2.5 离线评估体系

#### 2.5.1 为什么需要离线评估

没有评估指标，所有优化都是"感觉变好了"。面试时能说出"Query Rewrite 让 Recall@5 从 0.62 提升到 0.78"比"我加了 Query Rewrite 效果不错"有说服力得多。

#### 2.5.2 评估指标

| 指标 | 含义 | 计算方式 |
|---|---|---|
| Recall@K | Top K 结果中包含相关文档的比例 | 命中数 / 相关文档总数 |
| MRR | 第一个相关结果的排名倒数的均值 | 1/rank 的平均值 |
| Precision@K | Top K 结果中相关文档的占比 | 相关命中数 / K |

#### 2.5.3 评估集构造方案

利用现有题目数据构造 ground truth：

```
评估集格式（JSON）：
[
  {
    "query": "二分查找的边界条件怎么处理",
    "relevant_tags": ["二分查找", "二分"],
    "relevant_content_types": ["知识点", "错题分析"],
    "expected_question_ids": [5, 12, 23]    // 相似题 ground truth
  },
  {
    "query": "动态规划背包问题",
    "relevant_tags": ["动态规划", "背包"],
    "relevant_content_types": ["知识点", "代码模板"],
    "expected_question_ids": [8, 15, 31]
  }
]
```

构造方式：
1. 从 `question` 表按 `tags` 分组，每组抽取 2~3 个代表性 query。
2. 人工标注每个 query 对应的相关知识类型和相似题 ID。
3. 建议评估集规模：30~50 条 query，覆盖主要算法类型。

#### 2.5.4 评估脚本（新增文件）

```
src/test/java/com/XI/xi_oj/ai/rag/
└── RagEvaluationTest.java    ← 新增
```

```java
/**
 * RAG 离线评估测试。
 * 非单元测试，需要连接 Milvus 和 Redis，建议在开发环境手动执行。
 */
@SpringBootTest
@Slf4j
public class RagEvaluationTest {

    @Resource
    private OJKnowledgeRetriever retriever;

    @Test
    public void evaluateRecallAtK() {
        List<EvalCase> cases = loadEvalCases("eval/rag_eval_cases.json");
        int k = 5;
        double totalRecall = 0;
        double totalMRR = 0;

        for (EvalCase evalCase : cases) {
            List<Long> retrieved = retriever.retrieveSimilarQuestions(
                    -1L, evalCase.getQuery(), null);

            Set<Long> relevantSet = new HashSet<>(evalCase.getExpectedQuestionIds());
            int hits = 0;
            int firstHitRank = 0;

            for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
                if (relevantSet.contains(retrieved.get(i))) {
                    hits++;
                    if (firstHitRank == 0) firstHitRank = i + 1;
                }
            }

            double recall = relevantSet.isEmpty() ? 0 :
                    (double) hits / relevantSet.size();
            double mrr = firstHitRank == 0 ? 0 : 1.0 / firstHitRank;

            totalRecall += recall;
            totalMRR += mrr;
            log.info("[Eval] query='{}', Recall@{}={}, MRR={}",
                    evalCase.getQuery(), k, recall, mrr);
        }

        log.info("[Eval] === 总结 ===");
        log.info("[Eval] 平均 Recall@{} = {}", k, totalRecall / cases.size());
        log.info("[Eval] 平均 MRR = {}", totalMRR / cases.size());
    }
}
```

#### 2.5.5 评估流程

```mermaid
flowchart TD
    A["准备评估集<br/>30~50 条 query + ground truth"] --> B["跑基线<br/>当前 RAG 链路"]
    B --> C["记录 Recall@K / MRR"]
    C --> D["开启 Query Rewrite"]
    D --> E["重新跑评估"]
    E --> F["开启 Rerank"]
    F --> G["重新跑评估"]
    G --> H["对比三组数据<br/>基线 vs +Rewrite vs +Rewrite+Rerank"]
```

---

### 2.6 方向 B 实施状态

| 步骤 | 改动文件 | 状态 | 说明 |
|---|---|---|---|
| 1 | `QueryRewriter.java` | ✅ 已完成 | Query Rewrite 核心逻辑 + 模型缓存 + 事件重建 |
| 2 | `QueryRewriteTransformer.java` | ✅ 已完成 | LangChain4j QueryTransformer 适配器 |
| 3 | `RerankService.java` | ✅ 已完成 | Cross-Encoder Rerank（DashScope API） |
| 4 | `RerankingContentAggregator.java` | ✅ 已完成 | LangChain4j ContentAggregator 适配器 |
| 5 | `AiModelHolder.buildRetrievalAugmentor()` | ✅ 已完成 | 组装 pipeline（QueryTransformer + QueryRouter + ContentAggregator） |
| 6 | `RagEvaluationTest.java` | 待开发 | 离线评估脚本 |
| 7 | `eval/rag_eval_cases.json` | 待开发 | 评估数据集 |
| 8 | 跑评估 | 待执行 | 量化 Recall@K / MRR 指标 |

---

## 三、方向 A：自定义 Agent Loop

### 3.1 当前 Agent 架构（现状）

```mermaid
flowchart LR
    A["用户 query"] --> B["OJChatAgent<br/>（AiServices 接口）"]
    B --> C["LangChain4j 框架<br/>自动调度 ReAct"]
    C --> D["OJTools<br/>查题/判题/查错题"]
    D --> C
    C --> E["最终回答"]
```

问题：
- 推理循环完全是 LangChain4j 黑盒，无法控制中间步骤。
- 工具调用失败（如判题超时）没有重试机制，直接报错。
- 没有最大步数限制，理论上模型可能陷入死循环。
- 没有决策日志，无法追溯"为什么调了这个工具"、"中间推理了什么"。
- 面试官问"你的 Agent 是怎么工作的"，只能说"框架自动处理的"。

### 3.2 优化后 Agent 架构（目标）

```mermaid
flowchart TD
    A["用户 query"] --> B["AgentLoopService<br/>自定义推理循环"]
    B --> C{"步骤 < MAX_STEPS?"}
    C -- 是 --> D["调用 LLM<br/>传入历史 + 工具描述"]
    D --> E{"LLM 输出包含<br/>工具调用?"}
    E -- 是 --> F["解析工具名 + 参数"]
    F --> G["ToolDispatcher<br/>执行工具"]
    G --> H{"执行成功?"}
    H -- 是 --> I["记录 observation"]
    H -- 否 --> J{"重试次数 < MAX_RETRY?"}
    J -- 是 --> G
    J -- 否 --> K["记录错误 observation<br/>告知 LLM 工具失败"]
    I --> C
    K --> C
    E -- 否 --> L["最终回答"]
    C -- 否 --> M["强制终止<br/>返回已有信息"]

    style B fill:#e1f5fe
    style G fill:#fff3e0
```

### 3.3 核心组件设计

#### 3.3.1 新增文件清单

```
src/main/java/com/XI/xi_oj/ai/agent/
├── OJChatAgent.java              ← 已有，保留（作为简单模式的兼容）
├── AgentLoopService.java          ← 新增：自定义推理循环（run + runStreaming）
├── ToolDispatcher.java            ← 新增：工具分发与执行
├── AgentStep.java                 ← 新增：单步推理记录

src/main/java/com/XI/xi_oj/model/entity/
└── AgentTraceLog.java             ← 新增：决策链路日志实体

src/main/java/com/XI/xi_oj/mapper/
└── AgentTraceLogMapper.java       ← 新增：日志持久化

src/main/java/com/XI/xi_oj/service/impl/
└── AgentTraceService.java         ← 新增：异步写入 trace 日志
```

#### 3.3.2 AgentStep — 单步推理记录

```java
package com.XI.xi_oj.ai.agent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentStep {
    private int stepIndex;
    private String thought;        // LLM 的思考内容
    private String toolName;       // 调用的工具名（null 表示直接回答）
    private String toolInput;      // 工具输入参数
    private String toolOutput;     // 工具返回结果
    private boolean toolSuccess;   // 工具是否执行成功
    private int retryCount;        // 重试次数
    private long durationMs;       // 本步耗时
}
```

#### 3.3.3 ToolDispatcher — 工具分发与容错

```java
package com.XI.xi_oj.ai.agent;

import com.XI.xi_oj.ai.tools.OJTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class ToolDispatcher {

    private static final int MAX_RETRY = 2;

    @Resource
    private OJTools ojTools;

    /**
     * 执行工具调用，支持重试。
     * 返回 ToolResult 包含执行结果和是否成功。
     */
    public ToolResult execute(String toolName, Map<String, Object> params) {
        Exception lastError = null;

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                String result = dispatch(toolName, params);
                return ToolResult.success(result, attempt);
            } catch (Exception e) {
                lastError = e;
                log.warn("[ToolDispatcher] {} attempt {} failed: {}",
                        toolName, attempt + 1, e.getMessage());
                if (attempt < MAX_RETRY) {
                    sleep(500L * (attempt + 1));  // 退避重试
                }
            }
        }

        String errorMsg = String.format("工具 %s 执行失败（已重试 %d 次）：%s",
                toolName, MAX_RETRY, lastError.getMessage());
        return ToolResult.failure(errorMsg, MAX_RETRY);
    }

    private String dispatch(String toolName, Map<String, Object> params) {
        return switch (toolName) {
            case "query_question_info" ->
                    ojTools.queryQuestionInfo((String) params.get("keyword"));
            case "judge_user_code" ->
                    ojTools.judgeUserCode(
                            toLong(params.get("userId")),
                            toLong(params.get("questionId")),
                            (String) params.get("code"),
                            (String) params.get("language"));
            case "query_user_wrong_question" ->
                    ojTools.queryUserWrongQuestion(
                            toLong(params.get("userId")),
                            toLong(params.get("questionId")));
            case "search_questions" ->
                    ojTools.searchQuestions(
                            (String) params.get("keyword"),
                            (String) params.get("tag"),
                            (String) params.get("difficulty"));
            case "find_similar_questions" ->
                    ojTools.findSimilarQuestions(
                            toLong(params.get("questionId")));
            case "list_user_wrong_questions" ->
                    ojTools.listUserWrongQuestions(
                            toLong(params.get("userId")));
            case "query_user_submit_history" ->
                    ojTools.queryUserSubmitHistory(
                            toLong(params.get("userId")),
                            toLong(params.get("questionId")));
            case "query_user_mastery" ->
                    ojTools.queryUserMastery(
                            toLong(params.get("userId")));
            case "get_question_hints" ->
                    ojTools.getQuestionHints(
                            toLong(params.get("questionId")),
                            toInt(params.get("hintLevel")));
            case "run_custom_test" ->
                    ojTools.runCustomTest(
                            toLong(params.get("questionId")),
                            (String) params.get("code"),
                            (String) params.get("language"),
                            (String) params.get("customInput"));
            case "diagnose_error_pattern" ->
                    ojTools.diagnoseErrorPattern(
                            toLong(params.get("userId")));
            default -> throw new IllegalArgumentException("未知工具: " + toolName);
        };
    }

    private Long toLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("无法转换为 Long: " + value);
    }

    private int toInt(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("无法转换为 int: " + value);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public record ToolResult(String output, boolean success, int retryCount) {
        static ToolResult success(String output, int retryCount) {
            return new ToolResult(output, true, retryCount);
        }
        static ToolResult failure(String errorMsg, int retryCount) {
            return new ToolResult(errorMsg, false, retryCount);
        }
    }
}
```

#### 3.3.4 AgentLoopService — 自定义推理循环（核心）

`AgentLoopService` 提供两个入口方法：

- **`run()`**：同步执行，返回 `AgentResult`（用于非流式 `/ai/chat` 接口）。
- **`runStreaming()`**：流式执行，通过 `FluxSink<String>` 逐 token 推送（用于 SSE `/ai/chat/stream` 接口）。

两个方法共享同一套 ReAct 循环逻辑，区别在于 LLM 调用方式和结果推送方式。

**关键设计（相比初版的演进）：**

1. **自带 RAG 检索**：Agent Loop 不依赖 AiServices 的 `RetrievalAugmentor`，而是在循环开始前自行调用 `QueryRewriter` → `OJKnowledgeRetriever` → `RerankService` 完成检索，将结果作为 `UserMessage` 注入对话历史。
2. **System Prompt 可配置**：通过 `ai.prompt.agent_system` 配置项动态读取，支持运行时调整。
3. **`LinkValidationFilter` 后置校验**：最终回答经过链接真实性校验，剥离不存在的题目链接。
4. **`maxSteps` 动态读取**：从 `ai.agent.max_steps` 配置项读取，默认 6。
5. **流式推送**：`runStreaming()` 通过 `[STATUS]` 前缀推送中间状态事件，最终回答通过 `StreamingChatModel` 逐 token 推送。

```java
package com.XI.xi_oj.ai.agent;

// 省略 import（完整代码见源文件）

@Service
@Slf4j
public class AgentLoopService {

    @Resource private AiModelHolder aiModelHolder;
    @Resource private ToolDispatcher toolDispatcher;
    @Resource private AiConfigService aiConfigService;
    @Resource private QueryRewriter queryRewriter;
    @Resource private OJKnowledgeRetriever ojKnowledgeRetriever;
    @Resource private RerankService rerankService;
    @Resource private LinkValidationFilter linkValidationFilter;

    // 同步入口
    public AgentResult run(String userQuery, Long userId) {
        int maxSteps = maxSteps();
        List<AgentStep> steps = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();

        String systemPrompt = aiConfigService.getPrompt("ai.prompt.agent_system", DEFAULT_AGENT_SYSTEM_PROMPT);
        messages.add(SystemMessage.from(systemPrompt.formatted(maxSteps)));

        // 自行完成 RAG 检索（QueryRewrite → 向量检索 → Rerank）
        String ragContext = retrieveRagContext(userQuery);
        if (!ragContext.isBlank()) {
            messages.add(UserMessage.from("以下是从知识库检索到的相关资料，请参考：\n\n" + ragContext));
        }
        messages.add(UserMessage.from(userQuery));

        ChatModel chatModel = aiModelHolder.getChatModel();
        for (int i = 0; i < maxSteps; i++) {
            // ... ReAct 循环（解析 Thought/Action/Answer，执行工具，记录 AgentStep）
        }
        // 超过最大步数，强制总结
        return AgentResult.of(forceAnswer, steps);
    }

    // 流式入口（SSE 推送）
    public void runStreaming(String userQuery, Long userId, FluxSink<String> sink,
                             Consumer<AgentResult> onComplete) {
        // 1. 构建消息历史（同 run()，含 RAG 检索）
        // 2. ReAct 循环中，每一步通过 sink.next("[STATUS]...") 推送状态事件
        // 3. LLM 调用使用 streamStep()，支持 Answer 部分逐 token 推送
        // 4. 最终回答经 LinkValidationFilter 校验
        // 5. 循环结束后 sink.complete()，通过 onComplete 回调传出 AgentResult
    }

    // 流式单步调用（状态机：BUFFERING → STREAMING_ANSWER）
    private String streamStep(StreamingChatModel streamingModel, ChatModel chatModel,
                              List<ChatMessage> messages, FluxSink<String> sink,
                              StringBuilder answerBuffer) {
        // StreamingChatModel 为 null 时退化为同步 ChatModel + emitChunked
        // 否则使用 StreamingChatResponseHandler + CountDownLatch 桥接
        // Thought/Action 部分缓冲不推送，检测到 Answer: 后切换为逐 token 推送
    }

    // RAG 检索：QueryRewrite → 向量检索 → Rerank
    private String retrieveRagContext(String query) {
        String rewritten = queryRewriter.rewrite(query);
        List<Content> contents = ojKnowledgeRetriever.retrieveAsContents(rewritten, topK, minScore);
        if (rerankService.enabled() && contents.size() > 1) {
            contents = rerankService.rerank(rewritten, contents, topK);
        }
        return contents.stream().map(c -> formatSegmentWithImages(c.textSegment()))
                .collect(Collectors.joining("\n\n"));
    }

    public record AgentResult(String answer, List<AgentStep> steps) { ... }
}
```

> **完整源码**见 `src/main/java/com/XI/xi_oj/ai/agent/AgentLoopService.java`，此处展示核心结构。

**`runStreaming()` 的流式推送时序：**

```mermaid
sequenceDiagram
    participant FE as 前端
    participant Ctrl as AiChatController
    participant Svc as AiChatServiceImpl
    participant Agent as AgentLoopService
    participant LLM as StreamingChatModel

    FE->>Ctrl: POST /ai/chat/stream
    Ctrl->>Svc: chatStream()
    Svc->>Svc: Flux.create(sink -> virtualThread)
    Svc->>Agent: runStreaming(query, userId, sink, onComplete)
    Agent->>Agent: retrieveRagContext()
    Agent-->>FE: [STATUS]正在检索知识库...
    loop ReAct 循环
        Agent-->>FE: [STATUS]正在思考（第 N/M 步）...
        Agent->>LLM: streamStep()
        Note over Agent,LLM: 缓冲 Thought/Action 部分<br/>检测到 Answer: 后切换为直接推送
        LLM-->>FE: token1, token2, token3...（逐 token）
        alt 需要调用工具
            Agent-->>FE: [STATUS]正在调用工具: xxx
            Agent->>Agent: toolDispatcher.execute()
            Agent-->>FE: [STATUS]工具调用完成，继续分析...
        end
    end
    Agent-->>FE: sink.complete()
    Agent->>Svc: onComplete(AgentResult)
    Svc->>Svc: saveTraceAsync + saveRecordAsync
```

**`streamStep()` 状态机核心逻辑：**

用 `CountDownLatch` 将 `StreamingChatResponseHandler` 的异步回调桥接到同步的 ReAct 循环。Thought/Action 部分在 `pendingBuffer` 中积累（不推送给前端），检测到 `Answer:` 标记后切换为逐 token 直接 `sink.next()` 推送。这样中间推理步骤不会泄露给用户，只有最终回答是流式的。

**`AiChatServiceImpl.chatStream()` 的 advanced 分支：**

```java
if (isAdvancedAgentMode()) {
    return Flux.<String>create(sink -> {
        Thread.startVirtualThread(() -> {
            OJTools.setCurrentUserId(userId);
            try {
                agentLoopService.runStreaming(enrichedMessage, userId, sink, result -> {
                    agentTraceService.saveTraceAsync(userId, chatId, message, result.steps());
                    aiChatAsyncService.saveRecordAsync(userId, chatId, message, result.answer());
                });
            } catch (Exception e) { sink.error(e); }
            finally { OJTools.clearCurrentUserId(); }
        });
    });
}
```

用虚拟线程包装，因为 `runStreaming()` 内部有同步阻塞调用（`CountDownLatch.await()`、`ToolDispatcher.execute()`），不能阻塞 Reactor 线程。

**`AiChatController.chatStream()` 的 status 事件区分：**

```java
.map(token -> {
    if (token != null && token.startsWith("[STATUS]")) {
        String statusText = token.substring("[STATUS]".length());
        return ServerSentEvent.<String>builder()
                .event("status")
                .data(toJson(singletonPayload("d", statusText)))
                .build();
    }
    return ServerSentEvent.<String>builder()
            .data(toJson(singletonPayload("d", token)))
            .build();
})
```

`[STATUS]` 前缀的 token 转为 `event: status` SSE 事件，前端 `sse.ts` 的 `onStatus` 回调接收并显示为加载状态文本。

### 3.4 Agent 可观测性 — 决策链路日志

#### 3.4.1 日志表结构

> 完整的可执行 SQL 见第四章「所需 SQL 语句汇总」。

```sql
CREATE TABLE ai_agent_trace_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    chat_id     VARCHAR(64)  NOT NULL,
    query       TEXT         NOT NULL,
    step_index  INT          NOT NULL,
    thought     TEXT,
    tool_name   VARCHAR(64),
    tool_input  TEXT,
    tool_output TEXT,
    tool_success TINYINT     DEFAULT 1,
    retry_count INT          DEFAULT 0,
    duration_ms BIGINT       DEFAULT 0,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_chat (user_id, chat_id)
) COMMENT 'Agent 推理链路追踪日志';
```

#### 3.4.2 日志写入时机

**同步模式（`run()`）**：在 `AgentLoopService.run()` 返回后，异步写入所有 steps：

```java
// AiChatServiceImpl.chat() 中调用
AgentLoopService.AgentResult result = agentLoopService.run(message, userId);
agentTraceService.saveTraceAsync(userId, chatId, message, result.steps());
return result.answer();
```

**流式模式（`runStreaming()`）**：通过 `onComplete` 回调在流结束后异步写入：

```java
// AiChatServiceImpl.chatStream() 中调用
agentLoopService.runStreaming(enrichedMessage, userId, sink, result -> {
    agentTraceService.saveTraceAsync(userId, chatId, message, result.steps());
    aiChatAsyncService.saveRecordAsync(userId, chatId, message, result.answer());
});
```

#### 3.4.3 可观测性价值

一次完整的 Agent 调用，日志记录如下：

```
┌─────────────────────────────────────────────────────────────┐
│ query: "帮我看看第5题我哪里写错了"                              │
├─────┬───────────────────────────────────────────────────────┤
│ #1  │ Thought: 用户想分析第5题的错误，我需要先查题目信息        │
│     │ Action: query_question_info                            │
│     │ Input: {"keyword": "5"}                                │
│     │ Output: 题目ID：5, 标题：两数之和, ...                   │
│     │ Success: ✅  Duration: 120ms                           │
├─────┼───────────────────────────────────────────────────────┤
│ #2  │ Thought: 题目信息拿到了，接下来查用户的错题记录            │
│     │ Action: query_user_wrong_question                      │
│     │ Input: {"userId": 12345, "questionId": 5}              │
│     │ Output: 错误代码：..., 判题结果：Wrong Answer            │
│     │ Success: ✅  Duration: 85ms                            │
├─────┼───────────────────────────────────────────────────────┤
│ #3  │ Thought: 已有题目信息和错题记录，可以分析错误原因了        │
│     │ Answer: 你的代码在边界条件处理上有问题...                 │
│     │ Duration: 1200ms                                       │
└─────┴───────────────────────────────────────────────────────┘
总步数: 3    总耗时: 1405ms    工具调用: 2次    重试: 0次
```

面试时可以说："我的 Agent 每一步的推理过程、工具选择、执行结果都有完整日志，可以追溯任何一次对话的决策链路，方便调试和优化。"

---

### 3.5 与现有 OJChatAgent 的共存策略

不需要删除现有的 `OJChatAgent`，两套方案可以共存：

```mermaid
flowchart TD
    A["AiChatController"] --> B{"agent.mode 配置"}
    B -- "simple（默认）" --> C["OJChatAgent<br/>LangChain4j AiServices"]
    B -- "advanced" --> D["AgentLoopService<br/>自定义推理循环"]
    C --> E["返回结果"]
    D --> E
```

通过 `ai_config` 表的 `ai.agent.mode` 配置项切换：
- `simple`：使用现有 `OJChatAgent`（AiServices 自动调度），支持流式输出，稳定兜底。
- `advanced`：使用 `AgentLoopService`（自定义循环），功能更强，**已支持流式输出**（`runStreaming()` + `StreamingChatModel`）。

> **Streaming 实现说明**：`AgentLoopService.runStreaming()` 通过 `FluxSink<String>` 推送两类事件：中间步骤状态（`[STATUS]` 前缀）和最终回答（逐 token）。`AiChatServiceImpl.chatStream()` 在 advanced 分支使用 `Flux.create()` + 虚拟线程包装，`AiChatController` 将 `[STATUS]` 前缀转为 `event: status` SSE 事件。前端 `sse.ts` 的 `onStatus` 回调接收状态文本并显示为加载指示器。

这样可以灰度切换，不影响线上稳定性。

---

### 3.6 方向 A 实施顺序与改动清单

| 步骤 | 改动文件 | 改动类型 | 状态 | 说明 |
|---|---|---|---|---|
| 1 | `AgentStep.java` | 新增 | ✅ 已完成 | 单步推理记录模型 |
| 2 | `ToolDispatcher.java` | 新增 | ✅ 已完成 | 工具分发 + 重试 + 容错 |
| 3 | `AgentLoopService.java` | 新增 | ✅ 已完成 | 自定义 ReAct 推理循环 + 流式推送（`run()` + `runStreaming()`） |
| 4 | `ai_agent_trace_log` 表 | 新增 SQL | ✅ 已完成 | 决策链路日志表 |
| 5 | `AgentTraceLog.java` + Mapper + Service | 新增 | ✅ 已完成 | 日志实体、持久化、异步写入 |
| 6 | `AiChatServiceImpl.java` | 修改 | ✅ 已完成 | 根据配置切换 simple/advanced 模式，advanced 支持流式 |
| 7 | `AiChatController.java` | 修改 | ✅ 已完成 | `[STATUS]` 前缀转 `event: status` SSE 事件 |
| 8 | `ai_config` 表 | 插入数据 | ✅ 已完成 | 新增 `ai.agent.mode`、`ai.agent.max_steps` 配置项 |
| 9 | `sse.ts` | 修改 | ✅ 已完成 | 新增 `onStatus` 回调处理 status 事件 |
| 10 | `AiChatView.vue` + `AiChatWidget.vue` | 修改 | ✅ 已完成 | 显示 Agent 状态指示器 |

---

## 四、所需 SQL 语句汇总

本章汇总两个方向所有需要执行的 SQL，按实施顺序排列。

### 4.1 方向 B：ai_config 新增 Query Rewrite 和 Rerank 配置项

```sql
-- Query Rewrite 相关配置
INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.rewrite.model_name', 'qwen-turbo', 'Query Rewrite 使用的模型名称（轻量模型，用于改写用户口语化 query）', 1),
('ai.rewrite.temperature', '0.1', 'Query Rewrite 模型温度（低温度保证改写结果稳定，避免缓存命中率下降）', 1),
('ai.rewrite.max_tokens', '256', 'Query Rewrite 最大输出 token 数（改写只需一句话，不需要长输出）', 1);

-- Rerank 相关配置（预留，当前 RerankService 使用固定值，后续可改为动态读取）
INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.rerank.enabled', 'true', '是否启用 Cross-Encoder Rerank 精排', 1),
('ai.rerank.model_name', 'gte-rerank', 'Rerank 模型名称（DashScope 提供）', 1),
('ai.rerank.top_n', '3', 'Rerank 精排后取前 N 个结果', 1);
```

### 4.2 方向 A：Agent 推理链路日志表

```sql
CREATE TABLE ai_agent_trace_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    chat_id     VARCHAR(64)  NOT NULL,
    query       TEXT         NOT NULL,
    step_index  INT          NOT NULL,
    thought     TEXT,
    tool_name   VARCHAR(64),
    tool_input  TEXT,
    tool_output TEXT,
    tool_success TINYINT     DEFAULT 1,
    retry_count INT          DEFAULT 0,
    duration_ms BIGINT       DEFAULT 0,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_chat (user_id, chat_id)
) COMMENT 'Agent 推理链路追踪日志';
```

### 4.3 方向 A：ai_config 新增 Agent 模式切换配置

```sql
INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.agent.mode', 'simple', 'Agent 推理模式：simple=LangChain4j AiServices 自动调度，advanced=自定义 ReAct 推理循环', 1),
('ai.agent.max_steps', '6', 'Agent 推理循环最大步数（超过后强制总结回答）', 1),
('ai.agent.tool_max_retry', '2', '工具调用最大重试次数', 1);
```

### 4.4 执行顺序建议

| 顺序 | SQL | 时机 | 状态 |
|---|---|---|---|
| 1 | 4.1 Query Rewrite + Rerank 配置 | 方向 B 开发前 | ✅ 已执行 |
| 2 | 4.2 ai_agent_trace_log 建表 | 方向 A 开发前 | ✅ 已执行 |
| 3 | 4.3 Agent 模式配置 | 方向 A 开发前 | ✅ 已执行 |

---

## 五、两个方向的面试话术

### 5.1 RAG 优化怎么讲

> "我们的 RAG 做了三层优化，全部通过 LangChain4j 的原生扩展点接入 pipeline，不侵入检索器代码。第一层 Query Rewrite，实现了 `QueryTransformer` 接口，用轻量模型把用户口语化的短 query 改写成信息更丰富的检索 query，比如'二分怎么写'改写成'二分查找算法实现思路与边界处理'，改写模型实例缓存为 volatile 字段、配置变更时事件驱动重建，改写结果缓存到 Redis 避免重复调用。第二层 Cross-Encoder Rerank，实现了 `ContentAggregator` 接口，先用默认策略合并双集合检索结果，再调用 DashScope 的 gte-rerank 模型精排取 TopN，操作的是 `Content` 对象所以 metadata 不会丢失。有开关控制可以一键关闭。第三层是离线评估（待建设），计划用现有题目标签构造评估集，跑 Recall@5 和 MRR 指标量化效果。"

### 5.2 自定义 Agent Loop 怎么讲

> "我用 LangChain4j 做模型调用和 RAG 检索的基础设施，但 Agent 的推理循环是自己实现的，没有用 AiServices 的自动调度。底层能力复用框架（ChatModel、EmbeddingStore），上层控制逻辑自己掌握。每一步 LLM 输出 Thought + Action，我解析后通过 ToolDispatcher 执行工具（覆盖全部 11 个工具），工具失败会退避重试最多 2 次，超过最大步数会强制总结。Agent Loop 自带 RAG pipeline（QueryRewrite → 向量检索 → Rerank），不依赖 AiServices 的 RetrievalAugmentor。流式场景下，中间步骤通过 SSE status 事件推送给前端（'正在思考'、'正在调用工具'），最终回答用 StreamingChatModel 逐 token 推送——我用 CountDownLatch 把异步回调桥接到同步的 ReAct 循环，Thought/Action 部分在缓冲区积累不推送，检测到 Answer 标记后切换为直接推送。整个推理链路每一步都记录到数据库，方便线上排查。两套方案通过配置中心切换，可以灰度上线。"

### 5.3 被追问"为什么不完全脱离 LangChain4j"时怎么答

> "LangChain4j 的价值分层次：底层的模型抽象（ChatModel 接口统一了不同厂商的 API 差异）、向量存储适配（EmbeddingStore 屏蔽了 Milvus 的连接细节）这些是纯基础设施，自己写没有额外收益。但最上层的 AiServices 自动调度是黑盒——工具失败没有重试、没有步数限制、没有决策日志，这些在生产环境是必须的。所以我的做法是：基础设施用框架，编排逻辑自己写，该复用的复用，该掌控的掌控。"

---

## 六、总结

| 方向 | 核心价值 | 文件 | 状态 | 面试加分点 |
|---|---|---|---|---|
| B：RAG 深度优化 | 检索质量可量化提升 | QueryRewriter / QueryRewriteTransformer / RerankService / RerankingContentAggregator | ✅ 已落地（评估待建设） | LangChain4j 原生扩展点 + 动态配置 + 全链路降级 |
| A：自定义 Agent Loop | 证明理解 Agent 本质和框架分层 | AgentLoopService / ToolDispatcher / AgentStep / AgentTraceService 等 | ✅ 已落地（含流式推送） | 可观测性 + 容错 + 灰度 + StreamingChatModel 逐 token 推送 + 自带 RAG pipeline |

方向 B 已落地，剩余离线评估体系待建设。方向 A 已落地，包含同步 `run()` 和流式 `runStreaming()` 两个入口，支持 SSE 状态事件推送和逐 token 流式回答。

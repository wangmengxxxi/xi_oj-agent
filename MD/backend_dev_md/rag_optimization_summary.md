# XI OJ RAG 优化总结文档

## 一、文档目标

本文档用于总结当前 XI OJ 项目在 RAG（Retrieval-Augmented Generation，检索增强生成）方面已经完成的优化工作，覆盖：

1. 提示词层面的约束与动态配置。
2. AiServices 自动 RAG 链路。
3. 自定义 Agent Loop 手动 RAG 链路。
4. Query Rewrite 与 Rerank。
5. chunk 切分与 metadata 区分。
6. PDF / Word / Markdown 多格式知识导入。
7. 图片 URL 与 `image_refs` 精细化 metadata。
8. 缓存、热更新、防幻觉链接过滤等工程增强。

本文档描述的是当前项目代码中的实际实现情况，而不是通用 RAG 理论方案。

---

## 二、当前 RAG 的整体定位

XI OJ 的 RAG 不是单纯的“向量检索 + Prompt 拼接”，而是一个面向 OJ 学习场景的工程化检索增强系统。

当前 RAG 主要服务于以下场景：

1. AI 问答：回答算法知识、学习路径、题目推荐等问题。
2. 题目解析：结合知识库内容分析题目考点。
3. 代码分析：检索代码模板、错题分析等上下文。
4. 错题分析：结合历史提交、题目信息、相似题与知识库内容生成分析。
5. 自定义 Agent Loop：在 ReAct 风格推理过程中主动检索知识库并调用工具。

整体链路可以概括为：

```text
用户问题
  -> Query Rewrite
  -> Embedding 向量召回
  -> Milvus 检索
  -> metadata 过滤
  -> Rerank 精排
  -> 图片相关性过滤
  -> Prompt / Message 注入
  -> LLM 生成
  -> LinkValidationFilter 过滤假链接
```

---

## 三、两条 RAG 链路

### 3.1 AiServices 自动 RAG 链路

适用对象：

- `OJChatAgent`
- `OJQuestionParseAgent`

核心代码：

- `src/main/java/com/XI/xi_oj/ai/agent/AiModelHolder.java`
- `src/main/java/com/XI/xi_oj/ai/rag/ImageAwareContentRetriever.java`
- `src/main/java/com/XI/xi_oj/ai/rag/QueryRewriteTransformer.java`
- `src/main/java/com/XI/xi_oj/ai/rag/RerankingContentAggregator.java`

核心组件：

```text
AiServices
  -> DefaultRetrievalAugmentor
     -> QueryRewriteTransformer
     -> DefaultQueryRouter
        -> ImageAwareContentRetriever(oj_knowledge)
        -> EmbeddingStoreContentRetriever(oj_question)
     -> RerankingContentAggregator
```

特点：

1. RAG 由 LangChain4j `RetrievalAugmentor` 自动执行。
2. Service 层无需手动调用检索方法。
3. 适合通用问答和题目解析这类“对话输入 -> 自动补充上下文”的场景。
4. `oj_knowledge` 检索器被 `ImageAwareContentRetriever` 包装，可以处理知识库图片。
5. 查询进入向量检索前会经过 `QueryRewriteTransformer`。
6. 多路检索结果会经过 `RerankingContentAggregator` 精排。

### 3.2 自定义 Agent Loop 手动 RAG 链路

适用对象：

- advanced agent mode
- `AgentLoopService`

核心代码：

- `src/main/java/com/XI/xi_oj/ai/agent/AgentLoopService.java`
- `src/main/java/com/XI/xi_oj/ai/rag/OJKnowledgeRetriever.java`
- `src/main/java/com/XI/xi_oj/ai/rag/RerankService.java`
- `src/main/java/com/XI/xi_oj/ai/rag/RagImageSupport.java`

链路：

```text
AgentLoopService.run()
  -> retrieveRagContext(query)
     -> QueryRewriter.rewrite(query)
     -> OJKnowledgeRetriever.retrieveAsContents(rewritten, topK * 2, minScore)
     -> RerankService.rerank(rewritten, contents, topK)
     -> RagImageSupport.appendRelevantImages(segment, rewritten)
  -> 将 RAG context 作为 UserMessage 注入 Agent Loop
```

特点：

1. 不依赖 LangChain4j `RetrievalAugmentor`。
2. RAG 检索由自定义 Agent Loop 主动控制。
3. 可以和工具调用、最大推理步数、工具失败重试、执行轨迹记录结合。
4. 与 AiServices 链路复用同一套 Query Rewrite、Rerank 和图片过滤逻辑。
5. 使用过度召回策略：向量检索 `topK * 2` 条候选，Rerank 精排后取 `topK` 条，给 Rerank 模型更大的候选池提高精排质量。
6. 支持 SSE 流式输出（`runStreaming()`），包括 `[STATUS]` 进度消息推送和 `Answer:` 标记检测后才向前端推送 token。

这条链路体现了项目不是只会使用框架封装，而是理解并实现了 Agent 推理过程中的 RAG 编排。

---

## 四、提示词层面的优化

### 4.1 System Prompt 动态配置

当前项目没有把所有提示词硬编码在代码中，而是通过 `ai_config` 管理：

- `ai.prompt.chat_system`
- `ai.prompt.question_parse`
- `ai.prompt.agent_system`

相关代码：

- `AiModelHolder.buildChatAgent()`
- `AiModelHolder.buildQuestionParseAgent()`
- `AgentLoopService.run()`
- `AgentLoopService.runStreaming()`

优势：

1. 后台修改 Prompt 后可以动态生效。
2. 不需要重新打包后端。
3. 可以针对问答、题目解析、自定义 Agent Loop 使用不同提示词。

### 4.2 Agent Loop 专用提示词

`AgentLoopService` 中的默认 Agent Prompt 明确约束：

1. 当前可用工具。
2. 工具调用格式。
3. 最大推理步数。
4. 何时调用工具，何时直接回答。
5. 不要编造平台不存在的题目链接。
6. 输出要贴合 OJ 学习和算法训练场景。

这使得自定义 Agent Loop 不是“让模型自由发挥”，而是在一个明确的推理协议内运行。

### 4.3 图片引用提示词

RAG 图片上下文中会追加：

```text
[RAG_SOURCE_IMAGES]
Only keep the following image links when they directly support the answer.
```

这条提示的目标是：

1. 告诉模型这些图片来自 RAG 知识库。
2. 要求模型只在图片确实支撑回答时保留链接。
3. 减少模型无脑输出不相关图片。
4. 禁止模型修改或编造图片 URL。

---

## 五、Query Rewrite 优化

核心代码：

- `src/main/java/com/XI/xi_oj/ai/rag/QueryRewriter.java`
- `src/main/java/com/XI/xi_oj/ai/rag/QueryRewriteTransformer.java`

作用：

将用户口语化、模糊化的问题改写成更适合向量检索的 query。

示例：

```text
原始问题：
帮我找快排相关题

改写后可能变为：
快速排序 partition 分治 双指针 第K个元素 quickselect
```

解决的问题：

1. 用户问题经常很短，embedding 信息不足。
2. 用户表达和知识库教材表述不完全一致。
3. 同义词、英文术语、算法别名可能召回不到。

在 AiServices 链路中，Query Rewrite 通过 `QueryRewriteTransformer` 接入。

在 Agent Loop 链路中，Query Rewrite 由 `AgentLoopService.retrieveRagContext()` 主动调用。

### 5.1 Query Rewrite 自带 Redis 缓存

`QueryRewriter.rewrite()` 内置了独立的 Redis 缓存：

- 缓存 key 前缀：`ai:query:rewrite:<md5>`
- TTL：120 分钟
- 相同 query 在缓存有效期内不会重复调用改写模型

这意味着即使 Agent Loop 的 RAG 检索本身没有缓存，改写这一步也不会重复消耗模型调用。

---

## 六、Rerank 精排优化

核心代码：

- `src/main/java/com/XI/xi_oj/ai/rag/RerankService.java`
- `src/main/java/com/XI/xi_oj/ai/rag/RerankingContentAggregator.java`

当前 RAG 使用两阶段检索：

```text
第一阶段：Embedding 向量召回候选内容
第二阶段：Rerank 对候选内容重新排序
```

优化价值：

1. 向量检索负责召回，Rerank 负责排序。
2. 可以降低“语义相近但不够准确”的内容排在前面的概率。
3. 对短 query、口语 query、复杂算法问题更有帮助。

AiServices 链路：

```text
RerankingContentAggregator
```

Agent Loop 链路：

```text
RerankService.rerank(rewritten, contents, topK)
```

---

## 七、双 Collection 隔离设计

当前项目不是把所有向量都放进一个 collection，而是拆成：

| Collection | 用途 |
|---|---|
| `oj_knowledge` | 算法知识、PDF/Word/Markdown 导入内容、代码模板、错题分析知识 |
| `oj_question` | 平台题目向量，用于相似题推荐、题目检索 |

相关代码：

- `AiModelHolder` 中创建和使用两个 `MilvusEmbeddingStore`
- `QuestionVectorSyncService` 写入 `oj_question`
- `KnowledgeInitializer` 写入 `oj_knowledge`
- `OJKnowledgeRetriever` 同时支持知识库检索和相似题检索

优势：

1. 避免知识点和题目互相污染。
2. 相似题检索不会召回教材段落。
3. 知识问答不会被大量题目向量干扰。
4. 题目向量可以单独维护、重建和过滤。

---

## 八、Chunk 与 Metadata 区分策略

### 8.1 Chunk 不只是纯文本

当前项目中的 `TextSegment` 不只保存正文，还保存 metadata。

常见 metadata：

| 字段 | 说明 |
|---|---|
| `content_type` | 内容类型，如知识点、题目、代码模板、错题分析 |
| `tag` | 算法标签，如排序算法、动态规划、二叉树 |
| `title` | chunk 标题 |
| `source_type` | 来源格式，如 md / pdf / docx |
| `question_id` | 题目 ID，仅题目向量使用 |
| `difficulty` | 题目难度，仅题目向量使用 |
| `image_urls` | chunk 关联图片 URL，逗号分隔 |
| `image_refs` | 每张图片的结构化语义信息 |

### 8.2 知识库 Chunk

知识库导入由 `KnowledgeInitializer` 解析 markdown block。

示例：

```text
content_type: 知识点
tag: 排序算法
title: 快速排序
source_type: pdf
image_urls: http://...
image_refs: [...]

正文内容...
```

### 8.3 题目 Chunk

题目向量由 `QuestionVectorSyncService` 生成。

典型 metadata：

```text
content_type: 题目
question_id: 题目ID
difficulty: easy / medium / hard
```

相似题检索时会使用：

1. 向量相似度。
2. `content_type = 题目`。
3. `difficulty` 难度过滤。
4. 排除当前题目自身。

### 8.4 按类型检索

`OJKnowledgeRetriever.retrieveByType()` 支持按 `content_type` 过滤。

适合场景：

1. 代码分析：只检索代码模板、错题分析。
2. 错题分析：只检索错题分析相关知识。
3. 避免把通用知识点混进需要强约束的业务场景。

---

## 九、图片 Metadata 优化

### 9.1 旧方案：只有 image_urls

旧的图片 metadata 只有：

```text
image_urls: url1,url2,url3
```

问题：

1. 只能知道 chunk 有图片。
2. 不知道每张图片具体讲什么。
3. PDF 一页中可能有多个知识点和多张图。
4. 文本 chunk 命中了 query，但同页图片不一定相关。
5. 容易出现“文字讲快速排序，图片却是其他结构图”的问题。

### 9.2 新方案：新增 image_refs

现在新增：

```text
image_refs
```

结构示例：

```json
[
  {
    "url": "http://192.168.26.128:9000/oj-knowledge-images/knowledge/hello-algo_1.3.0_zh_java.pdf/xxx_p218_0.png",
    "title": "排序算法",
    "tag": "排序算法",
    "nearbyText": "图附近的文本内容",
    "caption": "",
    "page": 219
  }
]
```

字段说明：

| 字段 | 说明 |
|---|---|
| `url` | MinIO 图片访问地址 |
| `title` | 所属 chunk 标题 |
| `tag` | 所属知识标签 |
| `nearbyText` | 图片附近的 PDF 文本 |
| `caption` | 预留图注字段 |
| `page` | PDF 页码 |

### 9.3 image_refs 的价值

`image_refs` 让系统从：

```text
这个 chunk 有哪些图片
```

升级为：

```text
每张图片大概和什么知识内容相关
```

这样在最终回答前，可以根据用户 query 对图片做相关性过滤。

---

## 十、PDF 图片关联优化

核心代码：

- `src/main/java/com/XI/xi_oj/ai/rag/parser/PdfDocumentParser.java`

当前 PDF 导入流程：

```text
读取 PDF
  -> 提取全文文本
  -> 按标题和长度生成 chunk
  -> 按页提取文本行位置
  -> 提取图片对象
  -> 计算图片在页面中的位置
  -> 为图片寻找附近文本
  -> 判断图片是否属于当前 chunk
  -> 生成 image_urls
  -> 生成 image_refs
  -> 输出 markdown block
  -> KnowledgeInitializer 写入 Milvus
```

相比旧方案，关键变化是：

1. 不再只按页码把图片粗暴挂到 chunk 上。
2. 会提取图片的页面位置。
3. 会提取图片附近文本。
4. 会根据 chunk 的标题、标签、正文和图片附近文本判断相关性。
5. 会写入 `image_refs`，供检索时二次过滤。

这对 `hello-algo_1.3.0_zh_java.pdf` 这种图文密集教材尤其重要。

---

## 十一、RAG 图片返回过滤

核心代码：

- `src/main/java/com/XI/xi_oj/ai/rag/RagImageSupport.java`
- `src/main/java/com/XI/xi_oj/ai/rag/ImageAwareContentRetriever.java`
- `src/main/java/com/XI/xi_oj/ai/agent/AgentLoopService.java`
- `src/main/java/com/XI/xi_oj/ai/rag/OJKnowledgeRetriever.java`

### 11.1 统一过滤入口

当前多个 RAG 出口都复用了：

```java
RagImageSupport.appendRelevantImages(segment, query)
```

接入位置：

| 链路 | 接入点 |
|---|---|
| AiServices 自动 RAG | `ImageAwareContentRetriever` |
| 自定义 Agent Loop | `AgentLoopService.formatSegmentWithImages()` |
| 普通知识库检索 | `OJKnowledgeRetriever.retrieve()` |
| 按类型知识库检索 | `OJKnowledgeRetriever.retrieveByType()` |

### 11.2 过滤逻辑

优先读取：

```text
image_refs
```

如果存在 `image_refs`，则使用三级优先级判断图片是否相关：

**第一级（直接匹配）：** 用户 query 是否包含在图片语义文本中（`title` + `tag` + `caption` + `nearbyText` 拼接）。命中则直接保留。

**第二级（术语交集）：** 从 query 和图片语义文本中提取术语（含算法领域词表 `DOMAIN_TERMS`，如 dfs、bfs、avl、快排、partition、pivot、动态规划等），判断是否存在至少一个共同术语。命中则保留。

**第三级（视觉回退）：** 如果 query 看起来是视觉类问题（包含"图、图片、示意、结构、流程"等关键词），且当前 segment 正文与图片语义文本有术语交集，则保留。

三级均未命中的图片会被过滤掉。

如果没有 `image_refs`，只有旧的 `image_urls`，则走保守策略：

1. 用户明确问“图、图片、示意、结构、流程”等视觉相关问题时，才返回旧图片。
2. 普通文本问题不会轻易附带旧图片。

这样可以兼容旧数据，同时降低旧数据带来的错误配图概率。

---

## 十二、缓存优化

核心代码：

- `src/main/java/com/XI/xi_oj/ai/rag/OJKnowledgeRetriever.java`

缓存前缀：

```text
ai:rag:cache:
```

### 12.1 哪些链路使用了 Redis 缓存

这层缓存只存在于 `OJKnowledgeRetriever` 内部，并不是所有 RAG 链路都会经过它。

一句话理解：

```text
代码分析 / 错题分析 / 相似题推荐
  -> 直接调用 OJKnowledgeRetriever
  -> 使用 ai:rag:cache: Redis 缓存

普通 AI 问答 / AiServices 自动 RAG / Agent Loop 主检索
  -> 不走 OJKnowledgeRetriever 的缓存方法
  -> 不使用 ai:rag:cache: Redis 缓存
```

当前明确使用 Redis 缓存的方法：

| 方法 | 缓存内容 | 主要调用方 |
|---|---|---|
| `retrieve(query, topK, minScore)` | 普通知识库检索结果 | 当前更多是通用预留入口，业务调用较少 |
| `retrieveByType(query, contentTypes, topK, minScore)` | 按 `content_type` 过滤后的知识库上下文 | `AiCodeAnalysisServiceImpl`、`AiWrongQuestionServiceImpl`、`OJTools` |
| `retrieveSimilarQuestions(questionId, questionContent, difficulty)` | 相似题 ID 列表 | `AiQuestionParseServiceImpl`、`AiWrongQuestionServiceImpl`、`OJTools` |

按功能模块看：

| 功能模块 | 调用方法 | 是否使用 `ai:rag:cache:` | 缓存的内容 |
|---|---|---|---|
| 代码分析 | `retrieveByType(...)` | 是 | 检索到的“代码模板 / 错题分析”知识文本 |
| 错题分析 | `retrieveByType(...)` | 是 | 检索到的“错题分析”知识文本 |
| 题目解析中的相似题推荐 | `retrieveSimilarQuestions(...)` | 是 | 相似题 ID 列表 |
| 错题分析中的相似题推荐 | `retrieveSimilarQuestions(...)` | 是 | 相似题 ID 列表 |
| Agent 工具：相似题查询 | `retrieveSimilarQuestions(...)` | 是 | 相似题 ID 列表 |
| Agent 工具：错题统计分析 | `retrieveByType(...)` | 是 | 检索到的“错题分析”知识文本 |
| 通用知识检索入口 | `retrieve(...)` | 是，但当前业务调用较少 | 普通知识库文本 |

当前没有使用这层 Redis 缓存的链路：

| 链路 | 原因 |
|---|---|
| AiServices 自动 RAG | 直接走 LangChain4j `EmbeddingStoreContentRetriever`，不经过 `OJKnowledgeRetriever` |
| Agent Loop 的 `retrieveAsContents()` | 返回 `List<Content>` 供后续 Rerank 和图片过滤，目前没有做 Redis 序列化缓存 |
| Query Rewrite | 有自己的独立缓存，不使用 `ai:rag:cache:` |
| Rerank | 当前不使用 `ai:rag:cache:`，它处理的是召回后的候选内容排序 |

### 12.2 缓存内容

1. 普通知识库检索结果。
2. 按类型检索结果。
3. 相似题检索结果。

### 12.3 缓存维度

1. query。
2. topK。
3. minScore。
4. contentTypes。
5. difficulty。

其中：

1. `retrieve()` 的 key 由 `query + topK + minScore` 生成。
2. `retrieveByType()` 的 key 由 `query + contentTypes + topK + minScore` 生成。
3. `retrieveSimilarQuestions()` 的 key 由 `questionId + questionContent + difficulty` 生成。

### 12.4 价值

1. 减少重复 embedding 调用。
2. 减少重复 Milvus 查询。
3. 降低高频相同问题的响应时延。

需要注意，这个价值主要体现在代码分析、错题分析、相似题推荐等手动调用 `OJKnowledgeRetriever` 的场景。AiServices 自动 RAG 和 Agent Loop 的 `retrieveAsContents()` 当前不享受这层 Redis 缓存。

### 12.5 注意事项

1. 重新导入知识库、删除 collection、重建向量后，应清理 RAG 缓存，避免旧结果继续命中。
2. `KnowledgeInitializer.parseAndStore()` 在知识库导入后会调用 `clearRagCache()`。
3. `QuestionVectorSyncService.rebuildAllQuestionVectors()` 在题目向量重建后会调用 `clearRagCache()`。
4. Agent Loop 使用的 `retrieveAsContents()` 方法当前没有 Redis 缓存。这是因为它需要返回 `Content` 对象列表供后续 Rerank 和图片过滤处理，且每轮用户问题经过 Query Rewrite 后重复命中概率相对较低。
5. Query Rewrite 自身有独立缓存，改写步骤不会每次都重复调用模型。

---

## 十三、热更新能力

核心代码：

- `src/main/java/com/XI/xi_oj/service/impl/AiConfigServiceImpl.java`
- `src/main/java/com/XI/xi_oj/ai/event/AiConfigChangedEvent.java`
- `src/main/java/com/XI/xi_oj/ai/agent/AiModelHolder.java`
- `src/main/java/com/XI/xi_oj/ai/rag/QueryRewriter.java`

配置更新链路：

```text
前端管理页修改 ai_config
  -> AiConfigServiceImpl 保存配置
  -> applicationEventPublisher.publishEvent(...)
  -> @EventListener 监听配置变化
  -> AiModelHolder / QueryRewriter 按 key 重建相关对象
```

支持热更新的 RAG 相关配置包括：

1. `ai.rag.top_k`
2. `ai.rag.similarity_threshold`
3. Query Rewrite 相关开关和模型配置
4. Rerank 相关开关和模型配置
5. Chat model / embedding model 配置
6. Prompt 配置

优势：

1. 调整 RAG 参数不需要重启服务。
2. 切换模型供应商后，相关 agent 会重建。
3. RAG 调参可以通过前端管理页完成。

---

## 十四、防幻觉链接过滤

核心代码：

- `src/main/java/com/XI/xi_oj/ai/filter/LinkValidationFilter.java`

作用：

模型在生成题目推荐时，可能会编造不存在的题目链接，例如：

```text
/view/question/12345
```

`LinkValidationFilter` 会校验题目 ID 是否真实存在。

如果不存在，则移除假链接。

这属于 RAG 输出质量控制的一部分，因为 RAG 可以降低幻觉，但不能完全杜绝模型编造。

---

## 十五、Agent Trace 执行轨迹记录

核心代码：

- `src/main/java/com/XI/xi_oj/service/impl/AgentTraceService.java`
- `src/main/java/com/XI/xi_oj/model/entity/AgentTraceLog.java`
- `src/main/java/com/XI/xi_oj/mapper/AgentTraceLogMapper.java`

作用：

Agent Loop 每次执行会记录完整的推理轨迹，包括每一步的 Thought、Action、ActionInput、Observation。

记录内容：

1. 用户原始 query。
2. 每步推理的思考过程和工具调用。
3. 工具返回结果。
4. 最终 Answer。
5. 总步数和执行耗时。

价值：

1. 可以回溯 Agent 的推理过程，排查回答质量问题。
2. 为后续可观测性看板提供数据基础。
3. 可以分析哪些工具被频繁调用、哪些工具经常失败。

---

## 十六、当前优化亮点总结

当前项目的 RAG 优化亮点可以总结为：

1. **双链路统一优化**
   AiServices 自动 RAG 和自定义 Agent Loop 手动 RAG 都接入了 Query Rewrite、Rerank 和图片过滤。

2. **双 Collection 隔离**
   `oj_knowledge` 和 `oj_question` 分离，减少知识库和题目向量互相污染。

3. **metadata 驱动检索**
   通过 `content_type`、`tag`、`source_type`、`difficulty`、`question_id` 等字段进行业务级过滤。

4. **Query Rewrite 提升召回**
   将用户口语问题改写为更适合向量检索的算法关键词。

5. **Rerank 提升排序质量**
   向量召回后进行精排，提高 TopK 上下文质量。

6. **图片 RAG 工程化**
   从简单 `image_urls` 升级到结构化 `image_refs`，支持按图片语义过滤。

7. **PDF 图文关联更准确**
   结合图片页面位置、附近文本、chunk 标题和标签判断图片归属。

8. **Redis 缓存降低检索成本**
   对重复 RAG 查询进行缓存，减少 embedding 和 Milvus 开销。

9. **配置热更新**
   RAG 参数、模型、Prompt 可以动态更新，便于调试和运营。

10. **输出结果防护**
    通过 `LinkValidationFilter` 移除模型编造的题目链接。

11. **Agent Loop 过度召回 + Rerank**
    Agent Loop 向量检索 `topK * 2` 候选，Rerank 精排后取 `topK`，给精排模型更大的候选池。

12. **Agent Loop 流式输出**
    `runStreaming()` 支持 SSE 流式推送，包含进度状态消息和 `Answer:` 标记检测。

13. **Agent 执行轨迹记录**
    `AgentTraceService` 记录每次 Agent Loop 的完整推理轨迹，支持回溯和分析。

---

## 十七、重新导入知识库时的注意事项

由于 `image_refs` 是新 metadata，旧向量数据中没有该字段。

要让图片精准过滤真正生效，需要：

1. 删除或清空旧的知识库 collection。
2. 删除 MinIO 中旧 PDF 对应目录下的图片，避免重复数据。
3. 清理 RAG Redis 缓存。
4. 重新导入 PDF。
5. 等待异步导入任务完成。

对于 `hello-algo_1.3.0_zh_java.pdf` 这类超过 10MB 的文件，当前项目会走异步导入：

```text
KnowledgeImportController
  -> KnowledgeImportAsyncService.importAsync(...)
```

图片处理会增加一定耗时，主要新增开销为：

1. 提取图片位置。
2. 提取图片附近文本。
3. 生成 `image_refs`。

整体大头仍然通常是 embedding、Milvus 写入和 MinIO 上传图片。

---

## 十八、后续可继续优化方向

后续可以优先考虑：

1. **导入历史记录**
   记录文件名、导入时间、chunk 数量、图片数量、collection、MinIO 前缀。

2. **按文件删除知识**
   支持按 `source_file` 删除对应向量和 MinIO 图片，而不是每次清空整个 collection。

3. **图片 caption / OCR**
   对图片做 OCR 或视觉模型 caption，补充 `caption` 字段，提高图片匹配准确率。

4. **RAG 评估常态化**
   将 `RagEvaluationTest` 做成固定评估集，持续观察 Recall@K、MRR、Rerank 命中率。

5. **可观测性看板**
   统计 RAG 检索为空次数、Rerank 调用次数、图片过滤命中次数、假链接移除次数等。

6. **chunk 删除和增量更新**
   支持单文件增量重建，减少全量重导成本。

---

## 十九、一句话总结

当前 XI OJ 的 RAG 已经从基础向量检索，升级为：

```text
可热更新、可精排、可按业务 metadata 过滤、支持双链路 Agent（含流式输出与执行轨迹记录）、支持图文知识库、并带输出防护的工程化 RAG 系统。
```

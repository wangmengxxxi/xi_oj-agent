# XI OJ AIGC功能整合 — 执行步骤

> 版本：V1.0 | 日期：2026-04-10
> 参考文档：`backend_dev.md` / `前端页面拓展文档.md` / `前端方案设计.md`
> 前置条件：基础项目完成（用户体系 + 题库 + 代码沙箱 + 题目提交限流）

---

## 执行顺序总览

```
阶段零：环境准备 + 依赖引入（P0）
    ↓
阶段一：数据库建表（P0）
    ↓
阶段二：后端数据层（P0）
    ├── 实体类 + Mapper
    └── AiConfigService（配置读取服务）
    ↓
阶段三：AIGC 核心层（P0）
    ├── Milvus 向量库配置
    ├── RAG 检索模块（OJKnowledgeRetriever）
    ├── Agent 工具类（OJTools）
    └── Agent 实例配置（OJAgentConfig）
    ↓
阶段四：AI 功能模块（P0/P1）
    ├── AI 全局开关拦截（P0）
    ├── AI 问答（P0）
    ├── AI 代码分析（P0）
    ├── AI 题目解析 + 相似题推荐（P1）
    └── AI 错题本（P1）
    ↓
阶段五：业务扩展模块（P1）
    ├── 用户信息拓展（UserProfile）
    └── 题目评论区（QuestionComment）
    ↓
阶段六：错题自动收集（集成 JudgeService）（P1）
    ↓
阶段七：AI 配置管理接口（P1）
    ↓
阶段八：向量库数据导入（P1）
    ↓
阶段九：前端页面（P1/P2）
    ↓
阶段十：测试与验收（P2）
```

---

## 阶段零：环境准备与依赖引入（P0）

### 步骤 0.1 — 部署 Milvus 向量数据库

使用 Docker 启动 Milvus 单机版（Standalone）：

```bash
# 下载 docker-compose 并启动
wget https://github.com/milvus-io/milvus/releases/download/v2.3.x/milvus-standalone-docker-compose.yml -O docker-compose.yml
docker compose up -d
# 默认端口：gRPC 19530，HTTP 9091
```

**验收标准**：`docker ps` 可见 milvus-standalone 容器运行中。

### 步骤 0.2 — 引入 Maven 依赖

文件：`pom.xml`

在 `<dependencyManagement>` 中添加 BOM，在 `<dependencies>` 中添加具体模块（§3.3 依赖清单）：

```xml
<!-- dependencyManagement 中 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-bom</artifactId>
    <version>1.0.0-beta3</version>
    <type>pom</type><scope>import</scope>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-bom</artifactId>
    <version>1.0.0-beta3</version>
    <type>pom</type><scope>import</scope>
</dependency>

<!-- dependencies 中 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-dashscope</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-milvus</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 步骤 0.3 — 配置 application.yml

文件：`src/main/resources/application.yml`

新增以下配置节（实际值替换为真实环境参数）：

```yaml
# 阿里百炼 API（初始值，后续通过 ai_config 表动态管理）
ai:
  api-key: ${AI_API_KEY:your-dashscope-api-key}
  model-name: qwen-plus
  embedding-model: text-embedding-v3
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1

# Milvus 向量库
milvus:
  host: localhost
  port: 19530
  collection-name: oj_knowledge

# RabbitMQ（异步任务）
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

---

## 阶段一：数据库建表（P0）

### 步骤 1.1 — 执行 DDL 建表

新建 `sql/aigc_tables.sql`，按以下顺序执行6张表（§4.2 DDL）：

| 顺序 | 表名 | 用途 |
|------|------|------|
| 1 | `ai_config` | AI系统配置（全局开关、API密钥、RAG参数） |
| 2 | `ai_chat_record` | AI问答对话历史 |
| 3 | `ai_code_analysis` | 代码AI分析记录 |
| 4 | `question_comment` | 题目评论表 |
| 5 | `user_profile` | 用户信息拓展表 |
| 6 | `ai_wrong_question` | AI错题本 |

### 步骤 1.2 — 初始化 ai_config 表数据

插入7条核心配置（§4.2.1 初始化配置）：

```sql
INSERT INTO ai_config (config_key, config_value, description) VALUES
('ai.global.enable',         'true',          'AI功能全局开关'),
('ai.model.api_key',         'your-api-key',  '大模型API密钥'),
('ai.model.base_url',        'https://dashscope.aliyuncs.com/compatible-mode/v1', '百炼端点'),
('ai.model.name',            'qwen-plus',     '聊天模型名称'),
('ai.model.embedding_name',  'text-embedding-v3', '嵌入模型名称'),
('ai.rag.top_k',             '3',             'RAG检索返回条数'),
('ai.rag.similarity_threshold', '0.7',        'RAG最小相似度阈值');
```

---

## 阶段二：后端数据层（P0）

### 步骤 2.1 — 创建6个实体类

位置：`src/main/java/com/XI/xi_oj/model/entity/`

| 文件 | 对应表 |
|------|--------|
| `AiConfig.java` | `ai_config` |
| `AiChatRecord.java` | `ai_chat_record` |
| `AiCodeAnalysis.java` | `ai_code_analysis` |
| `QuestionComment.java` | `question_comment` |
| `UserProfile.java` | `user_profile`（`uk_user_id` 唯一键） |
| `AiWrongQuestion.java` | `ai_wrong_question` |

注意：使用 `@TableName`、`@TableId(type = IdType.AUTO)`，时间字段用 `Date`（与现有实体风格一致）。

### 步骤 2.2 — 创建6个 Mapper 接口

位置：`src/main/java/com/XI/xi_oj/mapper/`

每个 Mapper extends `BaseMapper<对应实体>`，加 `@Mapper` 注解。

### 步骤 2.3 — 实现 AiConfigService

位置：`src/main/java/com/XI/xi_oj/service/`

核心方法：

```java
public interface AiConfigService extends IService<AiConfig> {
    String getConfigValue(String configKey);   // 优先从 Redis 缓存读取
    void refreshCache(String configKey);       // 管理员修改后刷新
    boolean isAiEnabled();                     // 读取 ai.global.enable
}
```

实现要点：
- `getConfigValue()` 先查 Redis（key：`ai:config:{configKey}`，TTL 10分钟），miss 再查 DB
- `@PostConstruct` 预热所有配置到 Redis

---

## 阶段三：AIGC 核心层（P0）

### 步骤 3.1 — Milvus 向量库配置

位置：`src/main/java/com/XI/xi_oj/config/MilvusConfig.java`

```java
@Configuration
public class MilvusConfig {

    @Bean
    public MilvusEmbeddingStore milvusEmbeddingStore(
            @Value("${milvus.host}") String host,
            @Value("${milvus.port}") int port,
            @Value("${milvus.collection-name}") String collection) {
        return MilvusEmbeddingStore.builder()
                .host(host).port(port)
                .collectionName(collection)
                .dimension(1024)           // text-embedding-v3 默认维度
                .autoFlushOnInsert(true)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(AiConfigService configService) {
        return QwenEmbeddingModel.builder()
                .apiKey(configService.getConfigValue("ai.model.api_key"))
                .modelName(configService.getConfigValue("ai.model.embedding_name"))
                .build();
    }
}
```

**验收标准**：项目启动后 Milvus 集合 `oj_knowledge` 自动创建（首次运行）。

### 步骤 3.2 — 实现 OJKnowledgeRetriever（RAG 检索）

位置：`src/main/java/com/XI/xi_oj/ai/retriever/OJKnowledgeRetriever.java`

基于 §5.1.1 代码，实现：
- `retrieve(query, topK, minScore)` — 通用向量检索，返回上下文文本
- `retrieveSimilarQuestion(questionId, questionContent)` — 相似题检索，返回题目 ID 列表

### 步骤 3.3 — 实现 OJTools（Agent 工具类）

位置：`src/main/java/com/XI/xi_oj/ai/tools/OJTools.java`

基于 §5.1.2 代码，实现3个 `@Tool` 方法：

| 工具 | 入参 | 说明 |
|------|------|------|
| `query_question_info` | keyword | 按关键词/ID查题目信息 |
| `judge_user_code` | `题目ID\|代码\|语言` | 调用 JudgeService 评测 |
| `query_user_wrong_question` | `userId\|questionId` | 查错题记录 |

### 步骤 3.4 — 配置 OJAssistantAgent

位置：`src/main/java/com/XI/xi_oj/ai/config/OJAgentConfig.java`

基于 §5.1.2 代码构建 Agent Bean：
- `ChatModel` 从 `ai_config` 动态读取 API Key 和 modelName
- `ContentRetriever` 的 topK 和 minScore 从 `ai_config` 读取
- 使用 `MessageWindowChatMemory.withMaxMessages(20)` 支持多轮对话
- 通过 `chatMemoryProvider` 按 `memoryId`（= userId 或 chatId）隔离不同用户的对话历史

---

## 阶段四：AI 功能模块（P0/P1）

### 步骤 4.1 — AI 全局开关拦截（P0）

位置：`src/main/java/com/XI/xi_oj/aop/AiGlobalSwitchInterceptor.java`

创建 AOP 拦截器，切点为 `@AiRequired` 注解（自定义注解，与 `@RateLimit` 设计一致）：
- 从 `AiConfigService.isAiEnabled()` 读取全局开关
- 若为 false，抛 `BusinessException(ErrorCode.FORBIDDEN_ERROR, "AI功能暂时不可用")`

### 步骤 4.2 — 实现 AI 问答模块（P0）

位置：`src/main/java/com/XI/xi_oj/service/AiChatService.java`

接口方法：

```java
String chat(String userQuery, Long userId, String chatId);
List<AiChatRecord> getChatHistory(Long userId, String chatId);
void clearChatHistory(Long userId, String chatId);
```

**Controller**（路径 `/ai/chat`）：

| 接口 | 方法 | 说明 |
|------|------|------|
| `POST /api/ai/chat` | 发送消息 | 调 Agent，结果存 `ai_chat_record` |
| `GET /api/ai/chat/history` | 获取历史 | 按 userId + chatId 查 |
| `POST /api/ai/chat/clear` | 清空历史 | 逻辑删除相关记录 |

实现要点：
- `chatId` 为前端生成的 UUID，用于区分多次会话
- Agent 调用通过 `chatMemoryProvider(memoryId -> ...)` 关联历史，`memoryId` 用 `userId + ":" + chatId`
- 高频问题可将结果 Redis 缓存1小时（可选优化）

### 步骤 4.3 — 实现 AI 代码分析模块（P0）

位置：`src/main/java/com/XI/xi_oj/service/AiCodeAnalysisService.java`

接口方法：

```java
AiCodeAnalysis analyzeCode(Long questionId, String code, String language, String judgeResult, Long userId);
List<AiCodeAnalysis> getAnalysisHistory(Long userId);
```

实现要点：
- 拼装 §5.2 Prompt 模板：题目信息 + 用户代码 + 判题结果 + RAG 检索的题解知识点
- 调用 Agent 生成分析结果，存入 `ai_code_analysis` 表
- 该接口应设为异步（`@Async`），避免阻塞用户（AI 响应可能需要3-10秒）

**Controller**（路径 `/ai/code`）：

| 接口 | 方法 |
|------|------|
| `POST /api/ai/code/analysis` | 提交代码分析（异步返回，或 SSE 流式） |
| `GET /api/ai/code/history` | 获取历史分析记录 |

### 步骤 4.4 — 实现 AI 题目解析 + 相似题推荐（P1）

位置：`src/main/java/com/XI/xi_oj/service/AiQuestionService.java`

接口方法：

```java
String getQuestionParse(Long questionId);          // 题目 AI 解析
List<QuestionVO> getSimilarQuestions(Long questionId); // 相似题推荐
```

实现要点：
- 高频内容缓存到 Redis（TTL 1小时）
- 相似题通过 `OJKnowledgeRetriever.retrieveSimilarQuestion()` 检索，返回题目 ID 后查 DB 组装 `QuestionVO`

**Controller**（路径 `/ai/question`）：

| 接口 | 方法 |
|------|------|
| `GET /api/ai/question/parse?questionId=xxx` | 获取题目解析 |
| `GET /api/ai/question/similar?questionId=xxx` | 获取相似题目列表 |

### 步骤 4.5 — 实现 AI 错题本模块（P1）

位置：`src/main/java/com/XI/xi_oj/service/WrongQuestionService.java`

接口方法：

```java
// 错题自动收集（由 JudgeService 调用，阶段六集成）
void collectWrongQuestion(Long userId, Long questionId, String wrongCode, String judgeResult);
// 触发 AI 错误分析
AiWrongQuestion analyzeWrongQuestion(Long wrongQuestionId, Long userId);
// 标记已复习
void markReviewed(Long wrongQuestionId, Long userId);
// 查询错题列表（支持筛选）
Page<AiWrongQuestion> listWrongQuestions(Long userId, String judgeResult, int current, int pageSize);
```

**Controller**（路径 `/ai/wrong-question`）：

| 接口 | 说明 |
|------|------|
| `GET /api/ai/wrong-question/list` | 分页查询，支持按错误类型筛选 |
| `GET /api/ai/wrong-question/analysis?id=xxx` | 获取指定错题的 AI 分析 |
| `POST /api/ai/wrong-question/review` | 标记已复习，更新 is_reviewed + next_review_time |

---

## 阶段五：业务扩展模块（P1）

### 步骤 5.1 — 用户信息拓展（UserProfile）

位置：`src/main/java/com/XI/xi_oj/service/UserProfileService.java`

接口方法：

```java
UserProfile getOrCreateByUserId(Long userId);  // 不存在则自动创建空记录
void updateProfile(Long userId, String school, String signature);
UserProfileVO getProfileVO(Long userId);       // 组合 user + user_profile 数据
```

**Controller**：在现有 `UserController` 中追加接口，或新建 `UserProfileController`：

| 接口 | 说明 |
|------|------|
| `GET /api/user/profile?id=xxx` | 获取用户拓展信息 |
| `POST /api/user/profile/update` | 更新学校、个性签名 |

### 步骤 5.2 — 题目评论区（QuestionComment）

位置：`src/main/java/com/XI/xi_oj/service/QuestionCommentService.java`

接口方法：

```java
Page<QuestionCommentVO> listComments(Long questionId, int current, int pageSize);
long addComment(Long questionId, Long userId, String content, Long parentId);
void deleteComment(Long commentId, Long userId);  // 本人或管理员
void likeComment(Long commentId, Long userId);
```

**Controller**（路径 `/question/comment`）：标准 CRUD 接口。

---

## 阶段六：错题自动收集（P1）

### 步骤 6.1 — 集成到 JudgeServiceImpl

文件：`src/main/java/com/XI/xi_oj/judge/JudgeServiceImpl.java`

在 `doJudge()` 更新 QuestionSubmit 状态之后，判断判题结果为失败时自动收集：

```java
// 判题完成后，若结果为失败（WA/TLE/RE/CE），自动记录到错题本
JudgeInfo judgeInfo = JSONUtil.toBean(questionSubmit.getJudgeInfo(), JudgeInfo.class);
if (judgeInfo != null && !"Accepted".equals(judgeInfo.getMessage())) {
    try {
        wrongQuestionService.collectWrongQuestion(
            questionSubmit.getUserId(),
            questionSubmit.getQuestionId(),
            questionSubmit.getCode(),
            judgeInfo.getMessage()
        );
    } catch (Exception e) {
        log.error("错题收集失败，questionSubmitId={}", questionSubmitId, e);
    }
}
```

注意：通过 `@Lazy` 注入 `WrongQuestionService`，避免与 `QuestionSubmitService` 产生循环依赖。

---

## 阶段七：AI 配置管理接口（P1）

### 步骤 7.1 — 实现 AdminAiConfigController

位置：`src/main/java/com/XI/xi_oj/controller/admin/AdminAiConfigController.java`

所有接口加 `@AuthCheck(mustRole = "admin")`：

| 接口 | 说明 |
|------|------|
| `GET /api/admin/ai/config` | 获取所有 AI 配置（API Key 脱敏展示） |
| `POST /api/admin/ai/config` | 修改配置（修改后刷新 Redis 缓存） |

修改 `ai.model.api_key` 后需立即重建 Agent Bean（可通过 ApplicationContext 刷新，或提示重启服务）。

---

## 阶段八：向量库数据导入（P1）

### 步骤 8.1 — 实现向量化工具

位置：`src/main/java/com/XI/xi_oj/ai/vectorstore/KnowledgeStoreService.java`

```java
public interface KnowledgeStoreService {
    // 将单道题目写入向量库
    void indexQuestion(Question question);
    // 批量全量导入（管理员触发）
    void indexAllQuestions();
    // 删除/更新向量（题目修改后同步）
    void reindexQuestion(Long questionId);
}
```

### 步骤 8.2 — 触发全量初始化

启动服务后，管理员调用 `POST /api/admin/ai/knowledge/init` 触发全量题目向量化：
- 遍历所有题目，将 title + content + tags + answer 拼接后向量化
- 写入 Milvus，附带 metadata（questionId、tags、difficulty、content_type）

> 向量化是耗时操作，建议通过 RabbitMQ 异步任务完成，避免接口超时。

---

## 阶段九：前端页面（P1/P2）

### 步骤 9.1 — 类型定义 + API 封装（P1）

文件：`frontend/OJ_frontend/src/types/ai.ts`

定义所有 AI 相关类型（AiChatRecord、AiCodeAnalysis、AiWrongQuestion、UserProfileVO 等）。

文件：`frontend/OJ_frontend/src/api/ai.ts`

封装所有 AI 接口调用函数（对应 §9.1 接口规范）。

### 步骤 9.2 — AI 问答助手页（P1）

文件：`frontend/OJ_frontend/src/views/ai/AiChatView.vue`

参考前端页面拓展文档 §2：
- 对话气泡布局，支持 Markdown 渲染（代码块高亮）
- 限流触发（code=42900）时禁用输入框，展示 message 原文
- 新建会话按钮，历史记录按 chatId 分组切换
- AI 全局开关关闭时展示「AI 功能暂时不可用」

### 步骤 9.3 — AI 代码分析页（P1）

文件：`frontend/OJ_frontend/src/views/ai/AiCodeAnalysisView.vue`

参考前端页面拓展文档 §3：
- 左侧只读 Monaco Editor 展示用户代码
- 右侧 Markdown 渲染分析结果
- 3-10 秒异步等待，显示加载动画

**入口**：做题页提交成功后，结果区展示「AI 分析」按钮，跳转本页。

### 步骤 9.4 — AI 错题本页（P1）

文件：`frontend/OJ_frontend/src/views/ai/WrongQuestionView.vue`

参考前端页面拓展文档 §4：
- 错题列表按「下次复习时间」排序，今日到期项标红
- 点击「查看分析」弹出 Drawer，展示 AI 分析结果
- 「标记已复习」更新状态并移出待复习队列
- 支持按错误类型（WA/TLE/RE/CE）筛选

### 步骤 9.5 — AI 题目解析嵌入做题页（P2）

文件：`frontend/OJ_frontend/src/views/question/ViewQuestionView.vue`

在题目描述下方新增折叠区域：
- 「AI 解析」折叠面板（按需加载，首次展开时请求接口）
- 「相似题目推荐」列表，点击题目直接跳转

### 步骤 9.6 — 用户个人主页 + 设置页（P2）

文件：
- `frontend/OJ_frontend/src/views/user/UserProfileView.vue`（`/user/profile/:id`）
- `frontend/OJ_frontend/src/views/user/UserSettingsView.vue`（`/user/settings`）

参考前端页面拓展文档 §5、§6。

### 步骤 9.7 — AI 配置管理页（P2）

文件：`frontend/OJ_frontend/src/views/admin/AiConfigView.vue`（`/admin/ai-config`）

参考前端页面拓展文档 §7：API Key 脱敏展示，修改时需二次确认。

### 步骤 9.8 — 路由 + 导航更新（P1）

文件：`frontend/OJ_frontend/src/router/routes.ts`

追加以下路由：

```typescript
{ path: '/ai/chat',            name: 'AiChat',            component: () => import('@/views/ai/AiChatView.vue'),            meta: { requiresRole: 'user', title: 'AI 助手', showInMenu: true } },
{ path: '/ai/code-analysis',   name: 'AiCodeAnalysis',    component: () => import('@/views/ai/AiCodeAnalysisView.vue'),    meta: { requiresRole: 'user' } },
{ path: '/ai/wrong-question',  name: 'WrongQuestion',     component: () => import('@/views/ai/WrongQuestionView.vue'),     meta: { requiresRole: 'user', title: '错题本', showInMenu: true } },
{ path: '/user/profile/:id',   name: 'UserProfile',       component: () => import('@/views/user/UserProfileView.vue'),     meta: { requiresRole: 'user' } },
{ path: '/user/settings',      name: 'UserSettings',      component: () => import('@/views/user/UserSettingsView.vue'),    meta: { requiresRole: 'user' } },
{ path: '/admin/ai-config',    name: 'AiConfig',          component: () => import('@/views/admin/AiConfigView.vue'),       meta: { requiresRole: 'admin', title: 'AI 配置', showInMenu: true } },
```

导航栏新增：「AI 助手」「错题本」（AI 全局开关关闭时自动隐藏），头像下拉新增「个人主页」「个人设置」，管理员菜单新增「AI 配置」。

---

## 阶段十：测试与验收（P2）

### 步骤 10.1 — 后端测试场景

| 测试场景 | 验证点 |
|---------|-------|
| AI 全局开关 off 后调用 AI 接口 | 返回「AI功能暂时不可用」 |
| 发送 AI 问答消息 | 返回有效回复，记录写入 ai_chat_record |
| 提交错误代码后 | ai_wrong_question 自动写入 |
| 触发 AI 错题分析 | 返回 Prompt 生成的分析结果 |
| 相似题推荐 | Milvus 返回向量相似度 ≥ 0.75 的题目 |
| 管理员修改 AI 配置 | Redis 缓存刷新，下次调用使用新配置 |

### 步骤 10.2 — 前后端联调

1. 管理员流程：配置 API Key → 触发全量题目向量化 → 验证 Milvus 写入
2. 用户流程：AI 问答对话 → 查看历史 → 清空历史
3. 做题流程：提交错误代码 → 自动收录错题 → 查看 AI 分析 → 标记已复习
4. 开关测试：关闭 AI 全局开关 → 前端隐藏 AI 入口 → AI 接口返回403

---

## 开发优先级汇总

| 优先级 | 任务 | 阶段 |
|--------|------|------|
| P0 | Milvus 部署 + pom.xml 依赖引入 | 阶段零 |
| P0 | application.yml 配置（AI/Milvus/RabbitMQ） | 阶段零 |
| P0 | 6张表 DDL + ai_config 初始化数据 | 阶段一 |
| P0 | 6个实体 + Mapper + AiConfigService | 阶段二 |
| P0 | MilvusConfig + EmbeddingModel Bean | 阶段三 |
| P0 | OJKnowledgeRetriever（RAG检索） | 阶段三 |
| P0 | OJTools（Agent工具类） | 阶段三 |
| P0 | OJAgentConfig（Agent实例构建） | 阶段三 |
| P0 | AI 全局开关 AOP 拦截 | 阶段四 |
| P0 | AI 问答 Service + Controller | 阶段四 |
| P0 | AI 代码分析 Service + Controller | 阶段四 |
| P1 | AI 题目解析 + 相似题推荐 | 阶段四 |
| P1 | AI 错题本 Service + Controller | 阶段四 |
| P1 | UserProfile + QuestionComment | 阶段五 |
| P1 | JudgeServiceImpl 集成错题收集 | 阶段六 |
| P1 | AdminAiConfigController | 阶段七 |
| P1 | KnowledgeStoreService + 全量初始化 | 阶段八 |
| P1 | AI 类型定义 + API 封装 | 阶段九 |
| P1 | AiChatView + WrongQuestionView | 阶段九 |
| P1 | 路由 + 导航更新 | 阶段九 |
| P2 | AiCodeAnalysisView + 做题页 AI 解析嵌入 | 阶段九 |
| P2 | UserProfileView + UserSettingsView | 阶段九 |
| P2 | AiConfigView（管理员）| 阶段九 |
| P2 | 全功能测试 + 向量化效果调优 | 阶段十 |

---

*文档版本：V1.0 | 生成日期：2026-04-10*

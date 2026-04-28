package com.XI.xi_oj.ai.agent;

import com.XI.xi_oj.ai.event.AiConfigChangedEvent;
import com.XI.xi_oj.ai.tools.OJTools;
import com.XI.xi_oj.service.AiConfigService;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.XI.xi_oj.ai.rag.ImageAwareContentRetriever;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * AI模型持有者类，负责管理和初始化各种AI模型和代理
 * 该类使用Spring框架的组件注解，并提供了模型和代理的获取方法
 */
@Component
@Slf4j
public class AiModelHolder {

    // 定义模型名称的配置键集合
    private static final Set<String> MODEL_NAME_KEYS = Set.of("ai.model.name");
    // 定义嵌入模型名称的配置键集合
    private static final Set<String> EMBEDDING_NAME_KEYS = Set.of("ai.model.embedding_name");
    // 定义RAG（检索增强生成）相关的配置键集合
    private static final Set<String> RAG_KEYS = Set.of("ai.rag.top_k", "ai.rag.similarity_threshold");

    // 依赖注入的配置服务
    private final AiConfigService aiConfigService;
    // OJ工具服务
    private final OJTools ojTools;
    // Milvus向量存储（知识点）
    private final MilvusEmbeddingStore embeddingStore;
    // Milvus向量存储（题目）
    private final MilvusEmbeddingStore questionEmbeddingStore;
    // 聊天记忆存储
    private final ChatMemoryStore chatMemoryStore;

    // 从配置文件中注入API密钥
    @Value("${ai.model.api-key}")
    private String apiKey;



    // 可变类型的AI模型和代理
    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingChatModel;
    private volatile EmbeddingModel embeddingModel;            // 嵌入模型
    private volatile OJChatAgent ojChatAgent;                  // OJ聊天代理
    private volatile OJQuestionParseAgent ojQuestionParseAgent;  // OJ问题解析代理
    private volatile OJStreamingService ojStreamingService;    // OJ流式服务

    /**
     * 构造函数，注入必要的依赖项
     * @param aiConfigService AI配置服务
     * @param ojTools OJ工具服务
     * @param embeddingStore 嵌入模型存储
     * @param chatMemoryStore 聊天记忆存储
     */
    public AiModelHolder(AiConfigService aiConfigService,
                         OJTools ojTools,
                         @Qualifier("embeddingStore") MilvusEmbeddingStore embeddingStore,
                         @Qualifier("questionEmbeddingStore") MilvusEmbeddingStore questionEmbeddingStore,
                         ChatMemoryStore chatMemoryStore) {
        this.aiConfigService = aiConfigService;
        this.ojTools = ojTools;
        this.embeddingStore = embeddingStore;
        this.questionEmbeddingStore = questionEmbeddingStore;
        this.chatMemoryStore = chatMemoryStore;
    }

    /**
     * 初始化方法，在Bean创建后自动调用
     * 用于初始化所有AI模型和代理
     */
    @PostConstruct
    public void init() {
        this.chatModel = buildChatModel();
        this.streamingChatModel = buildStreamingChatModel();
        this.embeddingModel = buildEmbeddingModel();
        this.ojStreamingService = buildStreamingService(this.streamingChatModel);
        this.ojChatAgent = buildChatAgent();
        this.ojQuestionParseAgent = buildQuestionParseAgent();
        log.info("[AiModelHolder] all AI models and agents initialized");
    }

    /**
     * 配置变更事件监听器
     * 当AI配置发生变化时，重新构建相应的模型和代理
     * @param event 配置变更事件
     */
    @EventListener
    public void onConfigChanged(AiConfigChangedEvent event) {
        String key = event.getConfigKey();

        // 如果是模型名称相关配置变更
        if (MODEL_NAME_KEYS.contains(key)) {
            this.chatModel = buildChatModel();
            this.streamingChatModel = buildStreamingChatModel();
            this.ojStreamingService = buildStreamingService(this.streamingChatModel);
            this.ojChatAgent = buildChatAgent();
            this.ojQuestionParseAgent = buildQuestionParseAgent();
            log.info("[AiModelHolder] chat models and all agents rebuilt for config: {}", key);

        // 如果是嵌入模型名称相关配置变更
        } else if (EMBEDDING_NAME_KEYS.contains(key)) {
            this.embeddingModel = buildEmbeddingModel();
            this.ojChatAgent = buildChatAgent();
            this.ojQuestionParseAgent = buildQuestionParseAgent();
            log.info("[AiModelHolder] embedding model and agents rebuilt for config: {}", key);

        // 如果是RAG相关配置变更
        } else if (RAG_KEYS.contains(key)) {
            this.ojChatAgent = buildChatAgent();
            this.ojQuestionParseAgent = buildQuestionParseAgent();
            log.info("[AiModelHolder] agents rebuilt for RAG config: {}", key);
        }
    }

    // ── getters ──

    /**
     * 获取聊天语言模型
     * @return ChatLanguageModel 聊天语言模型实例
     */
    public ChatModel getChatModel() {
        return chatModel;
    }

    /**
     * 获取流式聊天语言模型
     * @return StreamingChatLanguageModel 流式聊天语言模型实例
     */
    public StreamingChatModel getStreamingChatModel() {
        return streamingChatModel;
    }

    /**
     * 获取嵌入模型
     * @return EmbeddingModel 嵌入模型实例
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * 获取OJ聊天代理
     * @return OJChatAgent OJ聊天代理实例
     */
    public OJChatAgent getOjChatAgent() {
        return ojChatAgent;
    }

    /**
     * 获取OJ问题解析代理
     * @return OJQuestionParseAgent OJ问题解析代理实例
     */
    public OJQuestionParseAgent getOjQuestionParseAgent() {
        return ojQuestionParseAgent;
    }

    /**
     * 获取OJ流式服务
     * @return OJStreamingService OJ流式服务实例
     */
    public OJStreamingService getOjStreamingService() {
        return ojStreamingService;
    }

    // ── builders ──

    /**
     * 构建聊天语言模型
     * @return ChatLanguageModel 构建好的聊天语言模型
     */
    private ChatModel buildChatModel() {
        String modelName = aiConfigService.getConfigValue("ai.model.name");
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2f)
                .maxTokens(4096)
                .build();
    }

    /**
     * 构建流式聊天语言模型
     * @return StreamingChatLanguageModel 构建好的流式聊天语言模型
     */
    private StreamingChatModel buildStreamingChatModel() {
        String modelName = aiConfigService.getConfigValue("ai.model.name");
        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2f)
                .maxTokens(4096)
                .build();
    }

    /**
     * 构建嵌入模型
     * @return EmbeddingModel 构建好的嵌入模型
     */
    private EmbeddingModel buildEmbeddingModel() {
        String embeddingName = aiConfigService.getConfigValue("ai.model.embedding_name");
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingName)
                .build();
    }

    /**
     * 构建流式服务
     * @param model 流式聊天语言模型
     * @return OJStreamingService 构建好的流式服务
     */
    private OJStreamingService buildStreamingService(StreamingChatModel model) {
        return fullPrompt -> Flux.create(sink -> model.chat(
                fullPrompt,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        sink.next(partialResponse == null ? "" : partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        sink.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        sink.error(error);
                    }
                }
        ));
    }

    /**
     * 构建聊天代理
     * @return OJChatAgent 构建好的聊天代理
     */
    private static final String DEFAULT_CHAT_SYSTEM_PROMPT = """
            你是XI OJ平台的智能编程助教，严格遵循以下规则：
            1. 仅回答编程、算法、OJ题目相关问题，无关问题直接拒绝；
            2. 分析代码或错题时，先指出错误、再给出改进思路，不直接提供完整可运行的标准答案；
            3. 解题讲解需分步骤，适配新手学习节奏，结合RAG提供的知识点进行说明；
            4. 回答语言为中文，格式清晰，重点突出。
            【辅导策略 — 苏格拉底式引导】
            - 当用户请求解题帮助时，不要直接给出答案，而是通过提问和提示引导用户自主思考；
            - 先调用 query_user_mastery 了解用户薄弱知识点，针对性调整讲解深度；
            - 使用 get_question_hints 分层提示：先给 Level 1（考点），用户仍有困难再给 Level 2（方向），最后才给 Level 3（框架）；
            - 每次回答后追问用户的理解，如"你觉得这里为什么要用这个数据结构？"；
            - 对薄弱知识点的题目，主动推荐相关练习题。
            【错误诊断策略】
            - 当用户代码出错时，先分析错误类型（WA/TLE/MLE/RE），再针对性诊断；
            - 使用 run_custom_test 构造边界测试用例验证用户代码，帮助定位具体错误场景；
            - 使用 diagnose_error_pattern 分析用户的系统性错误模式，给出针对性改进建议；
            - 对于 WA：尝试构造边界输入（空输入、最大值、特殊字符等）找出反例；
            - 对于 TLE：分析算法复杂度，建议优化方向；
            - 对于 MLE：检查数据结构选择和内存使用。
            【回答风格】
            - 简洁直接，不要输出工具调用的中间过程或思考过程（如"让我先查一下""没查到，换个方式试试"）；
            - 工具返回无结果时，直接告知用户结果即可，不要描述你接下来要做什么；
            - 工具返回的markdown链接（如 [标题](/view/question/123)）必须原样保留在回答中，不要去掉链接格式；
            - 展示题目列表时使用工具返回的链接格式，方便用户直接点击跳转。
            【可用工具】你可以调用以下工具获取信息或执行操作：
            - query_question_info：按ID或关键词查询单道题目的详细信息
            - search_questions：按关键词、标签、难度搜索题目列表（如"查找动态规划相关题目"）
            - find_similar_questions：按题目ID查找相似题目（基于向量相似度）
            - judge_user_code：提交代码执行判题
            - query_user_wrong_question：按题目ID查询用户的错题记录
            - list_user_wrong_questions：列出用户的所有错题
            - query_user_submit_history：查询用户的代码提交记录
            - query_user_mastery：分析用户各知识点掌握情况（AC率、错题数），用于个性化辅导
            - get_question_hints：获取题目分层提示（Level 1考点→Level 2方向→Level 3框架），用于引导式教学
            - run_custom_test：用自定义输入测试用户代码并与标准答案对比，用于寻找反例和验证边界
            - diagnose_error_pattern：分析用户错题的系统性错误模式，按错误类型和知识点维度统计
            【工具调用规范】
            - 收到问题后判断是否需要调用工具，需要则直接调用，不需要向用户解释你要调用什么工具；
            - 工具返回结果后直接基于结果回答用户，不要描述工具调用过程；
            - 工具调用失败时，直接告知用户"该功能暂时不可用"，绝对不要根据自身知识编造分析内容或虚构数据来替代工具结果；
            - 绝对不要自己编造题目名称、题目ID或题目链接，所有题目信息必须来自工具返回结果；
            - 如果用户要求推荐题目，必须先调用 search_questions 或 find_similar_questions 获取真实题目数据，严禁根据自身知识虚构题目；
            - 回答中只能包含工具实际返回的链接，不要自行拼接 /view/question/xxx 格式的链接；
            - 讲解知识点时，如果想附带推荐练习题目，必须先调用 search_questions 搜索真实题目，没有调用工具就不要提及任何具体题目名称、ID或链接；
            - 如果搜索不到相关题目，直接说"平台暂无相关练习题"，不要编造。
            - 用户要求按知识点、数据结构或算法类型搜索题目时，优先使用 search_questions 的 keyword 参数传入知识点名称（如"队列"、"动态规划"），系统会同时匹配标题和标签。
            【图片引用规范】
            - 当RAG检索到的知识点包含配图（markdown图片格式 ![...](url)）时，在回答中原样保留这些图片引用，帮助用户直观理解；
            - 不要修改图片URL，不要自行编造图片链接。
            """;

    private static final String DEFAULT_PARSE_SYSTEM_PROMPT = """
            你是XI OJ平台的题目解析助手，请对以下题目进行结构化分析：
            1. 考点分析：涉及哪些算法与数据结构；
            2. 分步骤解题思路，引导用户独立思考；
            3. 常见易错点与边界情况。
            回答格式结构清晰，语言通俗，适配编程初学者。总字数控制在500字以内。
            """;

    private OJChatAgent buildChatAgent() {
        java.lang.reflect.Method[] toolMethods = java.util.Arrays.stream(ojTools.getClass().getMethods())
                .filter(m -> m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                .toArray(java.lang.reflect.Method[]::new);
        log.info("[AiModelHolder] OJTools registered {} @Tool methods: {}",
                toolMethods.length,
                java.util.Arrays.stream(toolMethods)
                        .map(m -> m.getAnnotation(dev.langchain4j.agent.tool.Tool.class).name())
                        .collect(java.util.stream.Collectors.joining(", ")));

        return AiServices.builder(OJChatAgent.class)
                .chatModel(this.chatModel)
                .streamingChatModel(this.streamingChatModel)
                .tools(ojTools)
                .retrievalAugmentor(buildRetrievalAugmentor())
                .systemMessageProvider(memoryId ->
                        aiConfigService.getPrompt("ai.prompt.chat_system", DEFAULT_CHAT_SYSTEM_PROMPT))
                .chatMemoryProvider(memoryId ->
                        dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(50)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                .build();
    }

    private OJQuestionParseAgent buildQuestionParseAgent() {
        return AiServices.builder(OJQuestionParseAgent.class)
                .chatModel(this.chatModel)
                .streamingChatModel(this.streamingChatModel)
                .retrievalAugmentor(buildRetrievalAugmentor())
                .systemMessageProvider(memoryId ->
                        aiConfigService.getPrompt("ai.prompt.question_parse", DEFAULT_PARSE_SYSTEM_PROMPT))
                .build();
    }

    /**
     * 构建检索增强器（双集合：知识点 + 题目）
     * 使用 DefaultQueryRouter 将 query 同时发给两个 ContentRetriever，结果自动合并
     */
    private RetrievalAugmentor buildRetrievalAugmentor() {
        int topK = Integer.parseInt(aiConfigService.getConfigValue("ai.rag.top_k"));
        double minScore = Double.parseDouble(aiConfigService.getConfigValue("ai.rag.similarity_threshold"));

        EmbeddingStoreContentRetriever baseKnowledgeRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(this.embeddingModel)
                .maxResults(topK)
                .minScore(minScore)
                .build();

        ImageAwareContentRetriever knowledgeRetriever =
                new ImageAwareContentRetriever(baseKnowledgeRetriever);

        EmbeddingStoreContentRetriever questionRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(questionEmbeddingStore)
                .embeddingModel(this.embeddingModel)
                .maxResults(topK)
                .minScore(minScore)
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(knowledgeRetriever, questionRetriever))
                .build();
    }
}

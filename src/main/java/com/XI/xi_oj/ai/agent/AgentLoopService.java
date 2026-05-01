package com.XI.xi_oj.ai.agent;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.ai.filter.LinkValidationFilter;
import com.XI.xi_oj.ai.rag.OJKnowledgeRetriever;
import com.XI.xi_oj.ai.rag.QueryRewriter;
import com.XI.xi_oj.ai.rag.RagImageSupport;
import com.XI.xi_oj.ai.rag.RerankService;
import com.XI.xi_oj.service.AiConfigService;
import com.XI.xi_oj.utils.TimeUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentLoopService {

    private static final String DEFAULT_AGENT_SYSTEM_PROMPT = """
            你是 XI OJ 平台的智能编程助教。你可以使用以下工具：

            1. query_question_info(keyword) - 按关键词或 ID 查询题目信息
            2. judge_user_code(userId, questionId, code, language) - 提交代码判题
            3. query_user_wrong_question(userId, questionId) - 查询某道题的错题记录
            4. search_questions(keyword, tag, difficulty) - 按关键词/标签/难度搜索题目列表
            5. find_similar_questions(questionId) - 基于向量检索查找相似题目
            6. list_user_wrong_questions(userId) - 查询用户所有错题列表
            7. query_user_submit_history(userId, questionId) - 查询用户提交历史
            8. query_user_mastery(userId) - 分析用户知识点掌握情况
            9. get_question_hints(questionId, hintLevel) - 获取题目分层提示
            10. run_custom_test(questionId, code, language, customInput) - 运行自定义测试
            11. diagnose_error_pattern(userId) - 诊断用户错题模式
            12. recommend_learning_path(userId) - 诊断薄弱知识点并推荐学习路径（知识点+练习题）

            每次回复必须严格使用以下格式之一：

            【需要调用工具时】
            Thought: <你的思考过程>
            Action: <工具名>
            ActionInput: <JSON 格式参数>

            【可以直接回答时】
            Thought: <你的思考过程>
            Answer: <最终回答>

            【工具选择优先级】
            - 当用户询问"我该练什么""推荐学习路径""我哪里薄弱""帮我分析弱点"时，直接调用 recommend_learning_path，不要拆分成多个工具调用；
            - recommend_learning_path 已内置薄弱诊断+知识点检索+练习推荐的完整流程，无需先调 diagnose_error_pattern 或 query_user_mastery；
            - 仅当用户明确要求查看某道具体错题详情时才用 query_user_wrong_question；
            - 仅当用户明确要求查看提交历史时才用 query_user_submit_history。

            规则：
            - 每次只调用一个工具，等待结果后再决定下一步。
            - 如果工具返回错误，分析原因后可以换工具或换参数。
            - 最多执行 %d 步，达到上限后必须基于已有信息给出最佳回答。
            - 回答语言为中文，不直接给出完整可运行代码。
            - 如果对话中包含知识库检索资料，优先参考这些资料回答，但不要照搬原文。
            - 知识库资料中的图片引用（![...](url)）应原样保留在回答中。
            - 工具返回的结构化数据（如学习路径、题目名称、题目ID、题目链接）应直接呈现给用户，不要丢弃题目名称或重新编造内容。
            - 不要输出裸路径或裸链接，例如 /view/question/1；涉及题目时必须使用工具返回的真实题目名称，并优先保留可点击链接格式：[《题目名称》（ID：1）](/view/question/1)。
            - RAG 知识片段只能作为补充参考；如果知识片段的标签、标题或内容与当前薄弱知识点不匹配，不要把它写成该知识点的核心回顾。
            """;

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private ToolDispatcher toolDispatcher;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private OJKnowledgeRetriever ojKnowledgeRetriever;

    @Resource
    private RerankService rerankService;

    @Resource
    private LinkValidationFilter linkValidationFilter;

    @Resource
    private IntentRouter intentRouter;

    public AgentResult run(String userQuery, Long userId) {
        IntentRouter.Route route = intentRouter.route(userQuery);
        if (route != null) {
            return executeDirectRoute(route, userQuery, userId);
        }

        int maxSteps = maxSteps();
        List<AgentStep> steps = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        log.info("[AgentLoop] start, userId={}, maxSteps={}, query={}", userId, maxSteps, abbreviate(userQuery, 200));

        String systemPrompt = aiConfigService.getPrompt("ai.prompt.agent_system", DEFAULT_AGENT_SYSTEM_PROMPT);
        messages.add(SystemMessage.from(systemPrompt.formatted(maxSteps)));

        String ragContext = retrieveRagContext(userQuery);
        if (!ragContext.isBlank()) {
            messages.add(UserMessage.from("以下是从知识库检索到的相关资料，请参考：\n\n" + ragContext));
        }
        messages.add(UserMessage.from(userQuery));

        ChatModel chatModel = aiModelHolder.getChatModel();
        if (chatModel == null) {
            return AgentResult.of("AI 模型尚未初始化，请先完成模型配置。", steps);
        }

        for (int i = 0; i < maxSteps; i++) {
            long start = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(messages);
            String llmOutput = response.aiMessage().text();
            messages.add(AiMessage.from(llmOutput));
            log.info("[AgentLoop] step={} llmOutput={}", i + 1, abbreviate(llmOutput, 1000));

            String thought = extractBlock(llmOutput, "Thought:");
            String action = extractBlock(llmOutput, "Action:");
            String actionInput = extractBlock(llmOutput, "ActionInput:");
            String answer = extractBlock(llmOutput, "Answer:");

            if (answer != null && !answer.isBlank()) {
                log.info("[AgentLoop] step={} final answer, durationMs={}, answer={}",
                        i + 1, System.currentTimeMillis() - start, abbreviate(answer, 500));
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1)
                        .thought(thought)
                        .toolSuccess(true)
                        .retryCount(0)
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                return AgentResult.of(answer, steps);
            }

            if (action != null && !action.isBlank()) {
                Map<String, Object> params = parseToolParams(actionInput, userId);
                log.info("[AgentLoop] step={} action={}, input={}", i + 1, action.trim(), actionInput);
                ToolDispatcher.ToolResult toolResult = toolDispatcher.execute(action.trim(), params);
                log.info("[AgentLoop] step={} observation success={}, retryCount={}, output={}",
                        i + 1, toolResult.success(), toolResult.retryCount(), abbreviate(toolResult.output(), 1000));
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1)
                        .thought(thought)
                        .toolName(action.trim())
                        .toolInput(actionInput)
                        .toolOutput(toolResult.output())
                        .toolSuccess(toolResult.success())
                        .retryCount(toolResult.retryCount())
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                messages.add(UserMessage.from("Observation: " + toolResult.output()));
            } else {
                log.warn("[AgentLoop] step={} output parse failed: {}", i + 1, abbreviate(llmOutput, 1000));
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1)
                        .thought("格式解析失败：" + llmOutput)
                        .toolSuccess(false)
                        .retryCount(0)
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                messages.add(UserMessage.from("请严格按照 Thought/Action/ActionInput 或 Thought/Answer 格式回复。"));
            }
        }

        messages.add(UserMessage.from("已达到最大推理步数，请基于已有信息用 Answer 格式给出最终回答。"));
        ChatResponse forceResponse = chatModel.chat(messages);
        String forceOutput = forceResponse.aiMessage().text();
        String forceAnswer = extractBlock(forceOutput, "Answer:");
        log.info("[AgentLoop] force answer={}", abbreviate(forceAnswer != null ? forceAnswer : forceOutput, 800));
        return AgentResult.of(forceAnswer != null && !forceAnswer.isBlank() ? forceAnswer : forceOutput, steps);
    }

    public void runStreaming(String userQuery, Long userId, FluxSink<String> sink,
                             Consumer<AgentResult> onComplete) {
        IntentRouter.Route route = intentRouter.route(userQuery);
        if (route != null) {
            executeDirectRouteStreaming(route, userQuery, userId, sink, onComplete);
            return;
        }

        int maxSteps = maxSteps();
        List<AgentStep> steps = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        log.info("[AgentLoop-Stream] start, userId={}, maxSteps={}, query={}", userId, maxSteps, abbreviate(userQuery, 200));

        String systemPrompt = aiConfigService.getPrompt("ai.prompt.agent_system", DEFAULT_AGENT_SYSTEM_PROMPT);
        messages.add(SystemMessage.from(systemPrompt.formatted(maxSteps)));

        sink.next("[STATUS]正在检索知识库...");
        String ragContext = retrieveRagContext(userQuery);
        if (!ragContext.isBlank()) {
            messages.add(UserMessage.from("以下是从知识库检索到的相关资料，请参考：\n\n" + ragContext));
        }
        messages.add(UserMessage.from(userQuery));

        StreamingChatModel streamingModel = aiModelHolder.getStreamingChatModel();
        ChatModel chatModel = aiModelHolder.getChatModel();
        if (streamingModel == null && chatModel == null) {
            sink.next("AI 模型尚未初始化，请先完成模型配置。");
            sink.complete();
            onComplete.accept(AgentResult.of("AI 模型尚未初始化，请先完成模型配置。", steps));
            return;
        }

        StringBuilder fullAnswer = new StringBuilder();

        for (int i = 0; i < maxSteps; i++) {
            long start = System.currentTimeMillis();
            sink.next("[STATUS]正在思考（第 " + (i + 1) + "/" + maxSteps + " 步）...");

            String llmOutput = streamStep(streamingModel, chatModel, messages, sink, fullAnswer);
            messages.add(AiMessage.from(llmOutput));
            log.info("[AgentLoop-Stream] step={} llmOutput={}", i + 1, abbreviate(llmOutput, 1000));

            String thought = extractBlock(llmOutput, "Thought:");
            String action = extractBlock(llmOutput, "Action:");
            String actionInput = extractBlock(llmOutput, "ActionInput:");
            String answer = extractBlock(llmOutput, "Answer:");

            if (answer != null && !answer.isBlank()) {
                log.info("[AgentLoop-Stream] step={} final answer, durationMs={}", i + 1, System.currentTimeMillis() - start);
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1).thought(thought)
                        .toolSuccess(true).retryCount(0)
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                String validated = linkValidationFilter.validate(fullAnswer.toString());
                if (!validated.equals(fullAnswer.toString())) {
                    fullAnswer.setLength(0);
                    fullAnswer.append(validated);
                }
                sink.complete();
                onComplete.accept(AgentResult.of(fullAnswer.toString(), steps));
                return;
            }

            if (action != null && !action.isBlank()) {
                sink.next("[STATUS]正在调用工具: " + action.trim());
                Map<String, Object> params = parseToolParams(actionInput, userId);
                ToolDispatcher.ToolResult toolResult = toolDispatcher.execute(action.trim(), params);
                log.info("[AgentLoop-Stream] step={} action={}, success={}", i + 1, action.trim(), toolResult.success());
                sink.next("[STATUS]工具调用完成，继续分析...");
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1).thought(thought)
                        .toolName(action.trim()).toolInput(actionInput)
                        .toolOutput(toolResult.output()).toolSuccess(toolResult.success())
                        .retryCount(toolResult.retryCount())
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                messages.add(UserMessage.from("Observation: " + toolResult.output()));
                fullAnswer.setLength(0);
            } else {
                log.warn("[AgentLoop-Stream] step={} output parse failed", i + 1);
                steps.add(AgentStep.builder()
                        .stepIndex(i + 1).thought("格式解析失败：" + llmOutput)
                        .toolSuccess(false).retryCount(0)
                        .durationMs(System.currentTimeMillis() - start)
                        .build());
                messages.add(UserMessage.from("请严格按照 Thought/Action/ActionInput 或 Thought/Answer 格式回复。"));
                fullAnswer.setLength(0);
            }
        }

        sink.next("[STATUS]已达到最大步数，正在生成最终回答...");
        messages.add(UserMessage.from("已达到最大推理步数，请基于已有信息用 Answer 格式给出最终回答。"));
        fullAnswer.setLength(0);
        String forceOutput = streamStep(streamingModel, chatModel, messages, sink, fullAnswer);
        String forceAnswer = extractBlock(forceOutput, "Answer:");
        if (forceAnswer != null && !forceAnswer.isBlank()) {
            String validated = linkValidationFilter.validate(fullAnswer.toString());
            fullAnswer.setLength(0);
            fullAnswer.append(validated);
        } else if (fullAnswer.length() == 0) {
            fullAnswer.append(forceOutput);
        }
        sink.complete();
        onComplete.accept(AgentResult.of(fullAnswer.toString(), steps));
    }

    private AgentResult executeDirectRoute(IntentRouter.Route route, String userQuery, Long userId) {
        long start = System.currentTimeMillis();
        String toolName = route.toolName();
        log.info("[AgentLoop] intent pre-route hit, tool={}, query={}", toolName, abbreviate(userQuery, 200));

        Map<String, Object> params = buildDirectRouteParams(route, userQuery, userId, null);
        ToolDispatcher.ToolResult toolResult = toolDispatcher.execute(toolName, params);

        String systemPrompt = aiConfigService.getPrompt("ai.prompt.agent_system", DEFAULT_AGENT_SYSTEM_PROMPT);
        int maxSteps = maxSteps();
        List<ChatMessage> msgs = List.of(
                SystemMessage.from(systemPrompt.formatted(maxSteps)),
                UserMessage.from(userQuery),
                UserMessage.from("工具 " + toolName + " 的返回结果如下，请基于此结果用 Answer 格式直接回答用户，保留所有链接：\n\n" + toolResult.output())
        );

        ChatModel chatModel = aiModelHolder.getChatModel();
        if (chatModel == null) {
            return AgentResult.of(toolResult.output(), List.of());
        }
        ChatResponse response = chatModel.chat(msgs);
        String llmOutput = response.aiMessage().text();
        String answer = extractBlock(llmOutput, "Answer:");
        if (answer == null || answer.isBlank()) {
            answer = llmOutput;
        }
        answer = linkValidationFilter.validate(answer);

        long duration = System.currentTimeMillis() - start;
        AgentStep step = AgentStep.builder()
                .stepIndex(1)
                .thought("意图预路由命中: " + toolName)
                .toolName(toolName)
                .toolInput(JSONUtil.toJsonStr(params))
                .toolOutput(toolResult.output())
                .toolSuccess(toolResult.success())
                .retryCount(toolResult.retryCount())
                .durationMs(duration)
                .build();
        return AgentResult.of(answer, List.of(step));
    }

    private void executeDirectRouteStreaming(IntentRouter.Route route, String userQuery, Long userId,
                                              FluxSink<String> sink, Consumer<AgentResult> onComplete) {
        long start = System.currentTimeMillis();
        String toolName = route.toolName();
        log.info("[AgentLoop-Stream] intent pre-route hit, tool={}, query={}", toolName, abbreviate(userQuery, 200));

        sink.next("[STATUS]正在为你生成个性化推荐...");
        Map<String, Object> params = buildDirectRouteParams(route, userQuery, userId, null);
        ToolDispatcher.ToolResult toolResult = toolDispatcher.execute(toolName, params);

        sink.next("[STATUS]正在整理分析结果...");
        String systemPrompt = aiConfigService.getPrompt("ai.prompt.agent_system", DEFAULT_AGENT_SYSTEM_PROMPT);
        int maxSteps = maxSteps();
        List<ChatMessage> msgs = List.of(
                SystemMessage.from(systemPrompt.formatted(maxSteps)),
                UserMessage.from(userQuery),
                UserMessage.from("工具 " + toolName + " 的返回结果如下，请基于此结果用 Answer 格式直接回答用户，保留所有链接：\n\n" + toolResult.output())
        );

        StreamingChatModel streamingModel = aiModelHolder.getStreamingChatModel();
        ChatModel chatModel = aiModelHolder.getChatModel();
        StringBuilder fullAnswer = new StringBuilder();

        if (streamingModel != null) {
            String llmOutput = streamStep(streamingModel, chatModel, msgs, sink, fullAnswer);
            String answer = extractBlock(llmOutput, "Answer:");
            if (answer == null || answer.isBlank()) {
                if (fullAnswer.length() == 0) {
                    fullAnswer.append(llmOutput);
                }
            }
        } else if (chatModel != null) {
            ChatResponse response = chatModel.chat(msgs);
            String llmOutput = response.aiMessage().text();
            String answer = extractBlock(llmOutput, "Answer:");
            String text = (answer != null && !answer.isBlank()) ? answer : llmOutput;
            emitChunked(text, sink, fullAnswer);
        } else {
            emitChunked(toolResult.output(), sink, fullAnswer);
        }

        String validated = linkValidationFilter.validate(fullAnswer.toString());
        long duration = System.currentTimeMillis() - start;

        AgentStep step = AgentStep.builder()
                .stepIndex(1)
                .thought("意图预路由命中: " + toolName)
                .toolName(toolName)
                .toolInput(JSONUtil.toJsonStr(params))
                .toolOutput(toolResult.output())
                .toolSuccess(toolResult.success())
                .retryCount(toolResult.retryCount())
                .durationMs(duration)
                .build();

        sink.complete();
        onComplete.accept(AgentResult.of(validated, List.of(step)));
    }

    private String streamStep(StreamingChatModel streamingModel, ChatModel chatModel,
                              List<ChatMessage> messages, FluxSink<String> sink,
                              StringBuilder answerBuffer) {
        if (streamingModel == null) {
            ChatResponse response = chatModel.chat(messages);
            String text = response.aiMessage().text();
            String answer = extractBlock(text, "Answer:");
            if (answer != null && !answer.isBlank()) {
                emitChunked(answer, sink, answerBuffer);
            }
            return text;
        }

        StringBuilder fullOutput = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        final boolean[] answerStreaming = {false};
        final StringBuilder pendingBuffer = new StringBuilder();
        final String ANSWER_MARKER = "Answer:";

        ChatRequest request = ChatRequest.builder().messages(messages).build();

        streamingModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null) return;
                fullOutput.append(partial);

                if (answerStreaming[0]) {
                    sink.next(partial);
                    answerBuffer.append(partial);
                    return;
                }

                pendingBuffer.append(partial);
                String pending = pendingBuffer.toString();
                int markerPos = pending.indexOf(ANSWER_MARKER);
                if (markerPos >= 0) {
                    answerStreaming[0] = true;
                    String afterMarker = pending.substring(markerPos + ANSWER_MARKER.length());
                    String trimmed = afterMarker.startsWith(" ") ? afterMarker.substring(1) : afterMarker;
                    if (!trimmed.isEmpty()) {
                        sink.next(trimmed);
                        answerBuffer.append(trimmed);
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            boolean finished = latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[AgentLoop-Stream] streaming step timed out after 120s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AgentLoop-Stream] streaming step interrupted");
        }

        Throwable err = errorRef.get();
        if (err != null) {
            log.error("[AgentLoop-Stream] streaming step error", err);
        }

        return fullOutput.toString();
    }

    private void emitChunked(String text, FluxSink<String> sink, StringBuilder buffer) {
        int chunkSize = 4;
        for (int i = 0; i < text.length(); i += chunkSize) {
            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            sink.next(chunk);
            buffer.append(chunk);
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private int maxSteps() {
        try {
            String value = aiConfigService.getConfigValue("ai.agent.max_steps");
            return value == null || value.isBlank() ? 6 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 6;
        }
    }

    private String extractBlock(String text, String prefix) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = text.length();
        for (String nextPrefix : List.of("Thought:", "Action:", "ActionInput:", "Answer:", "Observation:")) {
            int nextStart = text.indexOf(nextPrefix, start);
            if (nextStart > start && nextStart < end) {
                end = nextStart;
            }
        }
        return text.substring(start, end).trim();
    }

    private Map<String, Object> buildDirectRouteParams(IntentRouter.Route route, String userQuery,
                                                       Long userId, Long fallbackQuestionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        if (route.requiresQuestionId()) {
            Long questionId = fallbackQuestionId != null ? fallbackQuestionId : intentRouter.extractQuestionId(userQuery);
            if (questionId != null) {
                params.put("questionId", questionId);
            }
        }
        return params;
    }

    private Map<String, Object> parseToolParams(String actionInput, Long userId) {
        Map<String, Object> params = new HashMap<>();
        if (actionInput != null && !actionInput.isBlank()) {
            try {
                JSONObject json = JSONUtil.parseObj(actionInput);
                json.forEach(params::put);
            } catch (Exception e) {
                params.put("keyword", actionInput.trim());
            }
        }
        params.put("userId", userId);
        return params;
    }

    private String retrieveRagContext(String query) {
        try {
            String rewritten = queryRewriter.rewrite(query);
            int topK = intConfig("ai.rag.top_k", 5);
            double minScore = doubleConfig("ai.rag.similarity_threshold", 0.5);

            List<Content> contents = ojKnowledgeRetriever.retrieveAsContents(rewritten, topK * 2, minScore);
            if (contents.isEmpty()) {
                return "";
            }

            if (rerankService.enabled() && contents.size() > 1) {
                contents = rerankService.rerank(rewritten, contents, topK);
            } else if (contents.size() > topK) {
                contents = contents.subList(0, topK);
            }

            return contents.stream()
                    .filter(c -> c.textSegment() != null)
                    .map(c -> formatSegmentWithImages(c.textSegment(), rewritten))
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.warn("[AgentLoop] RAG retrieval failed: {}", e.getMessage());
            return "";
        }
    }

    private String formatSegmentWithImages(dev.langchain4j.data.segment.TextSegment segment, String query) {
        return RagImageSupport.appendRelevantImages(segment, query);
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = aiConfigService.getConfigValue(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleConfig(String key, double fallback) {
        try {
            String value = aiConfigService.getConfigValue(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

}

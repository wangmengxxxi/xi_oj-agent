package com.XI.xi_oj.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.XI.xi_oj.ai.agent.AgentLoopService;
import com.XI.xi_oj.ai.agent.AgentResult;
import com.XI.xi_oj.ai.agent.AiModelHolder;
import com.XI.xi_oj.ai.agent.IntentRouter;
import com.XI.xi_oj.ai.agent.ToolDispatcher;
import com.XI.xi_oj.ai.filter.LinkValidationFilter;
import com.XI.xi_oj.ai.model.AiChatHistoryPageRequest;
import com.XI.xi_oj.ai.model.AiChatHistoryPageResponse;
import com.XI.xi_oj.ai.model.AiChatRecord;
import com.XI.xi_oj.ai.observability.AiObservationModule;
import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.ai.store.AiChatRecordMapper;
import com.XI.xi_oj.ai.tools.OJTools;
import com.XI.xi_oj.service.AiChatService;
import com.XI.xi_oj.service.AiConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class AiChatServiceImpl extends ServiceImpl<AiChatRecordMapper, AiChatRecord> implements AiChatService {

    private static final AtomicInteger AGENT_STREAM_THREAD_ID = new AtomicInteger(1);
    private static final ExecutorService AGENT_STREAM_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ai-agent-stream-" + AGENT_STREAM_THREAD_ID.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private AiModelHolder aiModelHolder;

    @Resource
    private AiChatRecordMapper chatRecordMapper;

    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Resource
    private AiChatAsyncService aiChatAsyncService;

    @Resource
    private LinkValidationFilter linkValidationFilter;

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AgentLoopService agentLoopService;

    @Resource
    private IntentRouter intentRouter;

    @Resource
    private ToolDispatcher toolDispatcher;

    @Resource
    private AgentTraceService agentTraceService;

    @Resource
    private AiObservationRecorder aiObservationRecorder;

    @Override
    public String chat(String chatId, Long userId, String message) {
        return chat(chatId, userId, message, null);
    }

    @Override
    public String chat(String chatId, Long userId, String message, Long questionId) {
        long start = System.currentTimeMillis();
        String memoryId = buildMemoryId(userId, chatId);
        String enrichedMessage = buildContextualMessage(questionId, userId, message);
        OJTools.setCurrentUserId(userId);
        try {
            String answer;
            if (isAdvancedAgentMode()) {
                AgentResult result = agentLoopService.run(enrichedMessage, userId);
                answer = result.answer();
                agentTraceService.saveTraceAsync(userId, chatId, message, result.steps());
            } else {
                IntentRouter.Route route = intentRouter.route(enrichedMessage);
                if (route != null) {
                    answer = executeSimpleDirectRoute(route, enrichedMessage, userId, questionId);
                } else {
                    answer = aiModelHolder.getOjChatAgent().chat(memoryId, enrichedMessage);
                }
            }
            answer = linkValidationFilter.validate(answer);
            saveRecord(userId, chatId, message, answer);
            aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                    System.currentTimeMillis() - start, true);
            return answer;
        } catch (Exception e) {
            aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                    System.currentTimeMillis() - start, false);
            throw e;
        } finally {
            OJTools.clearCurrentUserId();
        }
    }

    @Override
    public Flux<String> chatStream(String chatId, Long userId, String message) {
        return chatStream(chatId, userId, message, null);
    }

    @Override
    public Flux<String> chatStream(String chatId, Long userId, String message, Long questionId) {
        long start = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();
        String memoryId = buildMemoryId(userId, chatId);
        String enrichedMessage = buildContextualMessage(questionId, userId, message);
        if (isAdvancedAgentMode()) {
            return Flux.<String>create(sink -> {
                AGENT_STREAM_EXECUTOR.execute(() -> {
                    OJTools.setCurrentUserId(userId);
                    try {
                        log.info("[AI Chat] advanced agent stream, userId={}, chatId={}", userId, chatId);
                        agentLoopService.runStreaming(enrichedMessage, userId, sink, result -> {
                            agentTraceService.saveTraceAsync(userId, chatId, message, result.steps());
                            aiChatAsyncService.saveRecordAsync(userId, chatId, message, result.answer());
                        });
                    } catch (Exception e) {
                        log.error("[AI Chat] advanced stream failed, chatId={}", chatId, e);
                        sink.error(e);
                    } finally {
                        OJTools.clearCurrentUserId();
                    }
                });
            }).doOnComplete(() -> aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                            System.currentTimeMillis() - start, true))
                    .doOnError(e -> {
                        aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                                System.currentTimeMillis() - start, false);
                        log.error("[AI Chat] advanced stream error, chatId={}", chatId, e);
                    });
        }

        log.info("[AI Chat] simple AiServices stream enabled, userId={}, chatId={}", userId, chatId);
        IntentRouter.Route route = intentRouter.route(enrichedMessage);
        if (route != null) {
            return Flux.defer(() -> {
                        OJTools.setCurrentUserId(userId);
                        String answer = executeSimpleDirectRoute(route, enrichedMessage, userId, questionId);
                        String validated = linkValidationFilter.validate(answer);
                        buffer.append(validated);
                        return Flux.fromIterable(chunkText(validated));
                    })
                    .doOnComplete(() -> {
                        aiChatAsyncService.saveRecordAsync(userId, chatId, message, buffer.toString());
                        aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                                System.currentTimeMillis() - start, true);
                    })
                    .doOnError(e -> {
                        aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                                System.currentTimeMillis() - start, false);
                        log.error("[AI Chat] simple direct route stream failed, chatId={}", chatId, e);
                    })
                    .doFinally(signal -> OJTools.clearCurrentUserId());
        }

        OJTools.setCurrentUserId(userId);
        Flux<String> rawStream = aiModelHolder.getOjChatAgent().chatStream(memoryId, enrichedMessage);
        return linkValidationFilter.apply(rawStream)
                .doOnNext(buffer::append)
                .doOnComplete(() -> {
                    aiChatAsyncService.saveRecordAsync(userId, chatId, message, buffer.toString());
                    aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                            System.currentTimeMillis() - start, true);
                })
                .doOnError(e -> {
                    aiObservationRecorder.recordCall(AiObservationModule.CHAT, userId, chatId,
                            System.currentTimeMillis() - start, false);
                    log.error("[AI Chat] stream failed, chatId={}", chatId, e);
                })
                .doFinally(signal -> OJTools.clearCurrentUserId());
    }

    @Override
    public AiChatHistoryPageResponse getChatHistoryByCursor(Long userId, AiChatHistoryPageRequest req) {
        int requestedPageSize = req.getPageSize() == null ? 20 : req.getPageSize();
        int pageSize = Math.min(Math.max(requestedPageSize, 1), 50);

        List<AiChatRecord> rows = chatRecordMapper.selectHistoryByCursor(
                userId,
                req.getChatId(),
                req.getCursorTime(),
                req.getCursorId(),
                pageSize
        );

        LocalDateTime nextCursorTime = null;
        Long nextCursorId = null;
        if (CollUtil.isNotEmpty(rows) && rows.size() == pageSize) {
            AiChatRecord tail = rows.get(rows.size() - 1);
            nextCursorTime = tail.getCreateTime();
            nextCursorId = tail.getId();
        }
        return AiChatHistoryPageResponse.of(rows, nextCursorTime, nextCursorId);
    }

    @Override
    public List<AiChatRecord> getChatHistory(Long userId, String chatId) {
        return chatRecordMapper.selectByUserAndChat(userId, chatId);
    }

    @Override
    public void clearHistory(Long userId, String chatId) {
        chatRecordMapper.deleteByUserAndChat(userId, chatId);
        chatMemoryStore.deleteMessages(buildMemoryId(userId, chatId));
        // backward compatibility: clear legacy key where memoryId == chatId
        chatMemoryStore.deleteMessages(chatId);
        log.info("[AI Chat] history cleared, userId={}, chatId={}", userId, chatId);
    }

    @Override
    public List<Map<String, Object>> listSessions(Long userId) {
        return chatRecordMapper.selectSessionsByUser(userId);
    }

    private String executeSimpleDirectRoute(IntentRouter.Route route, String userQuery, Long userId, Long questionId) {
        Map<String, Object> params = buildDirectRouteParams(route, userQuery, userId, questionId);
        log.info("[AI Chat] simple intent pre-route hit, tool={}, params={}", route.toolName(), params);
        ToolDispatcher.ToolResult result = toolDispatcher.execute(route.toolName(), params);
        return result.output();
    }

    private Map<String, Object> buildDirectRouteParams(IntentRouter.Route route, String userQuery,
                                                       Long userId, Long fallbackQuestionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        if (route.requiresQuestionId()) {
            Long routedQuestionId = fallbackQuestionId != null ? fallbackQuestionId : intentRouter.extractQuestionId(userQuery);
            if (routedQuestionId != null) {
                params.put("questionId", routedQuestionId);
            }
        }
        return params;
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int chunkSize = 4;
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    private String buildContextualMessage(Long questionId, Long userId, String message) {
        if (questionId == null && userId == null) {
            return message;
        }
        StringBuilder prefix = new StringBuilder("【上下文信息】");
        if (userId != null) {
            prefix.append(String.format("当前用户ID：%d。", userId));
        }
        if (questionId != null) {
            prefix.append(String.format("当前题目ID：%d。", questionId));
        }
        prefix.append("请根据需要调用工具获取题目信息、用户提交记录或错题记录。\n");
        return prefix.toString() + message;
    }

    private void saveRecord(Long userId, String chatId, String question, String answer) {
        AiChatRecord record = new AiChatRecord();
        record.setUserId(userId);
        record.setChatId(chatId);
        record.setQuestion(question);
        record.setAnswer(answer);
        chatRecordMapper.insert(record);
    }

    private String buildMemoryId(Long userId, String chatId) {
        if (userId == null) {
            return chatId;
        }
        return userId + ":" + chatId;
    }

    private boolean isAdvancedAgentMode() {
        String mode = aiConfigService.getConfigValue("ai.agent.mode");
        return "advanced".equalsIgnoreCase(mode);
    }
}

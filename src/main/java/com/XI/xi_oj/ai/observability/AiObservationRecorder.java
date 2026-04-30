package com.XI.xi_oj.ai.observability;

import com.XI.xi_oj.mapper.AiObservationEventMapper;
import com.XI.xi_oj.model.entity.AiObservationEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class AiObservationRecorder {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration COUNTER_TTL = Duration.ofDays(3);
    private static final int MAX_DETAIL_LENGTH = 240;
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

    private final ExecutorService eventWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ai-observation-writer-" + THREAD_ID.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private AiObservationEventMapper aiObservationEventMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void recordCall(String module, Long userId, String chatId, long durationMs, boolean success) {
        AiObservationEvent event = baseEvent(AiObservationEventType.AI_CALL, module);
        event.setUserId(userId);
        event.setChatId(chatId);
        event.setSuccess(success ? 1 : 0);
        event.setDurationMs(Math.max(0, durationMs));
        submit(event);
        incrementCounter("metrics:ai:call:" + today());
        incrementCounter("metrics:ai:call:" + today() + ":" + module);
    }

    public void recordRateLimited(String ruleKey, Long userId, String detail) {
        AiObservationEvent event = baseEvent(AiObservationEventType.AI_RATE_LIMITED, "rate_limit");
        event.setUserId(userId);
        event.setTargetKey(abbreviate(ruleKey, 128));
        event.setDetail(abbreviate(detail, MAX_DETAIL_LENGTH));
        submit(event);
        incrementCounter("metrics:ai:rate_limited:" + today());
        incrementCounter("metrics:ai:rate_limited:" + today() + ":" + safeKey(ruleKey));
    }

    public void recordRagEmpty(String module, String query) {
        AiObservationEvent event = baseEvent(AiObservationEventType.RAG_EMPTY, module);
        event.setSuccess(0);
        event.setDetail("query=" + abbreviate(query, 180));
        submit(event);
        incrementCounter("metrics:ai:rag_empty:" + today());
    }

    public void recordRerankCall(String query, int candidateCount) {
        AiObservationEvent event = baseEvent(AiObservationEventType.RERANK_CALL, AiObservationModule.RERANK);
        event.setDetail("candidates=" + candidateCount + ", query=" + abbreviate(query, 160));
        submit(event);
        incrementCounter("metrics:ai:rerank_call:" + today());
    }

    public void recordRerankFailed(String query, String reason) {
        AiObservationEvent event = baseEvent(AiObservationEventType.RERANK_FAILED, AiObservationModule.RERANK);
        event.setSuccess(0);
        event.setDetail("reason=" + abbreviate(reason, 120) + ", query=" + abbreviate(query, 120));
        submit(event);
        incrementCounter("metrics:ai:rerank_failed:" + today());
    }

    public void recordLinkRemoved(Long questionId, String label) {
        AiObservationEvent event = baseEvent(AiObservationEventType.LINK_REMOVED, AiObservationModule.LINK_FILTER);
        event.setTargetKey(questionId == null ? null : String.valueOf(questionId));
        event.setDetail("label=" + abbreviate(label, 180));
        submit(event);
        incrementCounter("metrics:ai:link_removed:" + today());
    }

    private AiObservationEvent baseEvent(String eventType, String module) {
        AiObservationEvent event = new AiObservationEvent();
        event.setEventType(eventType);
        event.setModule(module);
        event.setSuccess(1);
        event.setDurationMs(0L);
        event.setCountValue(1);
        return event;
    }

    private void submit(AiObservationEvent event) {
        try {
            eventWriter.submit(() -> {
                try {
                    aiObservationEventMapper.insert(event);
                } catch (Exception e) {
                    log.warn("[AiMetrics] write event failed, eventType={}", event.getEventType(), e);
                }
            });
        } catch (Exception e) {
            log.warn("[AiMetrics] submit event failed, eventType={}", event.getEventType(), e);
        }
    }

    private void incrementCounter(String key) {
        try {
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, COUNTER_TTL);
        } catch (Exception e) {
            log.warn("[AiMetrics] redis counter failed, key={}", key, e);
        }
    }

    private String today() {
        return LocalDate.now().format(DAY_FORMATTER);
    }

    private String safeKey(String key) {
        return key == null || key.isBlank() ? "unknown" : key.replaceAll("\\s+", "_");
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    @PreDestroy
    public void shutdown() {
        eventWriter.shutdown();
    }
}

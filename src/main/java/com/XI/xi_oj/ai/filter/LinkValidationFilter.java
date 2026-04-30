package com.XI.xi_oj.ai.filter;

import com.XI.xi_oj.ai.observability.AiObservationRecorder;
import com.XI.xi_oj.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class LinkValidationFilter {

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "\\[([^\\]]*)]\\(/view/question/(\\d+)\\)");

    private final QuestionService questionService;

    private final AiObservationRecorder aiObservationRecorder;

    public Flux<String> apply(Flux<String> upstream) {
        Map<Long, Boolean> idCache = new HashMap<>();

        return Flux.create(sink -> {
            StringBuilder pending = new StringBuilder();

            upstream.subscribe(
                    chunk -> {
                        pending.append(chunk);
                        emitSafe(pending, sink, idCache);
                    },
                    error -> {
                        flushPending(pending, sink, idCache);
                        sink.error(error);
                    },
                    () -> {
                        flushPending(pending, sink, idCache);
                        sink.complete();
                    }
            );
        });
    }

    private void emitSafe(StringBuilder pending,
                           reactor.core.publisher.FluxSink<String> sink,
                           Map<Long, Boolean> idCache) {
        String text = pending.toString();
        int safePoint = findSafeEmitPoint(text);
        if (safePoint <= 0) return;

        String toEmit = text.substring(0, safePoint);
        sink.next(validateLinks(toEmit, idCache));
        pending.delete(0, safePoint);
    }

    private void flushPending(StringBuilder pending,
                               reactor.core.publisher.FluxSink<String> sink,
                               Map<Long, Boolean> idCache) {
        if (pending.length() > 0) {
            sink.next(validateLinks(pending.toString(), idCache));
            pending.setLength(0);
        }
    }

    private int findSafeEmitPoint(String text) {
        int openBracket = text.lastIndexOf('[');
        if (openBracket == -1) return text.length();

        int closeParen = text.lastIndexOf(')');
        if (closeParen > openBracket) return closeParen + 1;

        return openBracket;
    }

    public String validate(String text) {
        return validateLinks(text, new HashMap<>());
    }

    String validateLinks(String text, Map<Long, Boolean> idCache) {
        Matcher matcher = LINK_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(text, lastEnd, matcher.start());
            String label = matcher.group(1);
            String idStr = matcher.group(2);

            try {
                Long questionId = Long.parseLong(idStr);
                boolean exists = idCache.computeIfAbsent(questionId,
                        id -> questionService.getById(id) != null);
                if (exists) {
                    result.append(matcher.group());
                } else {
                    log.warn("[LinkFilter] removed fake link: questionId={}, label={}", idStr, label);
                    aiObservationRecorder.recordLinkRemoved(questionId, label);
                    result.append(label);
                }
            } catch (NumberFormatException e) {
                result.append(label);
            }

            lastEnd = matcher.end();
        }

        result.append(text, lastEnd, text.length());
        return result.toString();
    }
}

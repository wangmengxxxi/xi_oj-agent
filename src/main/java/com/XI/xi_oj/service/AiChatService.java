package com.XI.xi_oj.service;

import com.XI.xi_oj.ai.model.AiChatHistoryPageRequest;
import com.XI.xi_oj.ai.model.AiChatHistoryPageResponse;
import com.XI.xi_oj.ai.model.AiChatRecord;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AiChatService {

    String chat(String chatId, Long userId, String message);

    String chat(String chatId, Long userId, String message, Long questionId);

    Flux<String> chatStream(String chatId, Long userId, String message);

    Flux<String> chatStream(String chatId, Long userId, String message, Long questionId);

    AiChatHistoryPageResponse getChatHistoryByCursor(Long userId, AiChatHistoryPageRequest req);

    List<AiChatRecord> getChatHistory(Long userId, String chatId);

    void clearHistory(Long userId, String chatId);
}

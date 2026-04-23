package com.XI.xi_oj.controller;

import com.XI.xi_oj.ai.model.AiChatClearRequest;
import com.XI.xi_oj.ai.model.AiChatHistoryPageRequest;
import com.XI.xi_oj.ai.model.AiChatHistoryPageResponse;
import com.XI.xi_oj.ai.model.AiChatRecord;
import com.XI.xi_oj.ai.model.AiChatRequest;
import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.service.AiChatService;
import com.XI.xi_oj.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_CHAT_USER_DAY;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_IP_MINUTE;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_USER_MINUTE;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiChatService aiChatService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AiChatController(AiChatService aiChatService, UserService userService, ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @RateLimit(types = {AI_USER_MINUTE, AI_IP_MINUTE, AI_CHAT_USER_DAY})
    @PostMapping("/chat")
    public BaseResponse<String> chat(@RequestBody @Valid AiChatRequest request, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        String result = aiChatService.chat(request.getChatId(), loginUser.getId(), request.getMessage(), request.getQuestionId());
        return ResultUtils.success(result);
    }

    @RateLimit(types = {AI_USER_MINUTE, AI_IP_MINUTE, AI_CHAT_USER_DAY})
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody @Valid AiChatRequest request,
                                                    HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return aiChatService.chatStream(request.getChatId(), loginUser.getId(), request.getMessage(), request.getQuestionId())
                .map(token -> ServerSentEvent.<String>builder()
                        .data(toJson(singletonPayload("d", token == null ? "" : token)))
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .data(toJson(singletonPayload("done", true)))
                        .build()))
                .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(toJson(singletonPayload(
                                "error",
                                e == null || e.getMessage() == null ? "stream error" : e.getMessage()
                        )))
                        .build()));
    }

    @GetMapping("/chat/history")
    public BaseResponse<List<AiChatRecord>> getHistory(@RequestParam String chatId, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiChatService.getChatHistory(loginUser.getId(), chatId));
    }

    @PostMapping("/chat/history/page")
    public BaseResponse<AiChatHistoryPageResponse> getHistoryPage(@RequestBody @Valid AiChatHistoryPageRequest request,
                                                                  HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiChatService.getChatHistoryByCursor(loginUser.getId(), request));
    }

    @PostMapping("/chat/clear")
    public BaseResponse<String> clearHistory(@RequestBody @Valid AiChatClearRequest request,
                                             HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        aiChatService.clearHistory(loginUser.getId(), request.getChatId());
        return ResultUtils.success("会话历史已清空");
    }

    private Map<String, Object> singletonPayload(String key, Object value) {
        Map<String, Object> payload = new HashMap<>(1);
        payload.put(key, value);
        return payload;
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}

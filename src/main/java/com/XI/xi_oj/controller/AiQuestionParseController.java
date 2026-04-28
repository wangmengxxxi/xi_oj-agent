package com.XI.xi_oj.controller;

import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.model.dto.question.AiQuestionParseRequest;
import com.XI.xi_oj.model.dto.question.AiQuestionParseResponse;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.service.AiQuestionParseService;
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

import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_GLOBAL_TOKEN_BUCKET;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_IP_MINUTE;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_QUESTION_USER_DAY;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_USER_MINUTE;

@RestController
@RequestMapping("/ai/question")
public class AiQuestionParseController {

    private final AiQuestionParseService aiQuestionParseService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AiQuestionParseController(AiQuestionParseService aiQuestionParseService,
                                     UserService userService,
                                     ObjectMapper objectMapper) {
        this.aiQuestionParseService = aiQuestionParseService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @RateLimit(types = {AI_GLOBAL_TOKEN_BUCKET, AI_USER_MINUTE, AI_IP_MINUTE, AI_QUESTION_USER_DAY},
            message = "AI题目解析调用过于频繁，请稍后再试")
    @PostMapping("/parse")
    public BaseResponse<AiQuestionParseResponse> parseQuestion(@RequestBody @Valid AiQuestionParseRequest request,
                                                               HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiQuestionParseService.parseQuestion(loginUser.getId(), request.getQuestionId()));
    }

    @RateLimit(types = {AI_GLOBAL_TOKEN_BUCKET, AI_USER_MINUTE, AI_IP_MINUTE, AI_QUESTION_USER_DAY},
            message = "AI题目解析调用过于频繁，请稍后再试")
    @PostMapping(value = "/parse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> parseQuestionStream(@RequestBody @Valid AiQuestionParseRequest request,
                                                             HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return aiQuestionParseService.parseQuestionStream(loginUser.getId(), request.getQuestionId())
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
                                e == null || e.getMessage() == null ? "流式输出异常" : e.getMessage()
                        )))
                        .build()));
    }

    @GetMapping("/similar")
    public BaseResponse<List<Long>> listSimilarQuestions(@RequestParam Long questionId,
                                                         HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiQuestionParseService.listSimilarQuestionIds(loginUser.getId(), questionId));
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

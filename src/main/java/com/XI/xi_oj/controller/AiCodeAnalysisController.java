package com.XI.xi_oj.controller;

import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.model.dto.judge.AiCodeAnalysisRequest;
import com.XI.xi_oj.model.entity.AiCodeAnalysis;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.service.AiCodeAnalysisService;
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

import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_CODE_USER_DAY;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_IP_MINUTE;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_USER_MINUTE;

@RestController
@RequestMapping("/ai/code")
public class AiCodeAnalysisController {

    private final AiCodeAnalysisService aiCodeAnalysisService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AiCodeAnalysisController(AiCodeAnalysisService aiCodeAnalysisService,
                                    UserService userService,
                                    ObjectMapper objectMapper) {
        this.aiCodeAnalysisService = aiCodeAnalysisService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @RateLimit(types = {AI_USER_MINUTE, AI_IP_MINUTE, AI_CODE_USER_DAY},
            message = "AI代码分析调用过于频繁，请稍后再试")
    @PostMapping("/analysis")
    public BaseResponse<String> analyzeCode(@RequestBody @Valid AiCodeAnalysisRequest request,
                                            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiCodeAnalysisService.analyzeCode(loginUser.getId(), request));
    }

    @RateLimit(types = {AI_USER_MINUTE, AI_IP_MINUTE, AI_CODE_USER_DAY},
            message = "AI代码分析调用过于频繁，请稍后再试")
    @PostMapping(value = "/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> analyzeCodeStream(@RequestBody @Valid AiCodeAnalysisRequest request,
                                                           HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return aiCodeAnalysisService.analyzeCodeStream(loginUser.getId(), request.getQuestionId(), request.getQuestionSubmitId())
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

    @GetMapping("/history")
    public BaseResponse<List<AiCodeAnalysis>> listHistory(@RequestParam(required = false) Long questionId,
                                                          @RequestParam(required = false, defaultValue = "20") Integer pageSize,
                                                          HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(aiCodeAnalysisService.listMyHistory(loginUser.getId(), questionId, pageSize));
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

package com.XI.xi_oj.controller;

import com.XI.xi_oj.annotation.RateLimit;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.model.dto.question.WrongQuestionReviewRequest;
import com.XI.xi_oj.model.dto.question.WrongQuestionVO;
import com.XI.xi_oj.model.entity.User;
import com.XI.xi_oj.service.AiWrongQuestionService;
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
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_USER_MINUTE;
import static com.XI.xi_oj.model.enums.RateLimitTypeEnum.AI_WRONG_USER_DAY;

@RestController
@RequestMapping("/ai/wrong-question")
public class AiWrongQuestionController {

    private final AiWrongQuestionService aiWrongQuestionService;

    private final UserService userService;

    private final ObjectMapper objectMapper;

    public AiWrongQuestionController(AiWrongQuestionService aiWrongQuestionService,
                                     UserService userService,
                                     ObjectMapper objectMapper) {
        this.aiWrongQuestionService = aiWrongQuestionService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/list")
    public BaseResponse<List<WrongQuestionVO>> listMyWrongQuestions(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(aiWrongQuestionService.listMyWrongQuestions(loginUser.getId()));
    }

    @GetMapping("/due")
    public BaseResponse<List<WrongQuestionVO>> listDueReviewQuestions(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(aiWrongQuestionService.listDueReviewQuestions(loginUser.getId()));
    }

    @RateLimit(types = {AI_GLOBAL_TOKEN_BUCKET, AI_USER_MINUTE, AI_IP_MINUTE, AI_WRONG_USER_DAY},
            message = "AI错题分析调用过于频繁，请稍后再试")
    @GetMapping("/analysis")
    public BaseResponse<String> analyzeWrongQuestion(@RequestParam Long wrongQuestionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(aiWrongQuestionService.analyzeWrongQuestion(loginUser.getId(), wrongQuestionId));
    }

    @RateLimit(types = {AI_GLOBAL_TOKEN_BUCKET, AI_USER_MINUTE, AI_IP_MINUTE, AI_WRONG_USER_DAY},
            message = "AI错题分析调用过于频繁，请稍后再试")
    @PostMapping(value = "/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> analyzeWrongQuestionStream(@RequestBody @Valid WrongQuestionReviewRequest wrongRequest,
                                                                    HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return aiWrongQuestionService.analyzeWrongQuestionStream(loginUser.getId(), wrongRequest.getWrongQuestionId())
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

    @PostMapping("/review")
    public BaseResponse<String> markReviewed(@RequestBody @Valid WrongQuestionReviewRequest request,
                                             HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        aiWrongQuestionService.markReviewed(loginUser.getId(), request.getWrongQuestionId());
        return ResultUtils.success("已标记复习");
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

package com.XI.xi_oj.controller;

import com.XI.xi_oj.ai.observability.AiMetricsSnapshot;
import com.XI.xi_oj.ai.observability.AiModuleCallMetric;
import com.XI.xi_oj.ai.observability.AiObservationEventType;
import com.XI.xi_oj.ai.observability.AiObservationModule;
import com.XI.xi_oj.ai.observability.AiToolMetric;
import com.XI.xi_oj.annotation.AuthCheck;
import com.XI.xi_oj.common.BaseResponse;
import com.XI.xi_oj.common.ResultUtils;
import com.XI.xi_oj.mapper.AgentTraceLogMapper;
import com.XI.xi_oj.mapper.AiObservationEventMapper;
import com.XI.xi_oj.model.entity.AgentTraceLog;
import com.XI.xi_oj.model.entity.AiObservationEvent;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/ai/observability")
public class AiObservabilityController {

    private static final List<String> AI_MODULES = Arrays.asList(
            AiObservationModule.CHAT,
            AiObservationModule.CODE_ANALYSIS,
            AiObservationModule.QUESTION_PARSE,
            AiObservationModule.WRONG_QUESTION
    );

    @Resource
    private AiObservationEventMapper aiObservationEventMapper;

    @Resource
    private AgentTraceLogMapper agentTraceLogMapper;

    @GetMapping("/summary")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<AiMetricsSnapshot> summary(@RequestParam(required = false) String date) {
        LocalDate targetDate = parseDate(date);
        LocalDateTime startTime = targetDate.atStartOfDay();
        LocalDateTime endTime = targetDate.plusDays(1).atStartOfDay();

        AiMetricsSnapshot snapshot = new AiMetricsSnapshot();
        snapshot.setDate(targetDate.toString());
        snapshot.setTodayAiCalls(sumEventCount(AiObservationEventType.AI_CALL, null, startTime, endTime));
        snapshot.setTodayRateLimited(sumEventCount(AiObservationEventType.AI_RATE_LIMITED, null, startTime, endTime));
        snapshot.setAvgDurationMs(avgDuration(startTime, endTime));
        snapshot.setMaxDurationMs(maxDuration(startTime, endTime));
        snapshot.setAvgAgentSteps(defaultDouble(agentTraceLogMapper.selectAverageSteps(startTime, endTime)));
        snapshot.setToolFailedCount(defaultLong(agentTraceLogMapper.selectFailedToolCount(startTime, endTime)));
        snapshot.setRagEmptyCount(sumEventCount(AiObservationEventType.RAG_EMPTY, null, startTime, endTime));
        snapshot.setRerankCallCount(sumEventCount(AiObservationEventType.RERANK_CALL, null, startTime, endTime));
        snapshot.setRerankFailedCount(sumEventCount(AiObservationEventType.RERANK_FAILED, null, startTime, endTime));
        snapshot.setLinkRemovedCount(sumEventCount(AiObservationEventType.LINK_REMOVED, null, startTime, endTime));
        snapshot.setModuleDistribution(moduleDistribution(startTime, endTime));
        return ResultUtils.success(snapshot);
    }

    @GetMapping("/tool-top")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<List<AiToolMetric>> toolTop(@RequestParam(required = false) String date,
                                                    @RequestParam(defaultValue = "10") Integer limit) {
        LocalDate targetDate = parseDate(date);
        int safeLimit = Math.max(1, Math.min(limit == null ? 10 : limit, 50));
        return ResultUtils.success(agentTraceLogMapper.selectToolTop(
                targetDate.atStartOfDay(),
                targetDate.plusDays(1).atStartOfDay(),
                safeLimit
        ));
    }

    @GetMapping("/recent-events")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<List<AiObservationEvent>> recentEvents(@RequestParam(defaultValue = "50") Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        QueryWrapper<AiObservationEvent> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("create_time").orderByDesc("id");
        queryWrapper.last("limit " + safeLimit);
        return ResultUtils.success(aiObservationEventMapper.selectList(queryWrapper));
    }

    @GetMapping("/agent-trace")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<List<AgentTraceLog>> agentTrace(@RequestParam(required = false) String date,
                                                        @RequestParam(required = false) String chatId,
                                                        @RequestParam(defaultValue = "50") Integer limit) {
        LocalDate targetDate = parseDate(date);
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        QueryWrapper<AgentTraceLog> queryWrapper = new QueryWrapper<>();
        queryWrapper.ge("create_time", targetDate.atStartOfDay());
        queryWrapper.lt("create_time", targetDate.plusDays(1).atStartOfDay());
        queryWrapper.eq(chatId != null && !chatId.isBlank(), "chat_id", chatId);
        queryWrapper.orderByDesc("create_time").orderByDesc("id");
        queryWrapper.last("limit " + safeLimit);
        return ResultUtils.success(agentTraceLogMapper.selectList(queryWrapper));
    }

    private List<AiModuleCallMetric> moduleDistribution(LocalDateTime startTime, LocalDateTime endTime) {
        return AI_MODULES.stream()
                .map(module -> new AiModuleCallMetric(
                        module,
                        sumEventCount(AiObservationEventType.AI_CALL, module, startTime, endTime)
                ))
                .toList();
    }

    private Long sumEventCount(String eventType, String module, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<AiObservationEvent> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("IFNULL(SUM(count_value), 0) AS total");
        queryWrapper.eq("event_type", eventType);
        queryWrapper.eq(module != null, "module", module);
        queryWrapper.ge("create_time", startTime);
        queryWrapper.lt("create_time", endTime);
        List<Map<String, Object>> rows = aiObservationEventMapper.selectMaps(queryWrapper);
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        return toLong(rows.get(0).get("total"));
    }

    private Long avgDuration(LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<AiObservationEvent> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("IFNULL(AVG(duration_ms), 0) AS avgDuration");
        queryWrapper.eq("event_type", AiObservationEventType.AI_CALL);
        queryWrapper.ge("create_time", startTime);
        queryWrapper.lt("create_time", endTime);
        return toLong(firstValue(aiObservationEventMapper.selectMaps(queryWrapper), "avgDuration"));
    }

    private Long maxDuration(LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<AiObservationEvent> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("IFNULL(MAX(duration_ms), 0) AS maxDuration");
        queryWrapper.eq("event_type", AiObservationEventType.AI_CALL);
        queryWrapper.ge("create_time", startTime);
        queryWrapper.lt("create_time", endTime);
        return toLong(firstValue(aiObservationEventMapper.selectMaps(queryWrapper), "maxDuration"));
    }

    private Object firstValue(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        return rows.get(0).get(key);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(date);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private Long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private Double defaultDouble(Double value) {
        return value == null ? 0D : value;
    }
}

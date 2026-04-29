package com.XI.xi_oj.service.impl;

import com.XI.xi_oj.ai.agent.AgentStep;
import com.XI.xi_oj.mapper.AgentTraceLogMapper;
import com.XI.xi_oj.model.entity.AgentTraceLog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AgentTraceService {

    @Resource
    private AgentTraceLogMapper agentTraceLogMapper;

    @Async
    public void saveTraceAsync(Long userId, String chatId, String query, List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        try {
            for (AgentStep step : steps) {
                AgentTraceLog logRow = new AgentTraceLog();
                logRow.setUserId(userId);
                logRow.setChatId(chatId);
                logRow.setQuery(query);
                logRow.setStepIndex(step.getStepIndex());
                logRow.setThought(step.getThought());
                logRow.setToolName(step.getToolName());
                logRow.setToolInput(step.getToolInput());
                logRow.setToolOutput(step.getToolOutput());
                logRow.setToolSuccess(Boolean.TRUE.equals(step.getToolSuccess()) ? 1 : 0);
                logRow.setRetryCount(step.getRetryCount() == null ? 0 : step.getRetryCount());
                logRow.setDurationMs(step.getDurationMs() == null ? 0L : step.getDurationMs());
                agentTraceLogMapper.insert(logRow);
            }
        } catch (Exception e) {
            log.error("[AgentTrace] save failed, chatId={}", chatId, e);
        }
    }
}

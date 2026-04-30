package com.XI.xi_oj.ai.observability;

import lombok.Data;

import java.util.List;

@Data
public class AiMetricsSnapshot {

    private String date;

    private Long todayAiCalls;

    private Long todayRateLimited;

    private Long avgDurationMs;

    private Long maxDurationMs;

    private Double avgAgentSteps;

    private Long toolFailedCount;

    private Long ragEmptyCount;

    private Long rerankCallCount;

    private Long rerankFailedCount;

    private Long linkRemovedCount;

    private List<AiModuleCallMetric> moduleDistribution;
}

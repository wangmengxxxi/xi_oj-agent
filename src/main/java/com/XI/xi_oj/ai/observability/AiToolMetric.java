package com.XI.xi_oj.ai.observability;

import lombok.Data;

@Data
public class AiToolMetric {

    private String toolName;

    private Long callCount;

    private Long failedCount;
}

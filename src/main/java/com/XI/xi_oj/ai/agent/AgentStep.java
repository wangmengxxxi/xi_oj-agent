package com.XI.xi_oj.ai.agent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentStep {

    private Integer stepIndex;

    private String thought;

    private String toolName;

    private String toolInput;

    private String toolOutput;

    private Boolean toolSuccess;

    private Integer retryCount;

    private Long durationMs;
}

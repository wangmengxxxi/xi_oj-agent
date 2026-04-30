package com.XI.xi_oj.ai.agent;

import java.util.List;

public record AgentResult(String answer, List<AgentStep> steps) {
    public static AgentResult of(String answer, List<AgentStep> steps) {
        return new AgentResult(answer, steps);
    }
}

package com.XI.xi_oj.ai.observability;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiModuleCallMetric {

    private String module;

    private Long count;
}

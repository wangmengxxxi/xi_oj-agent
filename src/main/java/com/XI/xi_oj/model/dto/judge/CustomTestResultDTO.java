package com.XI.xi_oj.model.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomTestResultDTO {
    private String userOutput;
    private String expectedOutput;
    private boolean match;
    private String errorMsg;
}

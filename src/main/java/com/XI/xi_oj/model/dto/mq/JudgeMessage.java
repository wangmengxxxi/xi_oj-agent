package com.XI.xi_oj.model.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeMessage implements Serializable {

    private Long questionSubmitId;

    private Long questionId;

    private Long userId;

    private String source;
}

package com.XI.xi_oj.judge.codesandbox.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 执行代码请求类
 * 用于封装执行代码所需的请求信息
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ExecuteCodeRequest {
    private List<String> inputList;

    private String code;

    private String language;


    // todo:加入timeLimit,实现超时中断执行

}

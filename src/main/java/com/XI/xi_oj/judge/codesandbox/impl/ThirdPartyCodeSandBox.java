package com.XI.xi_oj.judge.codesandbox.impl;

import com.XI.xi_oj.judge.codesandbox.CodeSandBox;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 第三方代码沙箱实现类
 * 该类实现了CodeSandBox接口，作为第三方代码执行环境的实现
 */
public class ThirdPartyCodeSandBox implements CodeSandBox {
    /**
     * 执行代码的方法实现
     *
     * @param executeCodeRequest 代码执行请求对象，包含需要执行的代码、语言类型、执行参数等信息
     * @return ExecuteCodeResponse 代码执行响应对象，包含执行结果、输出信息、错误信息等
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        return null;
    }
}

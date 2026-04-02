package com.XI.xi_oj.judge.codesandbox.impl;

import com.XI.xi_oj.judge.codesandbox.CodeSandBox;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 远程代码沙箱实现类
 * 该类实现了CodeSandBox接口，提供远程执行代码的功能
 */
public class RemoteCodeSandBox implements CodeSandBox {
    /**
     * 执行代码方法
     * @param executeCodeRequest 代码执行请求，包含需要执行的代码、语言类型、输入参数等信息
     * @return ExecuteCodeResponse 代码执行响应，包含执行结果、输出信息、状态码等
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        // TODO: 实现代码远程执行逻辑
        return null;
    }

}
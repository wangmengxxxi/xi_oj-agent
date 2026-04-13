package com.XI.xi_oj.judge.codesandbox.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.judge.codesandbox.CodeSandBox;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 远程代码沙箱实现类
 * 该类实现了CodeSandBox接口，提供远程执行代码的功能
 */
public class RemoteCodeSandBox implements CodeSandBox {
    private static final String AUTH_REQUEST_HEAD="auth";
    private static final String AUTH_REQUEST_SECRET="secretKey";
    /**
     * 执行代码方法
     * @param executeCodeRequest 代码执行请求，包含需要执行的代码、语言类型、输入参数等信息
     * @return ExecuteCodeResponse 代码执行响应，包含执行结果、输出信息、状态码等
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        // TODO: 实现代码远程执行逻辑
        String url="http://localhost:8090/executeCode";
//        String url="http://192.168.26.132:8090/executeCode";

        String json= JSONUtil.toJsonStr(executeCodeRequest);
        String responseStr = HttpUtil.createPost(url)
                .header(AUTH_REQUEST_HEAD,AUTH_REQUEST_SECRET)
                .body(json)
                .execute()
                .body();
        System.out.println("已经成功调用代码沙箱，响应内容：" + responseStr);
        if(StrUtil.isBlank(responseStr)){
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,"远程执行代码失败,error="+responseStr);
        }
        return JSONUtil.toBean(responseStr, ExecuteCodeResponse.class);
    }

}
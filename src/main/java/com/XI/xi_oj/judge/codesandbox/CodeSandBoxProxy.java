package com.XI.xi_oj.judge.codesandbox;

import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeSandBoxProxy implements CodeSandBox {

    private  final CodeSandBox codeSandBox;

    public CodeSandBoxProxy(CodeSandBox codeSandBox) {
        this.codeSandBox = codeSandBox;
    }
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("代码沙箱请求信息："+executeCodeRequest.toString());
        ExecuteCodeResponse executeCodeResponse=codeSandBox.executeCode(executeCodeRequest);
        log.info("代码沙箱响应信息："+executeCodeResponse.toString());
        return executeCodeResponse;
    }
}

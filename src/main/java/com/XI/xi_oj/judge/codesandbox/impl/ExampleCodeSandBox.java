package com.XI.xi_oj.judge.codesandbox.impl;

import com.XI.xi_oj.judge.codesandbox.CodeSandBox;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;
import com.XI.xi_oj.judge.codesandbox.model.JudgeInfo;
import com.XI.xi_oj.model.enums.JudgeInfoMessageEnum;
import com.XI.xi_oj.model.enums.QuestionSubmitStatusEnum;

import java.util.List;

/**
 * 示例代码沙箱类，实现了CodeSandBox接口
 * 该类提供了一个代码执行的基本框架，具体执行逻辑需要根据实际需求实现
 */
public class ExampleCodeSandBox implements CodeSandBox {
    /**
     * 执行代码方法
     * @param executeCodeRequest 包含需要执行的代码信息，如代码内容、语言类型等
     * @return ExecuteCodeResponse 包含代码执行结果，如执行状态、输出信息、错误信息等
     * @note 这是一个抽象方法，需要由子类或实现类提供具体的代码执行逻辑
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(inputList);
        executeCodeResponse.setMessage("测试执行成功");
        executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.ACCEPTED.getText());
        judgeInfo.setMemory(100L);
        judgeInfo.setTime(100L);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

}

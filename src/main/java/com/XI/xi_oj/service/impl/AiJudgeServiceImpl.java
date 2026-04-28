package com.XI.xi_oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.XI.xi_oj.common.ErrorCode;
import com.XI.xi_oj.exception.BusinessException;
import com.XI.xi_oj.judge.JudgeService;
import com.XI.xi_oj.judge.codesandbox.CodeSandBox;
import com.XI.xi_oj.judge.codesandbox.CodeSandBoxFactory;
import com.XI.xi_oj.judge.codesandbox.CodeSandBoxProxy;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeRequest;
import com.XI.xi_oj.judge.codesandbox.model.ExecuteCodeResponse;
import com.XI.xi_oj.judge.codesandbox.model.JudgeInfo;
import com.XI.xi_oj.model.dto.judge.CustomTestResultDTO;
import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;
import com.XI.xi_oj.model.entity.Question;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import com.XI.xi_oj.model.enums.QuestionSubmitStatusEnum;
import com.XI.xi_oj.service.AiJudgeService;
import com.XI.xi_oj.service.QuestionService;
import com.XI.xi_oj.service.QuestionSubmitService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class AiJudgeServiceImpl implements AiJudgeService {
    @Resource
    private JudgeService judgeService;
    @Resource
    private QuestionService questionService;
    @Resource
    private QuestionSubmitService questionSubmitService;

    @Value("${codesandbox.type:example}")
    private String sandboxType;

    @Override
    public JudgeResultDTO submitCode(Long questionId, String code, String language, Long userId) {
        // 1. 校验题目是否存在
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        // 2. 构建提交记录（绕过异步路径，确保可同步拿到 id）
        // ⚠️ 必须设置 source="ai_tool"，将 AI 工具调用的判题记录与用户正常提交隔离：
        //    - 用户做题统计（solved_num / submit_num）查询时 WHERE source IS NULL 过滤此类记录
        //    - 题目通过数（question.acceptedNum）更新时同样跳过 ai_tool 记录
        //    - 防止 AI 问答多轮对话中多次测试代码污染用户提交历史
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setUserId(userId);
        questionSubmit.setQuestionId(questionId);
        questionSubmit.setCode(code);
        questionSubmit.setLanguage(language);
        questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        questionSubmit.setJudgeInfo("{}");
        questionSubmit.setSource("ai_tool");   // ← 关键：标记为 AI 工具调用，排除在用户统计之外
        boolean saved = questionSubmitService.save(questionSubmit);
        if (!saved) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交记录创建失败");
        }
        // 3. 同步调用 doJudge（AI 工具调用必须拿到结果，不走 CompletableFuture.runAsync）
        QuestionSubmit judged;
        try {
            judged = judgeService.doJudge(questionSubmit.getId());
        } catch (Exception e) {
            log.error("[AiJudge] 判题异常 questionId={}: {}", questionId, e.getMessage());
            return JudgeResultDTO.builder()
                    .status("判题失败")
                    .errorMsg(e.getMessage())
                    .build();
        }
        // 4. 反序列化 JudgeInfo JSON → JudgeResultDTO
        JudgeInfo judgeInfo = JSONUtil.toBean(judged.getJudgeInfo(), JudgeInfo.class);
        String status;
        if (judgeInfo.getMessage() != null && !judgeInfo.getMessage().isBlank()) {
            status = judgeInfo.getMessage();
        } else {
            status = QuestionSubmitStatusEnum.SUCCEED.getValue().equals(judged.getStatus())
                    ? "Accepted" : "Failed";
        }
        return JudgeResultDTO.builder()
                .status(status)
                .timeUsed(judgeInfo.getTime())
                .memoryUsed(judgeInfo.getMemory())
                .errorMsg("Accepted".equalsIgnoreCase(status) ? null : status)
                .build();
    }

    @Override
    public CustomTestResultDTO runCustomTest(Long questionId, String code, String language, String customInput) {
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        String answer = question.getAnswer();
        if (answer == null || answer.isBlank()) {
            return CustomTestResultDTO.builder()
                    .errorMsg("该题目没有标准答案代码，无法对比")
                    .match(false)
                    .build();
        }

        CodeSandBox sandbox = new CodeSandBoxProxy(CodeSandBoxFactory.newInstance(sandboxType));
        List<String> inputList = Collections.singletonList(customInput);

        String userOutput;
        try {
            ExecuteCodeResponse userResp = sandbox.executeCode(
                    ExecuteCodeRequest.builder().code(code).language(language).inputList(inputList).build());
            if (userResp.getOutputList() == null || userResp.getOutputList().isEmpty()) {
                userOutput = "";
            } else {
                userOutput = userResp.getOutputList().get(0);
            }
            if (userResp.getStatus() != null && userResp.getStatus() != 1) {
                return CustomTestResultDTO.builder()
                        .userOutput(userResp.getMessage())
                        .expectedOutput(null)
                        .match(false)
                        .errorMsg("用户代码执行异常: " + userResp.getMessage())
                        .build();
            }
        } catch (Exception e) {
            log.error("[AiJudge] 用户代码自定义测试执行失败: {}", e.getMessage());
            return CustomTestResultDTO.builder()
                    .errorMsg("用户代码执行失败: " + e.getMessage())
                    .match(false)
                    .build();
        }

        String expectedOutput;
        try {
            ExecuteCodeResponse answerResp = sandbox.executeCode(
                    ExecuteCodeRequest.builder().code(answer).language(language).inputList(inputList).build());
            if (answerResp.getOutputList() == null || answerResp.getOutputList().isEmpty()) {
                expectedOutput = "";
            } else {
                expectedOutput = answerResp.getOutputList().get(0);
            }
        } catch (Exception e) {
            log.error("[AiJudge] 标准答案自定义测试执行失败: {}", e.getMessage());
            return CustomTestResultDTO.builder()
                    .userOutput(userOutput)
                    .errorMsg("标准答案执行失败: " + e.getMessage())
                    .match(false)
                    .build();
        }

        boolean match = userOutput.trim().equals(expectedOutput.trim());
        return CustomTestResultDTO.builder()
                .userOutput(userOutput.trim())
                .expectedOutput(expectedOutput.trim())
                .match(match)
                .errorMsg(match ? null : "输出不一致")
                .build();
    }
}

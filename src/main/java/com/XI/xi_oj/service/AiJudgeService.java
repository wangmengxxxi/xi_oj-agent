package com.XI.xi_oj.service;

import com.XI.xi_oj.model.dto.judge.CustomTestResultDTO;
import com.XI.xi_oj.model.dto.judge.JudgeResultDTO;


/**
 * AI 判题服务接口
 * 职责：为 Agent Tool（OJTools）提供同步判题能力，与原 JudgeService 异步链路完全解耦。
 *
 * 单体阶段：实现类直接调用 JudgeService.doJudge()
 * 微服务阶段：只需将实现类中的 JudgeService 替换为 Feign Client，其他代码不动。
 */
public interface AiJudgeService {

    /**
     * AI工具调用入口：同步提交代码并等待判题结果
     * 与异步路径（doQuestionSubmit + doJudge）的区别：
     * 本方法同步阻塞直到判题完成，供 Agent Tool 使用
     *
     * @param questionId 题目ID
     * @param code       用户代码
     * @param language   代码语言（java / python / cpp）
     * @param userId     提交用户ID（由 OJTools 从会话上下文传入）
     * @return 判题结果 DTO
     */
    JudgeResultDTO submitCode(Long questionId, String code, String language, Long userId);

    /**
     * 自定义输入测试：用指定输入分别执行用户代码和标准答案，对比输出
     * 不创建提交记录，不走 doJudge 流程，直接调用沙箱
     *
     * @param questionId  题目ID（用于获取标准答案代码）
     * @param code        用户代码
     * @param language    代码语言
     * @param customInput 自定义测试输入
     * @return 对比结果
     */
    CustomTestResultDTO runCustomTest(Long questionId, String code, String language, String customInput);

}

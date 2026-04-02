package com.XI.xi_oj.judge.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


/**
 * ExecuteCodeResponse 类用于封装代码执行后的响应结果
 * 使用了 Lombok 注解来简化代码，包括：
 * @Builder: 提供构建器模式创建对象
 * @AllArgsConstructor: 生成全参数构造方法
 * @NoArgsConstructor: 生成无参构造方法
 * @Data: 生成getter、setter、toString等方法
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ExecuteCodeResponse {


    /**
     * 代码执行后的输出结果列表
     * 存储代码运行过程中产生的所有输出内容
     */
    private List<String> outputList;  // 代码执行后的输出结果列表

    /**
     * 执行过程中的消息信息
     * 可以包含错误提示、警告信息或其他执行过程中的反馈
     */
    private String message;           // 执行过程中的消息信息，如错误提示等

    /**
     * 执行状态码
     * 用于表示代码执行的当前状态，如成功、失败、运行中等
     */
    private Integer status;           // 执行状态码，表示代码执行的状态

    /**
     * 代码执行结果的评判信息
     * 包含测试用例通过情况、执行时间、内存使用等详细信息
     */
    private JudgeInfo judgeInfo;      // 代码执行结果的评判信息，包含测试用例通过情况等

}

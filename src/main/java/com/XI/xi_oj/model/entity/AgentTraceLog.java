package com.XI.xi_oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_trace_log")
public class AgentTraceLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("chat_id")
    private String chatId;

    private String query;

    @TableField("step_index")
    private Integer stepIndex;

    private String thought;

    @TableField("tool_name")
    private String toolName;

    @TableField("tool_input")
    private String toolInput;

    @TableField("tool_output")
    private String toolOutput;

    @TableField("tool_success")
    private Integer toolSuccess;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("duration_ms")
    private Long durationMs;

    private LocalDateTime createTime;
}

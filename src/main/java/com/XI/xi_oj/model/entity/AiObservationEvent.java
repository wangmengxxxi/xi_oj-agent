package com.XI.xi_oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ai_observation_event")
public class AiObservationEvent implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("event_type")
    private String eventType;

    private String module;

    @TableField("user_id")
    private Long userId;

    @TableField("chat_id")
    private String chatId;

    @TableField("request_id")
    private String requestId;

    @TableField("target_key")
    private String targetKey;

    private Integer success;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("count_value")
    private Integer countValue;

    private String detail;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}

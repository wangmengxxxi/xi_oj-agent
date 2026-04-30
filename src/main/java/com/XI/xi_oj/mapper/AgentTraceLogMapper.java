package com.XI.xi_oj.mapper;

import com.XI.xi_oj.ai.observability.AiToolMetric;
import com.XI.xi_oj.model.entity.AgentTraceLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTraceLogMapper extends BaseMapper<AgentTraceLog> {

    @Select("""
            SELECT IFNULL(AVG(step_count), 0)
            FROM (
                SELECT chat_id, COUNT(*) AS step_count
                FROM ai_agent_trace_log
                WHERE create_time >= #{startTime}
                  AND create_time < #{endTime}
                  AND chat_id IS NOT NULL
                GROUP BY chat_id
            ) t
            """)
    Double selectAverageSteps(@Param("startTime") LocalDateTime startTime,
                              @Param("endTime") LocalDateTime endTime);

    @Select("""
            SELECT tool_name AS toolName,
                   COUNT(*) AS callCount,
                   SUM(CASE WHEN tool_success = 0 THEN 1 ELSE 0 END) AS failedCount
            FROM ai_agent_trace_log
            WHERE create_time >= #{startTime}
              AND create_time < #{endTime}
              AND tool_name IS NOT NULL
              AND tool_name <> ''
            GROUP BY tool_name
            ORDER BY callCount DESC
            LIMIT #{limit}
            """)
    List<AiToolMetric> selectToolTop(@Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime,
                                     @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM ai_agent_trace_log
            WHERE create_time >= #{startTime}
              AND create_time < #{endTime}
              AND tool_name IS NOT NULL
              AND tool_name <> ''
              AND tool_success = 0
            """)
    Long selectFailedToolCount(@Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime);
}

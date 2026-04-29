-- AIGC RAG 深度优化 + 自定义 Agent Loop 支持
-- 在 sql/ai.sql 和 sql/ai_provider_patch.sql 之后执行

INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.rewrite.model_name', 'qwen-turbo', 'Query Rewrite 改写模型名称', 1),
('ai.rewrite.temperature', '0.1', 'Query Rewrite 改写温度', 1),
('ai.rewrite.max_tokens', '256', 'Query Rewrite 改写最大输出 token 数', 1)
ON DUPLICATE KEY UPDATE
config_value = VALUES(config_value),
description = VALUES(description),
is_enable = VALUES(is_enable);

INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.rerank.enabled', 'false', '是否启用 Rerank 重排序', 1),
('ai.rerank.model_name', 'gte-rerank', 'Rerank 重排序模型名称', 1),
('ai.rerank.top_n', '3', 'Rerank 重排序后保留的结果条数', 1),
('ai.rerank.endpoint', 'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank', 'Rerank 重排序 API 端点', 1)
ON DUPLICATE KEY UPDATE
config_value = VALUES(config_value),
description = VALUES(description),
is_enable = VALUES(is_enable);

CREATE TABLE IF NOT EXISTS ai_agent_trace_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    chat_id      VARCHAR(64)  NOT NULL,
    query        TEXT         NOT NULL,
    step_index   INT          NOT NULL,
    thought      TEXT,
    tool_name    VARCHAR(64),
    tool_input   TEXT,
    tool_output  TEXT,
    tool_success TINYINT      DEFAULT 1,
    retry_count  INT          DEFAULT 0,
    duration_ms  BIGINT       DEFAULT 0,
    createTime   DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    INDEX idx_user_chat (user_id, chat_id),
    INDEX idx_create_time (createTime)
) COMMENT 'Agent 推理追踪日志' COLLATE = utf8mb4_unicode_ci;

INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.agent.mode', 'simple', 'Agent 推理模式：simple=标准模式（AiServices），advanced=高级模式（自定义 ReAct 推理链）', 1),
('ai.agent.max_steps', '6', '高级模式下 Agent 单次对话最大推理步数', 1),
('ai.agent.tool_max_retry', '2', '高级模式下单个工具调用最大重试次数', 1)
ON DUPLICATE KEY UPDATE
config_value = VALUES(config_value),
description = VALUES(description),
is_enable = VALUES(is_enable);

INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.prompt.agent_system', '你是 XI OJ 平台的智能编程助教。你可以使用以下工具：\n\n1. query_question_info(keyword) - 按关键词或 ID 查询题目信息\n2. judge_user_code(userId, questionId, code, language) - 提交代码判题\n3. query_user_wrong_question(userId, questionId) - 查询某道题的错题记录\n4. search_questions(keyword, tag, difficulty) - 按关键词/标签/难度搜索题目列表\n5. find_similar_questions(questionId) - 基于向量检索查找相似题目\n6. list_user_wrong_questions(userId) - 查询用户所有错题列表\n7. query_user_submit_history(userId, questionId) - 查询用户提交历史\n8. query_user_mastery(userId) - 分析用户知识点掌握情况\n9. get_question_hints(questionId, hintLevel) - 获取题目分层提示\n10. run_custom_test(questionId, code, language, customInput) - 运行自定义测试\n11. diagnose_error_pattern(userId) - 诊断用户错题模式\n\n每次回复必须严格使用以下格式之一：\n\n【需要调用工具时】\nThought: <你的思考过程>\nAction: <工具名>\nActionInput: <JSON 格式参数>\n\n【可以直接回答时】\nThought: <你的思考过程>\nAnswer: <最终回答>\n\n规则：\n- 每次只调用一个工具，等待结果后再决定下一步。\n- 如果工具返回错误，分析原因后可以换工具或换参数。\n- 最多执行 %d 步，达到上限后必须基于已有信息给出最佳回答。\n- 回答语言为中文，不直接给出完整可运行代码。\n- 如果对话中包含知识库检索资料，优先参考这些资料回答，但不要照搬原文。\n- 知识库资料中的图片引用（![...](url)）应原样保留在回答中。', '高级模式（ReAct）System Prompt，包含工具列表和 Thought/Action/Answer 格式约束，%d 为最大步数占位符', 1)
ON DUPLICATE KEY UPDATE
config_value = VALUES(config_value),
description = VALUES(description),
is_enable = VALUES(is_enable);

-- VL 视觉语言模型配置（用于知识库导入时自动生成图片描述）
INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.vl.model_name', 'qwen-vl-plus', 'VL视觉语言模型名称（用于图片描述生成）', 1),
('ai.vl.concurrency', '4', 'VL模型并发调用线程数（建议2-8）', 1)
ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    description = VALUES(description);

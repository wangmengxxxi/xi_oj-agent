-- AI 多供应商支持 + API Key 加密存储
-- 执行前请确认 ai_config 表已存在

INSERT INTO ai_config (config_key, config_value, description, is_enable) VALUES
('ai.provider', 'dashscope', '当前AI供应商：dashscope/deepseek/openai/zhipu/minimax/siliconflow/moonshot', 1),
('ai.provider.api_key_encrypted', '', '聊天模型API密钥（AES加密存储）', 1),
('ai.embedding.api_key_encrypted', '', '嵌入模型API密钥（AES加密存储，留空则使用聊天模型密钥）', 1);

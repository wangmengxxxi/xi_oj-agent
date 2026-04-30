CREATE TABLE IF NOT EXISTS ai_observation_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL COMMENT 'event type',
    module       VARCHAR(64)  COMMENT 'ai module',
    user_id      BIGINT       COMMENT 'user id',
    chat_id      VARCHAR(64)  COMMENT 'chat id',
    request_id   VARCHAR(64)  COMMENT 'request id',
    target_key   VARCHAR(128) COMMENT 'target object key',
    success      TINYINT      DEFAULT 1 COMMENT 'success flag',
    duration_ms  BIGINT       DEFAULT 0 COMMENT 'duration in milliseconds',
    count_value  INT          DEFAULT 1 COMMENT 'event count value',
    detail       TEXT         COMMENT 'short detail',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    INDEX idx_type_time (event_type, create_time),
    INDEX idx_module_time (module, create_time),
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_chat_id (chat_id)
) COMMENT 'AI observation event table' COLLATE = utf8mb4_unicode_ci;

-- ============================================================
-- 题目提交限流规则配置表
-- 创建时间：2026-04-01
-- ============================================================
CREATE TABLE IF NOT EXISTS rate_limit_rule
(
    id          bigint auto_increment comment 'id' primary key,
    rule_key    varchar(128)  NOT NULL comment '规则唯一键，对应 RateLimitTypeEnum 的 key',
    rule_name   varchar(256)  NOT NULL comment '规则名称，便于管理员理解',
    limit_count int           NOT NULL default 5 comment '时间窗口内最大允许次数（冷却类型时此字段无效）',
    window_seconds int        NOT NULL default 60 comment '时间窗口大小（秒），冷却类型表示冷却时长',
    is_enable   tinyint       NOT NULL default 1 comment '是否启用：1-启用，0-禁用',
    description varchar(512)  comment '规则描述',
    createTime  datetime      NOT NULL default CURRENT_TIMESTAMP comment '创建时间',
    updateTime  datetime      NOT NULL default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP comment '更新时间',
    UNIQUE KEY uk_rule_key (rule_key)
    ) comment '题目提交限流规则配置表' collate = utf8mb4_unicode_ci;
-- 初始化默认限流规则
INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description) VALUES
                                                                                                           ('submit:global:second',   '全局提交-秒级限流',     50,  1,    1, '平台每秒最多处理50次提交，超出保护代码沙箱'),
                                                                                                           ('submit:ip:minute',       'IP提交-分钟级限流',      10,  60,   1, '同一IP每分钟最多提交10次，防代理批量刷题'),
                                                                                                           ('submit:user:minute',     '用户提交-分钟级限流',    5,   60,   1, '同一用户每分钟最多提交5次'),
                                                                                                           ('submit:user:day',        '用户提交-每日限流',       200, 86400,1, '同一用户每天最多提交200次'),
                                                                                                           ('submit:user:question:cooldown', '用户同题提交冷却', 1,   30,   1, '同一用户对同一题目30秒内只能提交1次');

-- AI接口限流规则初始化
INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description) VALUES
    ('ai:ip:minute',         'AI接口IP-分钟级限流',       30,  60,    1, 'AI接口同一IP每分钟最多调用30次，防代理滥用'),
    ('ai:user:minute',       'AI接口用户-分钟级限流',     10,  60,    1, 'AI接口用户每分钟最多调用10次，全功能共享'),
    ('ai:chat:user:day',     'AI问答用户-每日限流',       100, 86400, 1, 'AI问答用户每天最多调用100次'),
    ('ai:code:user:day',     'AI代码分析用户-每日限流',   30,  86400, 1, 'AI代码分析用户每天最多调用30次'),
    ('ai:question:user:day', 'AI题目解析用户-每日限流',   50,  86400, 1, 'AI题目解析用户每天最多调用50次'),
    ('ai:wrong:user:day',    'AI错题分析用户-每日限流',   30,  86400, 1, 'AI错题分析用户每天最多调用30次');

-- AI全局令牌桶限流规则（limit_count=桶容量，window_seconds=每个令牌补充间隔秒数）
INSERT INTO rate_limit_rule (rule_key, rule_name, limit_count, window_seconds, is_enable, description) VALUES
    ('ai:global:token_bucket', 'AI全局令牌桶限流', 20, 3, 1, 'AI全局令牌桶限流（桶容量20，每3秒补充1个令牌，约20次/分钟）');
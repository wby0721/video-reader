-- =====================================================================
-- Video Agent — 阶段六：用户反馈（👍/👎 接受率）
-- =====================================================================

CREATE TABLE IF NOT EXISTS analysis_feedback (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL COMMENT '反馈用户',
    media_id   BIGINT      NOT NULL,
    goal_key   VARCHAR(64) NOT NULL COMMENT '目标级键（goalDigest）',
    rating     TINYINT     NOT NULL COMMENT '1=👍 认可 / -1=👎 不认可',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_feedback (user_id, media_id, goal_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户反馈（接受率）';

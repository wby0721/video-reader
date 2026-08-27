-- =====================================================================
-- Video Agent — 阶段一：基础表结构（方案 §9.1）
-- 说明：MySQL 8 · utf8mb4；Checkpoint 以 MySQL 为恢复真源，Redis 仅热缓存。
-- =====================================================================

-- 用户与角色
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
    nickname    VARCHAR(64)  NULL COMMENT '昵称',
    avatar      VARCHAR(255) NULL COMMENT '头像地址',
    role        VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER' COMMENT 'ROLE_USER / ROLE_ADMIN',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户';

-- 视频元数据（user_id 归属，content_hash 支撑内容级复用）
CREATE TABLE IF NOT EXISTS media_files (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL COMMENT '归属用户（数据隔离）',
    filename      VARCHAR(255) NOT NULL,
    file_path     VARCHAR(512) NULL COMMENT 'MinIO 对象路径',
    status        VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PROCESSING/CONTEXT_READY/FAILED',
    content_hash  CHAR(64)     NULL COMMENT '视频内容指纹（SHA-256），同视频复用预处理',
    ai_summary    TEXT         NULL COMMENT 'AI 摘要（后续阶段）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user (user_id),
    KEY idx_content_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='视频元数据';

-- Agent 状态机 Checkpoint（恢复真源；目标级键 = (media_id, checkpoint_name)）
CREATE TABLE IF NOT EXISTS agent_checkpoint (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id        BIGINT       NOT NULL,
    checkpoint_name VARCHAR(64)  NOT NULL COMMENT '目标级键：goalDigest(goal, mode)',
    stage           VARCHAR(64)  NOT NULL COMMENT 'VIDEO_CONTEXT/PLAN/EXECUTOR/CRITIC/RESULT...',
    payload         JSON         NULL COMMENT '阶段状态快照',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_checkpoint (media_id, checkpoint_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='Agent Checkpoint';

-- 失败任务台账（管理员可查看/重投）
CREATE TABLE IF NOT EXISTS failed_analysis_task (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id      BIGINT       NULL,
    user_id       BIGINT       NULL,
    action        VARCHAR(32)  NULL COMMENT 'START_ANALYSIS / REVISE_ANALYSIS',
    user_goal     VARCHAR(512) NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    error_type    VARCHAR(64)  NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'FAILED' COMMENT 'FAILED / DEAD_LETTERED',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='失败任务台账';

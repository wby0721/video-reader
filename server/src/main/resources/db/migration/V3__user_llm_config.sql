-- =====================================================================
-- Video Agent — 阶段三.5：用户级 LLM 配置（上线后由用户自带 API key）
-- api_key_encrypted：AES-GCM 加密存储（非明文），主密钥来自环境变量 LLM_MASTER_KEY
-- =====================================================================

CREATE TABLE IF NOT EXISTS user_llm_config (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL UNIQUE COMMENT '归属用户',
    base_url          VARCHAR(255) NOT NULL DEFAULT 'https://api.deepseek.com',
    model             VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash',
    api_key_encrypted VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密后的 API Key（密文，非明文）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户级 LLM 配置';

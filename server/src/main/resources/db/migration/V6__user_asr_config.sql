-- =====================================================================
-- Video Agent — 用户级讯飞 ASR 配置（用户自带 XF 凭据）
-- 三个字段均 AES-GCM 加密存储（非明文），主密钥来自环境变量 LLM_MASTER_KEY
-- =====================================================================

CREATE TABLE IF NOT EXISTS user_asr_config (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT       NOT NULL UNIQUE COMMENT '归属用户',
    appid_encrypted    VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密后的讯飞 APPID（密文）',
    apikey_encrypted   VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密后的讯飞 APIKey（密文）',
    apisecret_encrypted VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密后的讯飞 APISecret（密文）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户级讯飞 ASR 配置';

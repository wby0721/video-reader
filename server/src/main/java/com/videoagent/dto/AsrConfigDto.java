package com.videoagent.dto;

/**
 * 用户级讯飞 ASR 配置查询结果（凭据仅脱敏返回）。
 */
public record AsrConfigDto(
        boolean configured,
        String appIdMasked,
        String apiKeyMasked,
        String apiSecretMasked
) {}

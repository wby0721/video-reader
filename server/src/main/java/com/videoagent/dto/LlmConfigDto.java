package com.videoagent.dto;

/**
 * 用户级 LLM 配置视图（Key 仅脱敏返回，绝不回传明文）。
 */
public record LlmConfigDto(
        boolean configured,
        String baseUrl,
        String model,
        String apiKeyMasked
) {}

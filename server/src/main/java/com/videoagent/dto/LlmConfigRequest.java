package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户级 LLM 配置请求（上线后用户自带 API Key）。
 */
public record LlmConfigRequest(
        @NotBlank(message = "API Key 不能为空")
        @Size(max = 256, message = "API Key 过长")
        String apiKey,
        @Size(max = 255, message = "baseUrl 过长")
        String baseUrl,
        @Size(max = 128, message = "模型名过长")
        String model
) {}

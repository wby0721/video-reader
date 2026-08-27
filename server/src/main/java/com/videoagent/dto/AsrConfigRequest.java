package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户级讯飞 ASR 配置请求（用户自带 XF 凭据，服务端 AES-GCM 加密落库）。
 */
public record AsrConfigRequest(
        @NotBlank(message = "讯飞 APPID 不能为空")
        @Size(max = 64, message = "APPID 过长")
        String appId,
        @NotBlank(message = "讯飞 APIKey 不能为空")
        @Size(max = 256, message = "APIKey 过长")
        String apiKey,
        @NotBlank(message = "讯飞 APISecret 不能为空")
        @Size(max = 256, message = "APISecret 过长")
        String apiSecret
) {}

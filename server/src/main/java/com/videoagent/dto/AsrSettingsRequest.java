package com.videoagent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新 ASR 识别设置请求。
 */
public record AsrSettingsRequest(
        @NotBlank(message = "engine 不能为空")
        @Size(max = 16)
        String engine,                 // local / xfyun

        @DecimalMin(value = "0", message = "剩余时长不能为负")
        double remainingHours          // 讯飞在线剩余时长（小时）
) {}

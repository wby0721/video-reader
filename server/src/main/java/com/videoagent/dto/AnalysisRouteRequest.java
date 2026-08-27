package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自动意图路由请求。
 */
public record AnalysisRouteRequest(
        @NotBlank(message = "分析目标不能为空")
        @Size(max = 500, message = "分析目标最长 500 字")
        String goal
) {}

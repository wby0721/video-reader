package com.videoagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 用户反馈请求（👍/👎）。
 */
public record FeedbackRequest(
        @NotNull @Positive Long mediaId,
        String goal,               // 目标（空 = 最近一次分析）
        @NotNull @Min(-1) @Max(1) Integer rating   // 1 = 👍，-1 = 👎
) {}

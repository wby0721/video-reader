package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 术语解释请求：用户在 ASR 转写中选中的片段 + 其在视频中的时间位置。
 */
public record ExplainRequest(
        @NotNull(message = "mediaId 不能为空")
        Long mediaId,
        @NotBlank(message = "选中内容不能为空")
        @Size(max = 100, message = "选中内容过长（最多 100 字）")
        String selectedText,
        @NotNull(message = "缺少选中片段的时间位置")
        Long contextStartMs
) {}

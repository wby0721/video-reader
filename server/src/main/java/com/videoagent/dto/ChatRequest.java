package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 连续追问请求：基于视频上下文 + 历史对话，返回自然语言回答。
 */
public record ChatRequest(
        @NotNull(message = "mediaId 不能为空") Long mediaId,
        @NotBlank(message = "问题不能为空") @Size(max = 500, message = "问题最长 500 字") String query,
        List<Turn> history            // 最近对话轮次（可选，最多取最近 6 条）
) {
    public record Turn(String role, String content) {}
}

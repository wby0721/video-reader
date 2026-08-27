package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提交分析任务请求（异步受理，返回 202）。
 *
 * @param mediaId  视频 mediaId
 * @param userGoal 分析目标
 * @param mode     分析模式（GENERAL/LEARNING/REVIEW/CREATION，阶段四起路由）
 */
public record AnalysisSubmitRequest(
        @NotNull(message = "mediaId 不能为空") @Positive(message = "mediaId 非法") Long mediaId,
        @NotBlank(message = "分析目标不能为空") @Size(max = 500, message = "分析目标最长 500 字") String userGoal,
        String mode
) {}

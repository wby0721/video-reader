package com.videoagent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Kafka 分析任务消息（方案 §7.1）：{ mediaId, action, contentHash, userGoal, mode }。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisTaskMessage(
        Long mediaId,
        String action,
        String contentHash,
        String userGoal,
        String mode
) {
    public static final String ACTION_START_ANALYSIS = "START_ANALYSIS";
    public static final String ACTION_REVISE_ANALYSIS = "REVISE_ANALYSIS";
}

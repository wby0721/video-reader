package com.videoagent.dto;

import java.util.List;
import java.util.Map;

/**
 * 评估与可观测报告（方案 §6.5）：多维指标 + 阶段 trace。
 */
public record EvaluationReport(
        String goal,
        Map<String, Object> metrics,
        List<TelemetryStage> telemetry,
        long totalDurationMs,
        int totalLlmCalls,
        long totalTokensEstimate,
        double costEstimateYuan
) {
    public record TelemetryStage(String stage, long durationMs, int llmCalls, long tokensEstimate) {}
}

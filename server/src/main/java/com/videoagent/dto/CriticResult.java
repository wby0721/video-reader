package com.videoagent.dto;

import java.util.List;

/**
 * Critic 产物（方案 §5.4）：只检查不改写。
 */
public record CriticResult(
        boolean passed,
        List<String> feedback,
        List<String> missingRequirements,   // 未覆盖的目标/任务
        List<String> unsupportedClaims,     // 无证据结论
        List<Long> requiredTimestamps       // 需定向补检索的时间戳
) {
    public static CriticResult ok() {
        return new CriticResult(true, List.of(), List.of(), List.of(), List.of());
    }
}

package com.videoagent.dto;

/**
 * 分析模式（方案 §5.6）：自动意图路由或用户显式指定。
 */
public enum AnalysisMode {
    GENERAL,
    LEARNING,
    REVIEW,
    CREATION;

    public static AnalysisMode parse(String mode) {
        if (mode == null || mode.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}

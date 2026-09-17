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
        List<Long> requiredTimestamps,      // 已知位置：直接读取 Chunk
        RetrievalAction retrievalAction,
        List<String> searchQueries          // 只有 SEARCH 动作才允许触发增量 RAG
) {
    public CriticResult {
        feedback = feedback == null ? List.of() : feedback;
        missingRequirements = missingRequirements == null ? List.of() : missingRequirements;
        unsupportedClaims = unsupportedClaims == null ? List.of() : unsupportedClaims;
        requiredTimestamps = requiredTimestamps == null ? List.of() : requiredTimestamps;
        searchQueries = searchQueries == null ? List.of() : searchQueries;
        if (retrievalAction == null) {
            retrievalAction = !searchQueries.isEmpty() ? RetrievalAction.SEARCH
                    : (!requiredTimestamps.isEmpty() ? RetrievalAction.ADD_TIMESTAMP : RetrievalAction.REUSE);
        }
        if (retrievalAction == RetrievalAction.SEARCH && searchQueries.isEmpty()) {
            retrievalAction = requiredTimestamps.isEmpty()
                    ? RetrievalAction.REUSE : RetrievalAction.ADD_TIMESTAMP;
        }
    }

    public static CriticResult ok() {
        return new CriticResult(true, List.of(), List.of(), List.of(), List.of(),
                RetrievalAction.REUSE, List.of());
    }
}

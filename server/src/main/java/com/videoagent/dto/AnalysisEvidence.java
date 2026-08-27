package com.videoagent.dto;

/**
 * 时间戳证据（方案 §5.3）：source ∈ {ASR, OCR, ASR+OCR}，claim 为绑定的结论原文。
 */
public record AnalysisEvidence(
        long timestampMs,
        String source,
        String content,
        String claim
) {}

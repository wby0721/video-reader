package com.videoagent.dto;

import java.util.List;

/**
 * 证据检索命中：语义召回 TopK 片段（方案 §6.2 混合检索输出）。
 */
public record EvidenceHit(
        long startMs,
        long endMs,
        String summary,
        List<String> keywords,
        double score,
        List<String> matchedTerms,
        String source       // QDRANT / LOCAL_COSINE / KEYWORD_ONLY
) {}

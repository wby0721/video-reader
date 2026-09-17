package com.videoagent.dto;

import java.util.List;

/**
 * 证据检索命中：语义召回 TopK 片段（方案 §6.2 混合检索输出）。
 */
public record EvidenceHit(
        long startMs,
        long endMs,
        String chunkId,
        String summary,
        List<String> keywords,
        double score,
        List<String> matchedTerms,
        String source,      // recall channels; independent of score type
        ScoreType scoreType
) {
    public enum ScoreType { RERANKER_SIGMOID, RRF, UNKNOWN }

    public EvidenceHit {
        // Old checkpoint records have no scoreType. Never guess from the numeric range.
        scoreType = scoreType == null ? ScoreType.UNKNOWN : scoreType;
    }

    public EvidenceHit(long startMs, long endMs, String chunkId, String summary,
                       List<String> keywords, double score, List<String> matchedTerms, String source) {
        this(startMs, endMs, chunkId, summary, keywords, score, matchedTerms, source, ScoreType.UNKNOWN);
    }
}

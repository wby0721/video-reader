package com.videoagent.support;

import com.videoagent.dto.VideoChunk;
import java.util.*;

/** Offline-only answerability experiment. Never injected into production retrieval. */
public final class ExpandedRagEvaluation {
    public record Interval(long startMs, long endMs) {}
    public record Annotation(String questionId, String videoId, String split, boolean related,
                             String category, List<Interval> supportIntervals, String reason) {}
    public record Decision(String evidenceStatus, String promptHint, List<String> acceptedChunkIds) {}

    // Thresholds assume the actual endpoint returns sigmoid scores in [0,1].
    // They are not probability estimates of answer correctness.
    public static final double[] THRESHOLDS = {0, .0001, .001, .005, .01, .02, .05,
            .1, .2, .3, .4, .5, .6, .7, .8, .9, .95, .99, 1.000001};

    public static Decision decide(Map<String, Double> scores, double minScore) {
        if (!Double.isFinite(minScore) || minScore<0) throw new IllegalArgumentException("Invalid threshold");
        if (scores.values().stream().anyMatch(s -> s == null || !Double.isFinite(s) || s<0 || s>1))
            throw new IllegalArgumentException("Expected real normalized reranker scores, not RRF scores");
        var accepted = scores.entrySet().stream().filter(e -> e.getValue()>=minScore)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).toList();
        return accepted.isEmpty()
            ? new Decision("LOW_RELEVANCE", "当前问题未检索到足够相关的证据；请说明视频证据不足，不要据此编造答案。", accepted)
            : new Decision("CANDIDATE_EVIDENCE", "存在候选证据，但相似度不保证可回答；只回答证据明确支持的内容，缺少细节时说明不足。", accepted);
    }

    /** Author-defined source spans projected onto actual raw segments, independent of model ranking.
     * Full/partial support grades: >=60s ->3, >=30s ->2, >0 ->1.
     * Short boundary chunks remain relevant; unrelated questions have an EMPTY gold set. */
    public static Map<String,Integer> gains(Annotation annotation, List<VideoChunk> chunks) {
        Map<String,Integer> out = new LinkedHashMap<>();
        for (var chunk : chunks) {
            long overlap = chunk.rawSegments().stream().mapToLong(segment ->
                annotation.supportIntervals().stream().mapToLong(span -> Math.max(0,
                    Math.min(segment.endMs(),span.endMs())-Math.max(segment.startMs(),span.startMs()))).sum()).sum();
            if (overlap>0) out.put(chunk.chunkId(), overlap>=60_000 ? 3 : overlap>=30_000 ? 2 : 1);
        }
        return out;
    }
}

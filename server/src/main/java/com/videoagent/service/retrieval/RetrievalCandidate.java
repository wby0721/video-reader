package com.videoagent.service.retrieval;

import java.util.Set;

/** RRF 去重后的候选及可观测排名信息。排名从 1 开始，未命中为 null。 */
public record RetrievalCandidate(
        String chunkId,
        double rrfScore,
        Integer denseRank,
        Integer bm25Rank,
        Integer ocrRank,
        Set<RetrievalChannel> sources,
        Double rerankerScore
) {
    public RetrievalCandidate withRerankerScore(double score) {
        return new RetrievalCandidate(chunkId, rrfScore, denseRank, bm25Rank, ocrRank, sources, score);
    }
}

package com.videoagent.dto;

import java.util.List;
import java.util.Map;

/**
 * 一次真实检索调用的只读审计快照。
 *
 * <p>各召回通道的 nativeScore 不在同一量纲；排序融合只使用 rank，
 * rerankerScore 才是 Cross-Encoder sigmoid 分数。</p>
 */
public record RetrievalTrace(
        Query query,
        Parameters parameters,
        List<RecallHit> denseRecall,
        List<RecallHit> bm25Recall,
        List<RecallHit> ocrRecall,
        List<CandidateHit> fusedCandidates,
        List<CandidateHit> rerankedCandidates,
        String denseSource,
        String rerankerStatus,
        long durationMs,
        RetrievalAssessment assessment
) {
    public RetrievalTrace {
        denseRecall = safe(denseRecall);
        bm25Recall = safe(bm25Recall);
        ocrRecall = safe(ocrRecall);
        fusedCandidates = safe(fusedCandidates);
        rerankedCandidates = safe(rerankedCandidates);
    }

    public RetrievalTrace withConversationRewrite(String standaloneQuestion, String semanticQuery,
                                                   List<String> keywords, List<String> ocrKeywords) {
        Query current = query == null ? new Query(null, null, null, null, null, List.of(), List.of()) : query;
        Query updated = new Query(current.originalQuestion(), standaloneQuestion,
                semanticQuery, join(keywords), join(ocrKeywords), safe(keywords), safe(ocrKeywords));
        return copy(updated, parameters, assessment);
    }

    public RetrievalTrace withPolicy(double minRerankerScore, boolean rejectLowRelevance,
                                     RetrievalAssessment retrievalAssessment) {
        Parameters current = parameters == null ? Parameters.defaults() : parameters;
        Parameters updated = new Parameters(current.denseTopK(), current.bm25TopK(), current.ocrTopK(),
                current.fusedTopK(), current.rerankerTopK(), current.rrfK(), current.rrfWeights(),
                minRerankerScore, rejectLowRelevance);
        return copy(query, updated, retrievalAssessment);
    }

    private RetrievalTrace copy(Query updatedQuery, Parameters updatedParameters,
                                RetrievalAssessment updatedAssessment) {
        return new RetrievalTrace(updatedQuery, updatedParameters, denseRecall, bm25Recall, ocrRecall,
                fusedCandidates, rerankedCandidates, denseSource, rerankerStatus, durationMs,
                updatedAssessment);
    }

    private static String join(List<String> values) {
        return String.join(" ", safe(values));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record Query(
            String originalQuestion,
            String standaloneQuestion,
            String semanticQuery,
            String bm25Query,
            String ocrQuery,
            List<String> keywords,
            List<String> ocrKeywords
    ) {
        public Query {
            keywords = safe(keywords);
            ocrKeywords = safe(ocrKeywords);
        }
    }

    public record Parameters(
            int denseTopK,
            int bm25TopK,
            int ocrTopK,
            int fusedTopK,
            int rerankerTopK,
            int rrfK,
            Map<String, Double> rrfWeights,
            Double minRerankerScore,
            Boolean rejectLowRelevance
    ) {
        public Parameters {
            rrfWeights = rrfWeights == null ? Map.of() : Map.copyOf(rrfWeights);
        }

        private static Parameters defaults() {
            return new Parameters(0, 0, 0, 0, 0, 0, Map.of(), null, null);
        }
    }

    /** 通道原始命中；nativeScore 只可在同一通道内比较。 */
    public record RecallHit(
            int rank,
            String chunkId,
            double nativeScore,
            String scoreType,
            long startMs,
            long endMs,
            String summary,
            List<String> keywords
    ) {
        public RecallHit {
            keywords = safe(keywords);
        }
    }

    /** RRF 候选或 Reranker 结果。贡献值严格按 weight/(rrfK+rank) 计算。 */
    public record CandidateHit(
            int rank,
            String chunkId,
            long startMs,
            long endMs,
            String summary,
            List<String> keywords,
            double rrfScore,
            Integer denseRank,
            Integer bm25Rank,
            Integer ocrRank,
            Double denseContribution,
            Double bm25Contribution,
            Double ocrContribution,
            List<String> sources,
            Double rerankerScore
    ) {
        public CandidateHit {
            keywords = safe(keywords);
            sources = safe(sources);
        }
    }
}

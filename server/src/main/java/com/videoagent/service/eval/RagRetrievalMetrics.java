package com.videoagent.service.eval;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RAG 黄金集离线评估指标：Hit@K、Precision@K、Recall@K、MRR 与 nDCG@K。 */
public final class RagRetrievalMetrics {

    private RagRetrievalMetrics() {}

    public static Report evaluate(List<String> rankedChunkIds,
                                  Set<String> relevantChunkIds, int k) {
        Map<String, Integer> binaryGains = new HashMap<>();
        if (relevantChunkIds != null) {
            relevantChunkIds.forEach(id -> binaryGains.put(id, 1));
        }
        return evaluate(rankedChunkIds, binaryGains, k);
    }

    /** 使用 1～3 分级相关性计算 nDCG；Hit、Recall 和 MRR 仍把所有正增益视为相关。 */
    public static Report evaluate(List<String> rankedChunkIds,
                                  Map<String, Integer> relevanceGains, int k) {
        List<String> ranked = rankedChunkIds == null ? List.of() : rankedChunkIds;
        Map<String, Integer> gains = relevanceGains == null ? Map.of() : relevanceGains;
        Set<String> relevant = gains.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        int cutoff = Math.max(0, k);
        int limit = Math.min(cutoff, ranked.size());
        Set<String> uniqueHits = new HashSet<>();
        double dcg = 0;
        double reciprocalRank = 0;

        for (int i = 0; i < limit; i++) {
            String chunkId = ranked.get(i);
            if (relevant.contains(chunkId) && uniqueHits.add(chunkId)) {
                if (reciprocalRank == 0) {
                    reciprocalRank = 1.0 / (i + 1);
                }
                dcg += gradedGain(gains.get(chunkId)) / log2(i + 2);
            }
        }

        double recall = relevant.isEmpty() ? 0 : (double) uniqueHits.size() / relevant.size();
        double precision = cutoff == 0 ? 0 : (double) uniqueHits.size() / cutoff;
        double idealDcg = 0;
        List<Integer> idealGains = relevant.stream()
                .map(gains::get)
                .sorted(java.util.Comparator.reverseOrder())
                .limit(cutoff)
                .toList();
        for (int i = 0; i < idealGains.size(); i++) {
            idealDcg += gradedGain(idealGains.get(i)) / log2(i + 2);
        }
        double ndcg = idealDcg == 0 ? 0 : dcg / idealDcg;
        return new Report(uniqueHits.isEmpty() ? 0 : 1,
                round(precision), round(recall), round(reciprocalRank), round(ndcg),
                uniqueHits.size(), relevant.size(), cutoff);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static double gradedGain(int relevance) {
        return Math.pow(2, relevance) - 1;
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    public record Report(int hitAtK, double precisionAtK, double recallAtK,
                         double mrr, double ndcgAtK,
                         int relevantRetrieved, int relevantTotal, int evaluatedK) {}
}

package com.videoagent.service.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 集中管理三路权重的加权 Reciprocal Rank Fusion。 */
@Component
public class WeightedRrfFusion {

    static final int RRF_K = 60;
    static final double DENSE_WEIGHT = 0.50;
    static final double BM25_WEIGHT = 0.35;
    static final double OCR_WEIGHT = 0.15;

    private static final Map<RetrievalChannel, Double> WEIGHTS = Map.of(
            RetrievalChannel.DENSE, DENSE_WEIGHT,
            RetrievalChannel.BM25, BM25_WEIGHT,
            RetrievalChannel.OCR, OCR_WEIGHT);

    public List<RetrievalCandidate> fuse(Map<RetrievalChannel, List<String>> rankings, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, MutableCandidate> merged = new HashMap<>();
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            List<String> rankedIds = rankings.getOrDefault(channel, List.of());
            int rank = 0;
            for (String chunkId : new LinkedHashSet<>(rankedIds)) {
                if (chunkId == null || chunkId.isBlank()) {
                    continue;
                }
                rank++;
                MutableCandidate candidate = merged.computeIfAbsent(chunkId, MutableCandidate::new);
                candidate.add(channel, rank, WEIGHTS.get(channel) / (RRF_K + rank));
            }
        }

        List<RetrievalCandidate> out = new ArrayList<>();
        for (MutableCandidate candidate : merged.values()) {
            out.add(candidate.freeze());
        }
        out.sort(Comparator.comparingDouble(RetrievalCandidate::rrfScore).reversed()
                .thenComparingInt(WeightedRrfFusion::bestRank)
                .thenComparing(RetrievalCandidate::chunkId));
        return out.stream().limit(limit).toList();
    }

    private static int bestRank(RetrievalCandidate candidate) {
        int best = Integer.MAX_VALUE;
        if (candidate.denseRank() != null) best = Math.min(best, candidate.denseRank());
        if (candidate.bm25Rank() != null) best = Math.min(best, candidate.bm25Rank());
        if (candidate.ocrRank() != null) best = Math.min(best, candidate.ocrRank());
        return best;
    }

    private static final class MutableCandidate {
        private final String chunkId;
        private final EnumMap<RetrievalChannel, Integer> ranks = new EnumMap<>(RetrievalChannel.class);
        private final EnumSet<RetrievalChannel> sources = EnumSet.noneOf(RetrievalChannel.class);
        private double score;

        private MutableCandidate(String chunkId) {
            this.chunkId = chunkId;
        }

        private void add(RetrievalChannel channel, int rank, double contribution) {
            ranks.put(channel, rank);
            sources.add(channel);
            score += contribution;
        }

        private RetrievalCandidate freeze() {
            return new RetrievalCandidate(chunkId, score,
                    ranks.get(RetrievalChannel.DENSE),
                    ranks.get(RetrievalChannel.BM25),
                    ranks.get(RetrievalChannel.OCR),
                    Set.copyOf(sources), null);
        }
    }
}

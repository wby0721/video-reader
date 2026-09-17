package com.videoagent.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedRrfFusionTest {

    private final WeightedRrfFusion fusion = new WeightedRrfFusion();

    @Test
    void fuseDeduplicatesByChunkIdAndPreservesChannelRanks() {
        Map<RetrievalChannel, List<String>> rankings = new EnumMap<>(RetrievalChannel.class);
        rankings.put(RetrievalChannel.DENSE, List.of("a", "b"));
        rankings.put(RetrievalChannel.BM25, List.of("b", "a"));
        rankings.put(RetrievalChannel.OCR, List.of("b"));

        List<RetrievalCandidate> result = fusion.fuse(rankings, 10);

        assertThat(result).extracting(RetrievalCandidate::chunkId).containsExactly("b", "a");
        RetrievalCandidate b = result.getFirst();
        assertThat(b.denseRank()).isEqualTo(2);
        assertThat(b.bm25Rank()).isEqualTo(1);
        assertThat(b.ocrRank()).isEqualTo(1);
        assertThat(b.sources()).containsExactlyInAnyOrder(
                RetrievalChannel.DENSE, RetrievalChannel.BM25, RetrievalChannel.OCR);
        assertThat(b.rrfScore()).isEqualTo(
                0.50 / 62 + 0.35 / 61 + 0.15 / 61);
    }

    @Test
    void duplicateWithinOneChannelDoesNotConsumeAnotherRank() {
        Map<RetrievalChannel, List<String>> rankings = Map.of(
                RetrievalChannel.DENSE, List.of("a", "a", "b"));

        List<RetrievalCandidate> result = fusion.fuse(rankings, 10);

        assertThat(result).extracting(RetrievalCandidate::chunkId).containsExactly("a", "b");
        assertThat(result.get(1).denseRank()).isEqualTo(2);
    }

    @Test
    void fuseEnforcesRequestedTopLimit() {
        List<String> ids = IntStream.range(0, 20).mapToObj(i -> "chunk-" + i).toList();

        assertThat(fusion.fuse(Map.of(RetrievalChannel.DENSE, ids), 10)).hasSize(10);
    }
}

package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25IndexServiceTest {

    @Test
    void contentAndOcrUseIndependentChannels() throws Exception {
        try (Bm25IndexService index = new Bm25IndexService(new ByteBuffersDirectory())) {
            index.index(7L, 11L, "hash-a", 2, List.of(chunk(
                    "chunk-a", "TCP three way handshake", "network protocol", List.of("SYN", "ACK"),
                    List.of("PPT page latency chart"))));

            assertThat(index.searchContent(7L, "hash-a", 2, "handshake", 10))
                    .extracting(Bm25IndexService.Hit::chunkId)
                    .containsExactly("chunk-a");
            assertThat(index.searchContent(7L, "hash-a", 2, "latency", 10)).isEmpty();
            assertThat(index.searchOcr(7L, "hash-a", 2, "latency", 10))
                    .extracting(Bm25IndexService.Hit::chunkId)
                    .containsExactly("chunk-a");
        }
    }

    @Test
    void searchIsScopedByUserContentAndVersion() throws Exception {
        try (Bm25IndexService index = new Bm25IndexService(new ByteBuffersDirectory())) {
            index.index(7L, 11L, "hash-a", 2,
                    List.of(chunk("u7-a", "quasar alpha", "", List.of(), List.of())));
            index.index(7L, 12L, "hash-b", 2,
                    List.of(chunk("u7-b", "quasar beta", "", List.of(), List.of())));
            index.index(8L, 13L, "hash-c", 2,
                    List.of(chunk("u8-c", "quasar gamma", "", List.of(), List.of())));

            assertThat(index.searchContent(7L, null, 2, "quasar", 10))
                    .extracting(Bm25IndexService.Hit::chunkId)
                    .containsExactlyInAnyOrder("u7-a", "u7-b");
            assertThat(index.searchContent(7L, "hash-a", 2, "quasar", 10))
                    .extracting(Bm25IndexService.Hit::chunkId)
                    .containsExactly("u7-a");
            assertThat(index.searchContent(7L, null, 1, "quasar", 10)).isEmpty();
        }
    }

    @Test
    void reindexReplacesStaleChunksAndDeleteRemovesAllVersions() throws Exception {
        try (Bm25IndexService index = new Bm25IndexService(new ByteBuffersDirectory())) {
            index.index(7L, 11L, "hash-a", 2,
                    List.of(chunk("old", "obsolete marker", "", List.of(), List.of())));
            index.index(7L, 11L, "hash-a", 2,
                    List.of(chunk("new", "current marker", "", List.of(), List.of())));

            assertThat(index.searchContent(7L, "hash-a", 2, "obsolete", 10)).isEmpty();
            assertThat(index.searchContent(7L, "hash-a", 2, "current", 10))
                    .extracting(Bm25IndexService.Hit::chunkId)
                    .containsExactly("new");

            index.deleteContent(7L, "hash-a");
            assertThat(index.searchContent(7L, "hash-a", 2, "current", 10)).isEmpty();
        }
    }

    private static VideoChunk chunk(String chunkId, String transcript, String summary,
                                    List<String> keywords, List<String> visualTexts) {
        return VideoChunk.indexed(0, 90_000, transcript, visualTexts, List.of(),
                        chunkId, 0, "ignored-by-index", 2)
                .withEnrichment(summary, keywords);
    }
}

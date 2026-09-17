package com.videoagent.service.agent;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.service.retrieval.RetrievalIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalSessionTest {

    @Test
    void normalizedEquivalentQueriesShareOneCachedSearch() {
        RetrievalSession session = new RetrievalSession(7L, RetrievalIndexService.INDEX_VERSION);
        AtomicInteger searches = new AtomicInteger();
        EvidenceHit hit = new EvidenceHit(0, 90_000, "chunk-1",
                "摘要", List.of(), 1.0, List.of(), "HYBRID");

        List<EvidenceHit> first = session.cachedOrSearch("  Docker   部署  ", () -> {
            searches.incrementAndGet();
            return List.of(hit);
        });
        List<EvidenceHit> second = session.cachedOrSearch("docker 部署", () -> {
            searches.incrementAndGet();
            return List.of();
        });

        assertThat(second).isSameAs(first);
        assertThat(searches).hasValue(1);
        assertThat(session.retrievalQueries()).isEqualTo(1);
        assertThat(session.queryCache()).hasSize(1);
    }

    @Test
    void evidencePoolDeduplicatesByVersionedChunkId() {
        RetrievalSession session = new RetrievalSession(7L, RetrievalIndexService.INDEX_VERSION);
        VideoChunk first = VideoChunk.indexed(0, 90_000, "原文",
                List.of(), List.of(), "chunk-1", 0, "hash", RetrievalIndexService.INDEX_VERSION);
        VideoChunk duplicate = VideoChunk.indexed(0, 90_000, "另一个对象",
                List.of(), List.of(), "chunk-1", 0, "hash", RetrievalIndexService.INDEX_VERSION);

        session.addEvidence(first);
        session.addEvidence(duplicate);

        assertThat(session.evidencePool()).hasSize(1);
        assertThat(session.evidencePool().get("chunk-1").transcript()).isEqualTo("原文");
    }
}

package com.videoagent.service.retrieval;

import com.videoagent.dto.*;
import com.videoagent.service.eval.RagRetrievalMetrics;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBoundsRegressionTest {
    @Test void fullRawSegmentIsCoveredWithoutChangingChunkIdentity() {
        var segment = VideoSegment.of(221000, 319000, "跨窗口原文", List.of("OCR"), List.of());
        var chunk = VideoChunk.indexed(225000, 319000, segment.transcript(), List.of("OCR"),
                List.of(segment), "stable-id", 3, "hash", 2);
        var hit = new EvidenceHit(225000, 319000, "stable-id", "summary", List.of(), .9, List.of(), "QDRANT");
        var pack = new ChatEvidenceService().build(1L, List.of(chunk), List.of(hit));
        assertThat(pack).singleElement().satisfies(e -> {
            assertThat(e.startMs()).isEqualTo(221000);
            assertThat(e.endMs()).isEqualTo(319000);
            assertThat(e.chunkIds()).containsExactly("stable-id");
        });
        assertThat(chunk.startTime()).isEqualTo(225000);
    }
    @Test void shorterRankingDoesNotShrinkMetricDenominators() {
        var m = RagRetrievalMetrics.evaluate(List.of("a"), Set.of("a","b","c","d","e"),5);
        assertThat(m.precisionAtK()).isEqualTo(.2);
        assertThat(m.recallAtK()).isEqualTo(.2);
        assertThat(m.ndcgAtK()).isEqualTo(.3392);
        assertThat(m.evaluatedK()).isEqualTo(5);
    }
}

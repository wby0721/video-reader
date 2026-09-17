package com.videoagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalMetricsTest {

    @Test
    void computesRecallMrrAndNdcgAtCutoff() {
        RagRetrievalMetrics.Report report = RagRetrievalMetrics.evaluate(
                List.of("noise", "relevant-b", "relevant-a", "late"),
                Set.of("relevant-a", "relevant-b", "missing"), 3);

        assertThat(report.recallAtK()).isEqualTo(0.6667);
        assertThat(report.precisionAtK()).isEqualTo(0.6667);
        assertThat(report.hitAtK()).isEqualTo(1);
        assertThat(report.mrr()).isEqualTo(0.5);
        assertThat(report.ndcgAtK()).isEqualTo(0.5307);
        assertThat(report.relevantRetrieved()).isEqualTo(2);
    }

    @Test
    void duplicateChunkDoesNotInflateRecall() {
        RagRetrievalMetrics.Report report = RagRetrievalMetrics.evaluate(
                List.of("a", "a", "noise"), Set.of("a", "b"), 3);

        assertThat(report.recallAtK()).isEqualTo(0.5);
        assertThat(report.precisionAtK()).isEqualTo(0.3333);
        assertThat(report.hitAtK()).isEqualTo(1);
        assertThat(report.relevantRetrieved()).isEqualTo(1);
    }

    @Test
    void emptyGroundTruthProducesZeroSafeMetrics() {
        RagRetrievalMetrics.Report report = RagRetrievalMetrics.evaluate(
                List.of("a"), Set.of(), 5);

        assertThat(report.recallAtK()).isZero();
        assertThat(report.precisionAtK()).isZero();
        assertThat(report.hitAtK()).isZero();
        assertThat(report.mrr()).isZero();
        assertThat(report.ndcgAtK()).isZero();
    }

    @Test
    void gradedNdcgPenalizesBackgroundBeforeDirectEvidence() {
        RagRetrievalMetrics.Report report = RagRetrievalMetrics.evaluate(
                List.of("background", "direct"),
                Map.of("direct", 3, "background", 1), 2);

        assertThat(report.hitAtK()).isEqualTo(1);
        assertThat(report.recallAtK()).isEqualTo(1);
        assertThat(report.precisionAtK()).isEqualTo(1);
        assertThat(report.mrr()).isEqualTo(1);
        assertThat(report.ndcgAtK()).isBetween(0.70, 0.72);
    }
}

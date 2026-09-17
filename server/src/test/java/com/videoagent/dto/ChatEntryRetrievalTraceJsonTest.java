package com.videoagent.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEntryRetrievalTraceJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldChatCheckpointWithoutTraceRemainsReadable() throws Exception {
        String oldJson = """
                {"role":"assistant","content":"旧回答","ts":1,"evidence":[],"retrieval":null,"evidencePack":[]}
                """;

        ChatEntry entry = mapper.readValue(oldJson, ChatEntry.class);

        assertThat(entry.content()).isEqualTo("旧回答");
        assertThat(entry.retrievalTrace()).isNull();
    }

    @Test
    void completeRetrievalTraceRoundTripsWithNativeAndFusionScores() throws Exception {
        RetrievalTrace trace = new RetrievalTrace(
                new RetrievalTrace.Query("原问题", "独立问题", "语义", "关键词", "画面词",
                        List.of("关键词"), List.of("画面词")),
                new RetrievalTrace.Parameters(25, 25, 10, 10, 5, 60,
                        Map.of("DENSE", .5, "BM25", .35, "OCR", .15), .5, false),
                List.of(new RetrievalTrace.RecallHit(1, "chunk-1", .8123, "COSINE_SIMILARITY",
                        1_000, 91_000, "摘要", List.of("词"))),
                List.of(), List.of(),
                List.of(new RetrievalTrace.CandidateHit(1, "chunk-1", 1_000, 91_000,
                        "摘要", List.of("词"), .0082, 1, null, null,
                        .0082, null, null, List.of("DENSE"), null)),
                List.of(new RetrievalTrace.CandidateHit(1, "chunk-1", 1_000, 91_000,
                        "摘要", List.of("词"), .0082, 1, null, null,
                        .0082, null, null, List.of("DENSE"), .93)),
                "QDRANT", "APPLIED", 52,
                new RetrievalAssessment(RetrievalAssessment.Status.CANDIDATE_EVIDENCE, false, "仅依据原文"));
        ChatEntry original = new ChatEntry("assistant", "回答", 2L, List.of(),
                trace.assessment(), List.of(), trace);

        ChatEntry restored = mapper.readValue(mapper.writeValueAsString(original), ChatEntry.class);

        assertThat(restored.retrievalTrace().denseRecall().getFirst().nativeScore()).isEqualTo(.8123);
        assertThat(restored.retrievalTrace().fusedCandidates().getFirst().rrfScore()).isEqualTo(.0082);
        assertThat(restored.retrievalTrace().rerankedCandidates().getFirst().rerankerScore()).isEqualTo(.93);
    }
}

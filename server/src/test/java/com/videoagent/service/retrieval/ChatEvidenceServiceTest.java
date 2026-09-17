package com.videoagent.service.retrieval;

import com.videoagent.dto.ChatEvidence;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEvidenceServiceTest {

    private final ChatEvidenceService service = new ChatEvidenceService();

    @Test
    void overlappingAndAdjacentChunksMergeWithoutDuplicatingRawSegments() {
        VideoSegment first = segment(0, 40_000, "第一段原文");
        VideoSegment overlap = segment(40_000, 80_000, "重叠原文");
        VideoSegment third = segment(80_000, 120_000, "第三段原文");
        VideoChunk a = chunk("a", 0, 90_000, List.of(first, overlap), List.of("PPT A"));
        VideoChunk b = chunk("b", 75_000, 165_000, List.of(overlap, third), List.of("PPT A", "PPT B"));
        VideoChunk far = chunk("c", 300_000, 390_000,
                List.of(segment(300_000, 330_000, "远处原文")), List.of());

        List<ChatEvidence> evidence = service.build(
                11L, List.of(a, b, far), List.of(hit("b"), hit("a"), hit("c")));

        assertThat(evidence).hasSize(2);
        assertThat(evidence.getFirst().chunkIds()).containsExactly("a", "b");
        assertThat(evidence.getFirst().startMs()).isZero();
        // 展示原始证据边界，不再把没有原文的窗口尾部算作证据。
        assertThat(evidence.getFirst().endMs()).isEqualTo(120_000);
        assertThat(evidence.getFirst().quote()).isEqualTo("第一段原文\n重叠原文\n第三段原文");
        assertThat(evidence.getFirst().ocrTexts()).containsExactly("PPT A", "PPT B");
    }

    @Test
    void readsAtMostFiveRerankedChunks() {
        List<VideoChunk> chunks = new ArrayList<>();
        List<EvidenceHit> hits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chunks.add(chunk("c" + i, i * 200_000L, i * 200_000L + 90_000,
                    List.of(segment(i * 200_000L, i * 200_000L + 10_000, "text" + i)), List.of()));
            hits.add(hit("c" + i));
        }

        List<ChatEvidence> evidence = service.build(11L, chunks, hits);

        assertThat(evidence).hasSize(5);
        assertThat(evidence).flatExtracting(ChatEvidence::chunkIds)
                .containsExactly("c0", "c1", "c2", "c3", "c4");
    }

    @Test
    void promptContainsChunkIdsOriginalTextAndOcr() {
        ChatEvidence evidence = new ChatEvidence(
                List.of("a", "b"), 11L, 10_000, 20_000, "逐字原文", List.of("PPT 文字"));

        assertThat(service.toPromptText("网络课程", List.of(evidence)))
                .contains("[证据1]", "chunkIds: a,b", "视频: 网络课程",
                        "时间: 10000ms~20000ms", "转写原文: 逐字原文", "OCR文字: PPT 文字");
    }

    private static VideoChunk chunk(String id, long start, long end,
                                    List<VideoSegment> segments, List<String> ocr) {
        String transcript = String.join(" ", segments.stream().map(VideoSegment::transcript).toList());
        return VideoChunk.indexed(start, end, transcript, ocr, segments,
                        id, 0, "hash", RetrievalIndexService.INDEX_VERSION)
                .withEnrichment("摘要", List.of("关键词"))
                .withEmbedding(List.of(1f));
    }

    private static VideoSegment segment(long start, long end, String text) {
        return VideoSegment.of(start, end, text, List.of(), List.of());
    }

    private static EvidenceHit hit(String chunkId) {
        return new EvidenceHit(0, 1, chunkId, "摘要", List.of(), .9, List.of(), "RERANKER");
    }
}

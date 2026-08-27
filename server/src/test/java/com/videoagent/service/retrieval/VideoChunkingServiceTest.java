package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoChunkingServiceTest {

    private static VideoSegment seg(long start, long end, String text, List<String> ocr) {
        return VideoSegment.of(start, end, text, ocr, List.of());
    }

    @Test
    void chunk_groupSegmentsInto5MinWindows() {
        VideoContext context = VideoContext.of("1", "goal", List.of(
                seg(0, 60_000, "第一段语音", List.of("画面A")),
                seg(120_000, 180_000, "第一段语音续", List.of()),
                seg(310_000, 350_000, "第二窗口语音", List.of("画面B"))
        ));

        List<VideoChunk> chunks = VideoChunkingService.chunk(context);

        assertThat(chunks).hasSize(2);
        VideoChunk first = chunks.get(0);
        assertThat(first.startTime()).isEqualTo(0);
        assertThat(first.endTime()).isEqualTo(180_000);
        assertThat(first.transcript()).contains("第一段语音", "第一段语音续");
        assertThat(first.visualTexts()).contains("画面A");
        assertThat(first.rawSegments()).hasSize(2);

        VideoChunk second = chunks.get(1);
        assertThat(second.startTime()).isEqualTo(300_000);
        assertThat(second.endTime()).isEqualTo(350_000);
        assertThat(second.transcript()).isEqualTo("第二窗口语音");
        assertThat(second.visualTexts()).contains("画面B");
    }

    @Test
    void chunk_emptyContext_returnsEmpty() {
        assertThat(VideoChunkingService.chunk(VideoContext.of("1", "g", List.of()))).isEmpty();
    }

    @Test
    void chunk_mergesDuplicateVisualTexts() {
        VideoContext context = VideoContext.of("1", "g", List.of(
                seg(0, 30_000, "语音", List.of("重复", "唯一")),
                seg(30_000, 60_000, "语音2", List.of("重复"))
        ));
        VideoChunk chunk = VideoChunkingService.chunk(context).get(0);
        assertThat(chunk.visualTexts()).containsExactly("重复", "唯一");
    }

    @Test
    void hitRate_matchesSubsetOfTerms() {
        assertThat(VideoEvidenceRetrievalService.hitRate("二叉树前序遍历根左右",
                List.of("前序遍历", "不存在的词"))).isEqualTo(0.5);
        assertThat(VideoEvidenceRetrievalService.hitRate("", List.of("a"))).isZero();
        assertThat(VideoEvidenceRetrievalService.hitRate("text", List.of())).isZero();
    }

    @Test
    void hitRate_caseInsensitive() {
        assertThat(VideoEvidenceRetrievalService.hitRate("TCP/IP protocol", List.of("tcp/ip"))).isEqualTo(1.0);
    }
}

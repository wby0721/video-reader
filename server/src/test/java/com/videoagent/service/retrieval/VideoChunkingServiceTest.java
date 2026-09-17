package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoChunkingServiceTest {

    private static final long USER_ID = 7L;
    private static final String HASH = "content-hash";
    private static final int VERSION = RetrievalIndexService.INDEX_VERSION;

    private static VideoSegment seg(long start, long end, String text, List<String> ocr) {
        return VideoSegment.of(start, end, text, ocr, List.of());
    }

    private static List<VideoChunk> chunk(VideoContext context) {
        return VideoChunkingService.chunk(context, USER_ID, HASH, VERSION);
    }

    @Test
    void chunk_uses90SecondWindowsWith15SecondOverlap() {
        VideoContext context = VideoContext.of("1", "goal", List.of(
                seg(0, 60_000, "第一段", List.of("画面A")),
                seg(80_000, 100_000, "跨重叠区域", List.of("画面B")),
                seg(150_000, 170_000, "第三段", List.of())
        ));

        List<VideoChunk> chunks = chunk(context);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(VideoChunk::startTime)
                .containsExactly(0L, 75_000L, 150_000L);
        assertThat(chunks.get(0).transcript()).contains("第一段", "跨重叠区域");
        assertThat(chunks.get(1).transcript()).contains("跨重叠区域", "第三段");
        assertThat(chunks.get(0).rawSegments().get(1).endMs()).isEqualTo(100_000L);
    }

    @Test
    void chunk_shortTailAlreadyCoveredByPreviousWindow_isNotDuplicated() {
        VideoContext context = VideoContext.of("1", "goal", List.of(
                seg(0, 80_000, "短视频尾部", List.of())
        ));

        assertThat(chunk(context)).hasSize(1);
    }

    @Test
    void chunk_emptyContext_returnsEmpty() {
        assertThat(chunk(VideoContext.of("1", "g", List.of()))).isEmpty();
    }

    @Test
    void chunk_mergesDuplicateVisualTexts() {
        VideoContext context = VideoContext.of("1", "g", List.of(
                seg(0, 30_000, "语音", List.of("重复", "唯一")),
                seg(30_000, 60_000, "语音2", List.of("重复"))
        ));
        VideoChunk chunk = chunk(context).getFirst();
        assertThat(chunk.visualTexts()).containsExactly("重复", "唯一");
    }

    @Test
    void chunkId_isStableAndScopedByUserAndVersion() {
        String first = VideoChunkingService.chunkId(7L, HASH, 2, 0, 90_000);
        String same = VideoChunkingService.chunkId(7L, HASH, 2, 0, 90_000);
        String anotherUser = VideoChunkingService.chunkId(8L, HASH, 2, 0, 90_000);
        String anotherVersion = VideoChunkingService.chunkId(7L, HASH, 3, 0, 90_000);

        assertThat(first).isEqualTo(same).hasSize(64);
        assertThat(anotherUser).isNotEqualTo(first);
        assertThat(anotherVersion).isNotEqualTo(first);
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

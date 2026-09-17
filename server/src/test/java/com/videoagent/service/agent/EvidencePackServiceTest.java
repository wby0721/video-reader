package com.videoagent.service.agent;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证据包构建回归测试（v6.1 修复）：
 * 1) 定向补检索（Critic requiredTimestamps）必须追加进包，即使任务项已占满上限——此前提前 return 导致
 *    被点名的时间戳（如 600000/900000）永远缺席，轮次空转；
 * 2) 任务项转写取完整块（TRANSCRIPT_CAP=2000），不再只取头部 400 字导致块内细节被截断；
 * 3) 请求了视频中不存在的时间戳 → 静默跳过，不崩溃。
 */
class EvidencePackServiceTest {

    // Keep the original scenarios/hits unchanged; adapt mocks to the richer batch contract.
    @org.junit.jupiter.api.BeforeEach
    void adaptBatchDiagnostics() {
        when(retrieval.searchNoRewriteBatchPrepared(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    Map<String, List<EvidenceHit>> raw = retrieval.searchNoRewriteBatch(
                            inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), inv.getArgument(5));
                    Map<String, VideoEvidenceRetrievalService.PreparedSearch> result = new java.util.LinkedHashMap<>();
                    raw.forEach((q, hits) -> result.put(q, new VideoEvidenceRetrievalService.PreparedSearch(hits, List.of(), "QDRANT")));
                    return result;
                });
    }

    private VideoEvidenceRetrievalService retrieval = mock(VideoEvidenceRetrievalService.class);
    private RetrievalIndexService index = mock(RetrievalIndexService.class);
    private EvidencePackService service = new EvidencePackService(retrieval, index);

    private static final VideoContext CTX = VideoContext.of("1", "g", List.of(
            VideoSegment.of(0, 60_000, "开头", List.of(), List.of()),
            VideoSegment.of(300_000, 360_000, "中间", List.of(), List.of())));

    private static VideoChunk chunk(long start, long end, String transcript) {
        return VideoChunk.of(start, end, "摘要", List.of("关键词"), transcript,
                List.of(), List.of(VideoSegment.of(start, end, transcript, List.of(), List.of())),
                List.of(0.1f));
    }

    private static EvidenceHit hit(long startMs) {
        return new EvidenceHit(startMs, startMs + 60_000, null,
                "摘要", List.of(), 0.9, List.of(), "QDRANT");
    }

    @Test
    void targetedTimestamps_alwaysAdded_evenWhenTaskItemsFillCap() {
        // 5 个任务 × 每任务 2 个不同命中 = 10 个任务项 → 超过 MAX_TASK_CHUNKS=8
        List<VideoChunk> chunks = List.of(
                chunk(0, 300_000, "a"), chunk(300_000, 600_000, "b"),
                chunk(600_000, 900_000, "c"), chunk(900_000, 1_200_000, "d"),
                chunk(1_200_000, 1_500_000, "e"), chunk(1_500_000, 1_800_000, "f"),
                chunk(1_800_000, 2_100_000, "g"), chunk(2_100_000, 2_400_000, "h"),
                chunk(2_400_000, 2_700_000, "i"), chunk(2_700_000, 3_000_000, "j"));
        Map<String, List<EvidenceHit>> perTask = Map.of(
                "任务1", List.of(hit(0), hit(300_000)),
                "任务2", List.of(hit(600_000), hit(900_000)),
                "任务3", List.of(hit(1_200_000), hit(1_500_000)),
                "任务4", List.of(hit(1_800_000), hit(2_100_000)),
                "任务5", List.of(hit(2_400_000), hit(2_700_000)));
        when(retrieval.searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenReturn(perTask);
        List<String> tasks = List.of("任务1", "任务2", "任务3", "任务4", "任务5");

        EvidencePackService.EvidencePack pack =
                service.build(1L, "g", CTX, chunks, tasks, List.of(2_700_000L), 1L);

        // 修复点：定向补检索的时间戳必须进包（旧代码在任务项占满 8 个时提前 return，TARGETED 永远缺失）
        assertThat(pack.items()).anyMatch(i -> "TARGETED".equals(i.source()) && i.startMs() == 2_700_000L);
        assertThat(pack.items()).hasSize(9);
        assertThat(pack.coveredTimestamps()).contains(2_700_000L);
    }

    @Test
    void taskItemContent_keepsWholeChunk_notHeadTruncated() {
        // 块转写 1200 字，细节在中间/后半段——旧 TRANSCRIPT_CAP=400 只留开头，细节被截断
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第").append(i).append("句具体内容详情");
        }
        String transcript = sb.toString(); // 60 × 9 = 540 字
        VideoChunk chunk = chunk(0, 300_000, transcript);
        when(retrieval.searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenReturn(Map.of("任务", List.of(hit(0))));

        EvidencePackService.EvidencePack pack =
                service.build(1L, "g", CTX, List.of(chunk), List.of("任务"), List.of(), 1L);

        assertThat(pack.items()).hasSize(1);
        assertThat(pack.items().get(0).content()).isEqualTo(transcript);
    }

    @Test
    void requiredTimestampOutsideVideo_isSkipped() {
        VideoChunk chunk = chunk(0, 300_000, "内容");
        when(retrieval.searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenReturn(Map.of("任务", List.of(hit(0))));

        EvidencePackService.EvidencePack pack = service.build(
                1L, "g", CTX, List.of(chunk), List.of("任务"), List.of(9_999_999L), 1L);

        assertThat(pack.items()).hasSize(1);
        assertThat(pack.coveredTimestamps()).doesNotContain(9_999_999L);
    }

    @Test
    void overlappingChunks_areResolvedByChunkIdInsteadOfAmbiguousTimestamp() {
        VideoChunk first = VideoChunk.indexed(0, 100_000, "错误的重叠块",
                List.of(), List.of(), "chunk-1", 0, "hash", RetrievalIndexService.INDEX_VERSION);
        VideoChunk second = VideoChunk.indexed(75_000, 170_000, "正确命中的块",
                List.of(), List.of(), "chunk-2", 1, "hash", RetrievalIndexService.INDEX_VERSION);
        EvidenceHit secondHit = new EvidenceHit(75_000, 170_000, "chunk-2",
                "摘要", List.of(), 0.9, List.of(), "QDRANT");
        when(retrieval.searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenReturn(Map.of("任务", List.of(secondHit)));

        EvidencePackService.EvidencePack pack = service.build(
                1L, "hash", CTX, List.of(first, second), List.of("任务"), List.of(), 1L);

        assertThat(pack.items()).singleElement()
                .satisfies(item -> assertThat(item.content()).isEqualTo("正确命中的块"));
    }

    @Test
    void sharedSession_onlyRunsRagForNewQueries_whileTimestampReadsChunkDirectly() {
        VideoChunk first = VideoChunk.indexed(0, 90_000, "首轮证据",
                List.of(), List.of(), "chunk-1", 0, "hash", RetrievalIndexService.INDEX_VERSION);
        VideoChunk second = VideoChunk.indexed(75_000, 165_000, "时间戳补充证据",
                List.of(), List.of(), "chunk-2", 1, "hash", RetrievalIndexService.INDEX_VERSION);
        VideoChunk third = VideoChunk.indexed(150_000, 240_000, "新增查询证据",
                List.of(), List.of(), "chunk-3", 2, "hash", RetrievalIndexService.INDEX_VERSION);
        EvidenceHit firstHit = new EvidenceHit(0, 90_000, "chunk-1",
                "首轮摘要", List.of(), 0.9, List.of(), "HYBRID");
        EvidenceHit thirdHit = new EvidenceHit(150_000, 240_000, "chunk-3",
                "新增摘要", List.of(), 0.8, List.of(), "HYBRID");
        when(retrieval.searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    List<String> queries = invocation.getArgument(3);
                    Map<String, List<EvidenceHit>> found = new java.util.LinkedHashMap<>();
                    for (String query : queries) {
                        found.put(query, switch (query) {
                            case "初始任务" -> List.of(firstHit);
                            case "新增问题" -> List.of(thirdHit);
                            default -> List.of();
                        });
                    }
                    return found;
                });

        RetrievalSession session = new RetrievalSession(1L, RetrievalIndexService.INDEX_VERSION);
        List<VideoChunk> chunks = List.of(first, second, third);

        service.build(session, "hash", CTX, chunks,
                List.of("初始任务"), List.of(), 1L);
        EvidencePackService.EvidencePack timestampRound = service.build(session, "hash", CTX, chunks,
                List.of("初始任务"), List.of(100_000L), 1L);
        EvidencePackService.EvidencePack searchRound = service.build(session, "hash", CTX, chunks,
                List.of("初始任务", "新增问题"), List.of(100_000L), 1L);

        verify(retrieval, times(2))
                .searchNoRewriteBatch(anyLong(), any(), any(), anyList(), anyInt(), anyLong());
        assertThat(session.retrievalQueries()).isEqualTo(2);
        assertThat(timestampRound.items()).anyMatch(item ->
                "TARGETED".equals(item.source()) && item.content().equals("时间戳补充证据"));
        assertThat(searchRound.items()).extracting(EvidencePackService.EvidenceItem::content)
                .contains("首轮证据", "时间戳补充证据", "新增查询证据");
        assertThat(session.evidencePool()).containsKeys("chunk-1", "chunk-2", "chunk-3");
    }
}

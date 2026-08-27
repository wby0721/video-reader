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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 证据包构建回归测试（v6.1 修复）：
 * 1) 定向补检索（Critic requiredTimestamps）必须追加进包，即使任务项已占满上限——此前提前 return 导致
 *    被点名的时间戳（如 600000/900000）永远缺席，轮次空转；
 * 2) 任务项转写取完整块（TRANSCRIPT_CAP=2000），不再只取头部 400 字导致块内细节被截断；
 * 3) 请求了视频中不存在的时间戳 → 静默跳过，不崩溃。
 */
class EvidencePackServiceTest {

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
        return new EvidenceHit(startMs, startMs + 60_000, "摘要", List.of(), 0.9, List.of(), "QDRANT");
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
        when(retrieval.searchNoRewrite(anyLong(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenAnswer(inv -> perTask.getOrDefault(inv.getArgument(3), List.of()));
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
        when(retrieval.searchNoRewrite(anyLong(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(List.of(hit(0)));

        EvidencePackService.EvidencePack pack =
                service.build(1L, "g", CTX, List.of(chunk), List.of("任务"), List.of(), 1L);

        assertThat(pack.items()).hasSize(1);
        assertThat(pack.items().get(0).content()).isEqualTo(transcript);
    }

    @Test
    void requiredTimestampOutsideVideo_isSkipped() {
        VideoChunk chunk = chunk(0, 300_000, "内容");
        when(retrieval.searchNoRewrite(anyLong(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(List.of(hit(0)));

        EvidencePackService.EvidencePack pack = service.build(
                1L, "g", CTX, List.of(chunk), List.of("任务"), List.of(9_999_999L), 1L);

        assertThat(pack.items()).hasSize(1);
        assertThat(pack.coveredTimestamps()).doesNotContain(9_999_999L);
    }
}

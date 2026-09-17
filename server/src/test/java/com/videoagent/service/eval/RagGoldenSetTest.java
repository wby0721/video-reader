package com.videoagent.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.videoagent.service.retrieval.VideoChunkingService;
import com.videoagent.support.RagFixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定 20/40 分钟过程样本与人工 Top7 Ground Truth 的一致性守卫。 */
class RagGoldenSetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyVideoHasThreeQuestionsAndSevenValidStableChunkIds() throws Exception {
        RagFixtureLoader.GoldenSet goldenSet = RagFixtureLoader.loadGoldenSet(mapper);

        assertThat(goldenSet.schemaVersion()).isEqualTo(1);
        assertThat(goldenSet.indexVersion()).isEqualTo(RetrievalIndexService.INDEX_VERSION);
        assertThat(goldenSet.videos()).extracting(RagFixtureLoader.GoldenVideo::fixture)
                .containsExactly("20min", "40min");

        for (RagFixtureLoader.GoldenVideo video : goldenSet.videos()) {
            RagFixtureLoader.Fixture fixture = switch (video.fixture()) {
                case "20min" -> RagFixtureLoader.load20Minutes(mapper);
                case "40min" -> RagFixtureLoader.load40Minutes(mapper);
                default -> throw new AssertionError("未知 fixture: " + video.fixture());
            };
            VideoContext context = fixture.align(String.valueOf(video.mediaId()), "RAG Golden Set");
            List<VideoChunk> chunks = VideoChunkingService.chunk(
                    context, video.userId(), video.contentHash(), goldenSet.indexVersion());
            Map<String, VideoChunk> chunksById = chunks.stream().collect(Collectors.toMap(
                    VideoChunk::chunkId, Function.identity()));

            assertThat(video.questions()).hasSize(3);
            assertThat(video.questions()).extracting(RagFixtureLoader.GoldenQuestion::turn)
                    .containsExactly(1, 2, 3);
            for (RagFixtureLoader.GoldenQuestion question : video.questions()) {
                assertThat(question.userQuestion()).isNotBlank();
                assertThat(question.standaloneQuery()).isNotBlank();
                assertThat(question.expectedTop7()).hasSize(7);
                assertThat(question.expectedTop7())
                        .extracting(RagFixtureLoader.GoldenChunk::rank)
                        .containsExactly(1, 2, 3, 4, 5, 6, 7);

                List<String> ids = question.expectedTop7().stream()
                        .map(RagFixtureLoader.GoldenChunk::chunkId).toList();
                assertThat(new LinkedHashSet<>(ids)).hasSize(7);
                for (RagFixtureLoader.GoldenChunk expected : question.expectedTop7()) {
                    assertThat(expected.relevance()).isBetween(1, 3);
                    assertThat(expected.reason()).isNotBlank();
                    assertThat(chunksById).as("%s 第%d轮 chunkId", video.fixture(), question.turn())
                            .containsKey(expected.chunkId());
                    VideoChunk actual = chunksById.get(expected.chunkId());
                    assertThat(actual.startTime()).isEqualTo(expected.startMs());
                    assertThat(actual.endTime()).isEqualTo(expected.endMs());
                }

                RagRetrievalMetrics.Report ideal = RagRetrievalMetrics.evaluate(
                        ids, new LinkedHashSet<>(ids), 7);
                assertThat(ideal.hitAtK()).isEqualTo(1);
                assertThat(ideal.recallAtK()).isEqualTo(1);
                assertThat(ideal.mrr()).isEqualTo(1);
                assertThat(ideal.ndcgAtK()).isEqualTo(1);
            }
        }
    }
}

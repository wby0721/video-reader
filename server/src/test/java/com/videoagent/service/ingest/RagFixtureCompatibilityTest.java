package com.videoagent.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.VideoContext;
import com.videoagent.support.RagFixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 保证模拟过程文件始终兼容真实 Checkpoint Java 类型和对齐规则。 */
class RagFixtureCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void twentyMinuteFixtureMatchesCheckpointPayloadTypes() throws Exception {
        verify(RagFixtureLoader.load20Minutes(mapper), 50, 114, 5_500, "CACHE-SHIELD-X7");
    }

    @Test
    void fortyMinuteFixtureMatchesCheckpointPayloadTypes() throws Exception {
        verify(RagFixtureLoader.load40Minutes(mapper), 100, 228, 11_000, "NET-TRACE-Z9");
    }

    private void verify(RagFixtureLoader.Fixture fixture, int asrCount,
                        int ocrCount, int minimumAsrCharacters,
                        String ocrOnlyToken) {
        List<VideoContextBuilder.AsrSeg> asr = fixture.asr();
        List<VideoContextBuilder.OcrFrame> ocr = fixture.ocr();
        VideoContext context = fixture.storedContext();
        long durationMs = fixture.durationMs();

        assertThat(asr).hasSize(asrCount);
        assertThat(ocr).hasSize(ocrCount);
        assertThat(asr.stream().mapToInt(segment -> segment.text().length()).sum())
                .isGreaterThanOrEqualTo(minimumAsrCharacters);
        long colloquialSegments = asr.stream()
                .filter(segment -> segment.text().matches(".*[嗯啊呃].*"))
                .count();
        assertThat(colloquialSegments).isGreaterThan(asr.size() / 2);
        String allAsr = asr.stream().map(VideoContextBuilder.AsrSeg::text)
                .reduce("", String::concat);
        assertThat(allAsr).contains("字幕", "外卖", "天气", "音乐");
        assertThat(asr.getFirst().startMs()).isZero();
        assertThat(asr.getLast().endMs()).isEqualTo(durationMs);
        for (int i = 1; i < asr.size(); i++) {
            assertThat(asr.get(i).startMs()).isEqualTo(asr.get(i - 1).endMs());
        }
        assertThat(ocr).allMatch(frame -> frame.timestampMs() >= 0
                && frame.timestampMs() < durationMs);
        assertThat(ocr).anyMatch(frame -> frame.texts().contains(ocrOnlyToken));
        assertThat(asr).noneMatch(segment -> segment.text().contains(ocrOnlyToken));
        assertThat(context.segments())
                .containsExactlyElementsOf(VideoContextBuilder.align(asr, ocr, durationMs));
    }
}

package com.videoagent.service.ingest;

import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoContextBuilderTest {

    @Test
    void align_attachesOcrFramesToSpeechSegments() {
        List<VideoContextBuilder.AsrSeg> asr = List.of(
                new VideoContextBuilder.AsrSeg(0, 10_000, "前序遍历"),
                new VideoContextBuilder.AsrSeg(20_000, 30_000, "根左右")
        );
        List<VideoContextBuilder.OcrFrame> ocr = List.of(
                new VideoContextBuilder.OcrFrame(3_000, List.of("前序遍历"), "frames/1/3000.jpg"),
                new VideoContextBuilder.OcrFrame(5_000, List.of("根 左 右"), "frames/1/5000.jpg"),
                new VideoContextBuilder.OcrFrame(25_000, List.of("二叉树"), "frames/1/25000.jpg")
        );

        List<VideoSegment> segments = VideoContextBuilder.align(asr, ocr, 30_000);

        assertThat(segments).hasSize(2);
        VideoSegment first = segments.get(0);
        assertThat(first.transcript()).isEqualTo("前序遍历");
        assertThat(first.ocrTexts()).containsExactlyInAnyOrder("前序遍历", "根 左 右");
        assertThat(first.evidenceFrames()).containsExactlyInAnyOrder("frames/1/3000.jpg", "frames/1/5000.jpg");
        VideoSegment second = segments.get(1);
        assertThat(second.transcript()).isEqualTo("根左右");
        assertThat(second.ocrTexts()).containsExactly("二叉树");
    }

    @Test
    void align_ocrInSpeechGap_becomesVisualOnlySegment() {
        List<VideoContextBuilder.AsrSeg> asr = List.of(
                new VideoContextBuilder.AsrSeg(0, 10_000, "第一段"),
                new VideoContextBuilder.AsrSeg(30_000, 40_000, "第二段")
        );
        // 大间隙 10s~30s 内出现 PPT 文字（无语音，纯画面信息）
        List<VideoContextBuilder.OcrFrame> ocr = List.of(
                new VideoContextBuilder.OcrFrame(20_000, List.of("关键公式：E=mc²"), "frames/1/20000.jpg")
        );

        List<VideoSegment> segments = VideoContextBuilder.align(asr, ocr, 40_000);

        assertThat(segments).hasSize(3);
        VideoSegment visual = segments.get(1);
        assertThat(visual.visualOnly()).isTrue();
        assertThat(visual.startMs()).isEqualTo(10_000);
        assertThat(visual.endMs()).isEqualTo(30_000);
        assertThat(visual.ocrTexts()).containsExactly("关键公式：E=mc²");
    }

    @Test
    void align_smallSpeechGap_keepsSingleSegment() {
        List<VideoContextBuilder.AsrSeg> asr = List.of(
                new VideoContextBuilder.AsrSeg(0, 10_000, "短句一"),
                new VideoContextBuilder.AsrSeg(11_000, 20_000, "短句二")
        );
        List<VideoContextBuilder.OcrFrame> ocr = List.of();

        List<VideoSegment> segments = VideoContextBuilder.align(asr, ocr, 20_000);

        assertThat(segments).hasSize(2); // 1s 间隙不产生视觉段
        assertThat(segments.get(0).speechOnly()).isTrue();
    }

    @Test
    void align_noAsr_fallsBackTo30sWindows() {
        List<VideoContextBuilder.OcrFrame> ocr = List.of(
                new VideoContextBuilder.OcrFrame(5_000, List.of("板书A"), "frames/1/5000.jpg"),
                new VideoContextBuilder.OcrFrame(35_000, List.of("板书B"), "frames/1/35000.jpg")
        );

        List<VideoSegment> segments = VideoContextBuilder.align(List.of(), ocr, 60_000);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).startMs()).isEqualTo(0);
        assertThat(segments.get(0).endMs()).isEqualTo(30_000);
        assertThat(segments.get(0).ocrTexts()).containsExactly("板书A");
        assertThat(segments.get(1).startMs()).isEqualTo(30_000);
        assertThat(segments.get(1).endMs()).isEqualTo(60_000);
        assertThat(segments.get(1).ocrTexts()).containsExactly("板书B");
    }

    @Test
    void align_emptyEverything_returnsEmpty() {
        assertThat(VideoContextBuilder.align(List.of(), List.of(), 30_000)).hasSize(1); // 兜底仍产出 30s 窗口
    }
}

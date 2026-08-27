package com.videoagent.service.ingest;

import com.videoagent.dto.VideoSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * VideoContext 对齐器（纯函数，便于单元测试）：
 * 将 ASR 分段与 OCR 关键帧按时间轴融合为 VideoSegment 列表。
 *
 * <p>规则：
 * <ol>
 *   <li>基线 = ASR 分段（有语音时按语音分段）；ASR 缺失时退化为 30s 时间窗；</li>
 *   <li>每个基线片段内 attach 落入 [start,end) 的 OCR 帧文本与帧引用；</li>
 *   <li>ASR 片段间大间隙（&gt;5s）内的 OCR 帧单独成段（纯画面信息，视觉-only）；</li>
 *   <li>时间窗基线（ASR 全失败）时，OCR 帧按时间窗归属，纯语音段 transcript 为空。</li>
 * </ol>
 */
public final class VideoContextBuilder {

    /** 语音片段间超过该间隔视为大间隙，期间 OCR 帧单独成段 */
    private static final long GAP_MS = 5_000L;
    /** 无 ASR 时的时间窗长度 */
    private static final long WINDOW_MS = 30_000L;

    private VideoContextBuilder() {
    }

    public static List<VideoSegment> align(List<AsrSeg> asrSegments, List<OcrFrame> ocrFrames, long durationMs) {
        List<VideoSegment> result = new ArrayList<>();
        List<AsrSeg> asr = asrSegments == null ? List.of() : asrSegments.stream()
                .sorted(Comparator.comparingLong(AsrSeg::startMs))
                .toList();
        List<OcrFrame> ocr = ocrFrames == null ? List.of() : ocrFrames.stream()
                .sorted(Comparator.comparingLong(OcrFrame::timestampMs))
                .toList();

        if (asr.isEmpty()) {
            return windowFallback(ocr, durationMs);
        }

        for (AsrSeg seg : asr) {
            List<String> texts = new ArrayList<>();
            List<String> frames = new ArrayList<>();
            for (OcrFrame f : ocr) {
                if (f.timestampMs() >= seg.startMs() && f.timestampMs() < seg.endMs()) {
                    texts.addAll(f.texts());
                    frames.add(f.frameRef());
                }
            }
            result.add(VideoSegment.of(seg.startMs(), seg.endMs(), seg.text(), texts, frames));
        }

        // 语音间隙中的 OCR 帧 → 纯画面信息段
        for (int i = 0; i < asr.size() - 1; i++) {
            long gapStart = asr.get(i).endMs();
            long gapEnd = asr.get(i + 1).startMs();
            if (gapEnd - gapStart < GAP_MS) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            List<String> frames = new ArrayList<>();
            for (OcrFrame f : ocr) {
                if (f.timestampMs() >= gapStart && f.timestampMs() < gapEnd) {
                    texts.addAll(f.texts());
                    frames.add(f.frameRef());
                }
            }
            if (!texts.isEmpty()) {
                long start = Math.max(gapStart, gapStart);
                result.add(VideoSegment.of(start, gapEnd, null, texts, frames));
            }
        }
        return result.stream().sorted(Comparator.comparingLong(VideoSegment::startMs)).toList();
    }

    /** ASR 全失败时的 30s 时间窗兜底：OCR 帧按窗口归属，纯语音信息缺失。 */
    private static List<VideoSegment> windowFallback(List<OcrFrame> ocr, long durationMs) {
        List<VideoSegment> result = new ArrayList<>();
        long start = 0;
        while (start < durationMs) {
            long end = Math.min(start + WINDOW_MS, durationMs);
            List<String> texts = new ArrayList<>();
            List<String> frames = new ArrayList<>();
            for (OcrFrame f : ocr) {
                if (f.timestampMs() >= start && f.timestampMs() < end) {
                    texts.addAll(f.texts());
                    frames.add(f.frameRef());
                }
            }
            result.add(VideoSegment.of(start, end, null, texts, frames));
            start = end;
        }
        return result;
    }

    /** ASR 文本段（毫秒时间轴）。 */
    public record AsrSeg(long startMs, long endMs, String text) {}

    /** OCR 帧（毫秒时间轴）。 */
    public record OcrFrame(long timestampMs, List<String> texts, String frameRef) {}
}

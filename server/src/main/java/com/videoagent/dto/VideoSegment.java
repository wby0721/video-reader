package com.videoagent.dto;

import java.util.List;

/**
 * 视频时间轴片段（方案 §5.1）。
 *
 * @param startMs        片段起始时间戳
 * @param endMs          片段结束时间戳
 * @param transcript     语音转写文本（可能为空：纯画面信息片段）
 * @param ocrTexts       画面 OCR 文字（可能为空：纯语音片段）
 * @param evidenceFrames 关键帧引用（MinIO 对象名，用于前端证据展示）
 */
public record VideoSegment(
        long startMs,
        long endMs,
        String transcript,
        List<String> ocrTexts,
        List<String> evidenceFrames
) {

    public static VideoSegment of(long startMs, long endMs, String transcript, List<String> ocrTexts, List<String> evidenceFrames) {
        return new VideoSegment(startMs, endMs, transcript, ocrTexts, evidenceFrames);
    }

    /** 是否为「纯画面信息」片段（无语音）。 */
    public boolean visualOnly() {
        return (transcript == null || transcript.isBlank()) && (ocrTexts != null && !ocrTexts.isEmpty());
    }

    /** 是否为「纯语音」片段。 */
    public boolean speechOnly() {
        return (ocrTexts == null || ocrTexts.isEmpty()) && (transcript != null && !transcript.isBlank());
    }
}

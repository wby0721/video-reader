package com.videoagent.dto;

import java.util.List;

/**
 * 5 分钟知识块：长视频检索的最小单元（方案 §5.2）。
 *
 * @param startTime      块起始时间戳（毫秒）
 * @param endTime        块结束时间戳（毫秒）
 * @param segmentSummary 片段摘要（≤200 字）
 * @param keywords       关键词
 * @param transcript     块内合并语音转写（检索关键词匹配用）
 * @param visualTexts    块内画面 OCR 文字（检索画面通道用）
 * @param rawSegments    原始片段（证据回溯）
 * @param embedding      摘要 Embedding 向量（1024 维，BGE-M3）
 */
public record VideoChunk(
        long startTime,
        long endTime,
        String segmentSummary,
        List<String> keywords,
        String transcript,
        List<String> visualTexts,
        List<VideoSegment> rawSegments,
        List<Float> embedding
) {
    public static VideoChunk of(long startTime, long endTime, String segmentSummary, List<String> keywords,
                                String transcript, List<String> visualTexts, List<VideoSegment> rawSegments,
                                List<Float> embedding) {
        return new VideoChunk(startTime, endTime, segmentSummary, keywords, transcript, visualTexts, rawSegments, embedding);
    }
}

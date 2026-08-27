package com.videoagent.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体文件信息（脱敏）。除基础字段外附带处理进度与各步骤耗时（视频库列表展示用）。
 */
public record MediaDto(
        Long id,
        String filename,
        String title,                 // LLM 自动生成/用户修改的简短视频标题
        String status,
        String contentHash,
        Long durationMs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String stage,                 // 当前阶段（SUBMITTED/INGEST/ASR/OCR/ALIGN/AGENT_*，处理中时有效）
        Integer progress,             // 0-100（PROCESSING 时有效）
        List<ProcessingTimeline.Step> steps, // 处理大步骤耗时（asr/ocr/llm 等）
        Long totalProcessMs           // 完整处理耗时（无时间线时回退为 updatedAt-createdAt）
) {
    public static MediaDto from(com.videoagent.entity.MediaFile m) {
        return new MediaDto(m.getId(), m.getFilename(), m.getTitle(), m.getStatus(), m.getContentHash(),
                m.getDurationMs(), m.getCreatedAt(), m.getUpdatedAt(), null, null, null, null);
    }

    public static MediaDto detail(com.videoagent.entity.MediaFile m, String stage, Integer progress,
                                  List<ProcessingTimeline.Step> steps, Long totalProcessMs) {
        return new MediaDto(m.getId(), m.getFilename(), m.getTitle(), m.getStatus(), m.getContentHash(),
                m.getDurationMs(), m.getCreatedAt(), m.getUpdatedAt(), stage, progress, steps, totalProcessMs);
    }
}

package com.videoagent.dto;

/**
 * 全局证据检索命中（方案 §6.2 跨项目检索中心）：命中证据片段 + 所属媒体。
 */
public record GlobalEvidenceHit(
        Long mediaId,
        String title,
        String filename,
        String chunkId,
        long startMs,
        long endMs,
        String summary,
        java.util.List<String> keywords
) {}

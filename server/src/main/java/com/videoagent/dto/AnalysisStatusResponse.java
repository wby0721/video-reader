package com.videoagent.dto;

/**
 * 分析任务状态查询响应。
 */
public record AnalysisStatusResponse(
        Long mediaId,
        String status,            // UPLOADED / PROCESSING / CONTEXT_READY / FAILED
        String stage,             // 当前流水线阶段（SUBMITTED/INGEST/ASR/OCR/ALIGN/AGENT_*/READY/FAILED）
        String error,             // 失败原因（仅 FAILED 时非空）
        boolean contextAvailable, // VideoContext checkpoint 是否已就绪
        boolean resultAvailable   // Agent 结构化结果是否已生成
) {}

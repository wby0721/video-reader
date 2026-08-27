package com.videoagent.dto;

/**
 * 分析任务受理响应（202）。
 */
public record AnalysisSubmitResponse(
        Long taskId,
        Long mediaId,
        String status,
        String message
) {}

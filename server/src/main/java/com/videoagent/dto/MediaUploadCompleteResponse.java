package com.videoagent.dto;

/**
 * 分片上传：合并完成响应（mediaId 可用于提交分析任务）。
 */
public record MediaUploadCompleteResponse(
        Long mediaId,
        String contentHash,
        String status,
        boolean reused
) {}

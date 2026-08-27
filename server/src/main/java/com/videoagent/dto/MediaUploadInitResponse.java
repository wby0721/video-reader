package com.videoagent.dto;

/**
 * 分片上传：初始化响应（断点续传：客户端保存 uploadId，可随时续传剩余分片）。
 */
public record MediaUploadInitResponse(String uploadId, String objectKey, String bucket) {}

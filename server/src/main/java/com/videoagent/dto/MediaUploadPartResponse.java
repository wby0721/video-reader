package com.videoagent.dto;

/**
 * 分片上传：单个分片结果。
 */
public record MediaUploadPartResponse(int partNumber, String etag) {}

package com.videoagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 分片上传：初始化请求。
 */
public record MediaUploadInitRequest(
        @NotBlank(message = "文件名不能为空") String filename,
        @Positive(message = "文件大小必须为正数") long totalSize
) {}

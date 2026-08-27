package com.videoagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 分片上传：合并请求。
 */
public record MediaUploadCompleteRequest(
        @NotBlank(message = "uploadId 不能为空") String uploadId,
        @NotEmpty(message = "分片列表不能为空") List<@Valid PartInfo> parts
) {
    public record PartInfo(
            @jakarta.validation.constraints.Min(value = 1, message = "partNumber 从 1 开始") int partNumber,
            @NotBlank(message = "etag 不能为空") String etag
    ) {}
}

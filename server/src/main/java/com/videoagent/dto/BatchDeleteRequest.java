package com.videoagent.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量删除媒体请求。
 */
public record BatchDeleteRequest(
        @NotEmpty(message = "待删除列表不能为空") List<Long> ids
) {}

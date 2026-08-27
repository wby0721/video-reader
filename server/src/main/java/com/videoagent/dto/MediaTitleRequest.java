package com.videoagent.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改视频标题请求（可传空串清除标题）。
 */
public record MediaTitleRequest(
        @Size(max = 64, message = "标题最长 64 字符") String title
) {}

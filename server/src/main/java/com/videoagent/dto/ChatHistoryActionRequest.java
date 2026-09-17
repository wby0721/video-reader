package com.videoagent.dto;

import jakarta.validation.constraints.NotNull;

/** 当前视频追问窗口的显示状态操作；不会删除后台审计历史。 */
public record ChatHistoryActionRequest(@NotNull Long mediaId) {}

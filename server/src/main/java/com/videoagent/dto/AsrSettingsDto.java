package com.videoagent.dto;

/**
 * ASR 识别设置视图。
 *
 * @param engine          local / xfyun
 * @param remainingHours  讯飞在线剩余时长（小时）
 * @param channels        讯飞并发路数（当前账号 1 路）
 */
public record AsrSettingsDto(
        String engine,
        double remainingHours,
        int channels
) {}

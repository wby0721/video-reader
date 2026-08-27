package com.videoagent.dto;

import java.util.List;

/**
 * 时序多模态上下文（感知层产出，方案 §5.1）：语音转写 + 画面文字 + 关键帧按时间轴对齐。
 *
 * @param source   视频源标识（mediaId）
 * @param userGoal 分析目标
 * @param segments 时间轴片段
 */
public record VideoContext(String source, String userGoal, List<VideoSegment> segments) {

    public static VideoContext of(String source, String userGoal, List<VideoSegment> segments) {
        return new VideoContext(source, userGoal, segments);
    }
}

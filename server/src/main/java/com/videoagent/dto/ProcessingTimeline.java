package com.videoagent.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体处理时间线（media 级 Checkpoint「process-timeline」）：记录每个大步骤耗时，
 * 供视频库列表展示「完整处理时间 + 各步骤耗时」。
 *
 * @param steps  步骤耗时（key 稳定，label 中文展示名）
 * @param totalMs 完整处理耗时（真实墙钟：ASR/OCR 并行步骤不重复计入，由写入方显式给出）
 * @param status  READY / REUSED / FAILED
 */
public record ProcessingTimeline(List<Step> steps, long totalMs, String status) {

    public record Step(String key, String label, long durationMs) {}

    public static ProcessingTimeline of(List<Step> steps, long totalMs, String status) {
        return new ProcessingTimeline(steps, totalMs, status);
    }

    public static ProcessingTimeline ofStatus(String status) {
        return new ProcessingTimeline(List.of(), 0, status);
    }

    /**
     * 按 key 覆盖（Agent 步骤可能随新目标重复运行）。
     * totalMs 保持墙钟语义：仅按新步骤与旧步骤的耗时差调整，绝不重算为步骤求和
     * （否则会重复计入并行的 ASR/OCR 窗口）。
     */
    public ProcessingTimeline withStep(String key, String label, long durationMs) {
        List<Step> next = new ArrayList<>();
        long replacedMs = 0;
        boolean replaced = false;
        for (Step s : steps) {
            if (s.key().equals(key)) {
                next.add(new Step(key, label, durationMs));
                replaced = true;
                replacedMs = s.durationMs();
            } else {
                next.add(s);
            }
        }
        if (!replaced) {
            next.add(new Step(key, label, durationMs));
        }
        long total = totalMs + (durationMs - replacedMs);
        return new ProcessingTimeline(next, total, status);
    }
}

package com.videoagent.service.agent;

import com.videoagent.dto.AnalysisMode;

import java.util.List;

/**
 * 模式配置（方案 §6.3）：每种模式 = 追加到 Planner/Executor/Critic 的指令 + 必须产出的段落规格。
 * 新增模式只需注册新 Profile，核心编排零改动（扩展点）。
 */
public record ModeProfile(
        AnalysisMode mode,
        String plannerInstruction,
        String executorInstruction,
        String criticInstruction,
        List<SectionSpec> requiredSections
) {
    /** 模式化段落规格。 */
    public record SectionSpec(String key, String title) {}
}

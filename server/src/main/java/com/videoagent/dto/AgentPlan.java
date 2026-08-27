package com.videoagent.dto;

import java.util.List;

/**
 * Planner 产物（方案 §5.4）：目标理解 + 1~5 个可验证任务。
 */
public record AgentPlan(
        String understoodGoal,
        List<String> tasks
) {}

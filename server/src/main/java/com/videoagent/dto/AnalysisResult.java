package com.videoagent.dto;

import java.util.List;

/**
 * Agent 固定结构化产物（方案 §5.3）：结论 + 时间戳证据 + 建议 + 模式化段落。
 *
 * @param title       分析标题
 * @param conclusions 核心结论（每条必须绑定证据）
 * @param evidence    时间戳证据
 * @param suggestions 建议
 * @param sections    模式化段落（学习/审查/创作模式使用）
 * @param warning     轮次/预算警告（正常为空）
 */
public record AnalysisResult(
        String title,
        List<String> conclusions,
        List<AnalysisEvidence> evidence,
        List<String> suggestions,
        List<AnalysisSection> sections,
        String warning
) {
    public static AnalysisResult of(String title, List<String> conclusions, List<AnalysisEvidence> evidence,
                                    List<String> suggestions, List<AnalysisSection> sections, String warning) {
        return new AnalysisResult(title, conclusions, evidence, suggestions, sections, warning);
    }

    public AnalysisResult withWarning(String w) {
        return new AnalysisResult(title, conclusions, evidence, suggestions, sections, w);
    }
}

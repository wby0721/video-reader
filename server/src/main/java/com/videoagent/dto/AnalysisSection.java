package com.videoagent.dto;

import java.util.List;

/**
 * 模式化段落（方案 §6.3 模式体系）。
 */
public record AnalysisSection(
        String key,      // outline / keypoints / quiz / pitfalls / fallacies / exaggerations / omissions / doubtful / highlights / titles / intro / script
        String title,    // 中文标题
        List<String> items
) {}

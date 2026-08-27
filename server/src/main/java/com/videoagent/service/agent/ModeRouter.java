package com.videoagent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 自动模式路由（方案 §6.3 关键机制）：LLM 按目标意图选择
 * GENERAL / LEARNING / REVIEW / CREATION；LLM 不可用或解析失败回退 GENERAL（含关键词启发）。
 */
@Service
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    private static final Map<String, AnalysisMode> KEYWORD_HINTS = Map.ofEntries(
            Map.entry("学习", AnalysisMode.LEARNING),
            Map.entry("教程", AnalysisMode.LEARNING),
            Map.entry("知识点", AnalysisMode.LEARNING),
            Map.entry("课程", AnalysisMode.LEARNING),
            Map.entry("大纲", AnalysisMode.LEARNING),
            Map.entry("审查", AnalysisMode.REVIEW),
            Map.entry("批判", AnalysisMode.REVIEW),
            Map.entry("漏洞", AnalysisMode.REVIEW),
            Map.entry("存疑", AnalysisMode.REVIEW),
            Map.entry("评价", AnalysisMode.REVIEW),
            Map.entry("创作", AnalysisMode.CREATION),
            Map.entry("写脚本", AnalysisMode.CREATION),
            Map.entry("文案", AnalysisMode.CREATION),
            Map.entry("标题", AnalysisMode.CREATION),
            Map.entry("爆点", AnalysisMode.CREATION),
            Map.entry("剪辑", AnalysisMode.CREATION)
    );

    private final ObjectMapper objectMapper;

    public ModeRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalysisMode route(String goal, LlmClient model) {
        if (model != null) {
            try {
                String json = model.chat("""
                        判断用户的分析目标最匹配哪种分析模式，只输出 JSON：
                        {"mode":"GENERAL|LEARNING|REVIEW|CREATION"}
                        - LEARNING 学习：学知识、课程、知识点、大纲
                        - REVIEW 审查：批判、找漏洞、评价、存疑
                        - CREATION 创作：做内容、写文案、标题、脚本、剪辑
                        - GENERAL 通用：其他
                        用户目标：%s
                        """.formatted(goal), 50);
                int s = json.indexOf('{');
                int e = json.lastIndexOf('}');
                JsonNode node = objectMapper.readTree(json.substring(s, e + 1));
                AnalysisMode mode = AnalysisMode.parse(node.path("mode").asText());
                log.debug("模式路由: {} -> {}", goal, mode);
                return mode;
            } catch (Exception ex) {
                log.warn("模式路由失败，回退关键词: {}", ex.getMessage());
            }
        }
        return keywordFallback(goal);
    }

    private AnalysisMode keywordFallback(String goal) {
        if (goal == null) {
            return AnalysisMode.GENERAL;
        }
        return KEYWORD_HINTS.entrySet().stream()
                .filter(e -> goal.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(AnalysisMode.GENERAL);
    }
}

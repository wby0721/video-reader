package com.videoagent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Planner 角色（方案 §6.3）：拆解目标为 1~5 个可验证任务（必须仅靠上下文证据完成）。
 */
@Service
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final ObjectMapper objectMapper;

    public Planner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentPlan plan(LlmClient model, String goal, ModeProfile profile, String overview) {
        String prompt = """
                你是视频分析规划师。根据用户目标与视频内容概览，制定 1~5 个可验证的分析任务。
                约束：任务必须仅能依靠视频证据完成；不得要求超出视频内容的外部知识。
                只输出 JSON：{"understoodGoal":"对目标的简明理解","tasks":["任务1","任务2",...]}
                用户目标：%s
                模式要求：%s
                视频内容概览：
                %s
                """.formatted(goal, profile.plannerInstruction(), trim(overview, 1500));
        try {
            JsonNode node = objectMapper.readTree(extractJson(model.chat(prompt, 300)));
            String understoodGoal = node.path("understoodGoal").asText(goal);
            List<String> rawTasks = new ArrayList<>();
            node.path("tasks").forEach(t -> rawTasks.add(t.asText().strip()));
            List<String> tasks = rawTasks.stream().filter(t -> !t.isBlank()).limit(5).toList();
            if (tasks.isEmpty()) {
                tasks = List.of(goal);
            }
            return new AgentPlan(understoodGoal, tasks);
        } catch (Exception e) {
            log.warn("Planner 解析失败，回退为单一任务: {}", e.getMessage());
            return new AgentPlan(goal, List.of(goal));
        }
    }

    private static String extractJson(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : "{}";
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }
}

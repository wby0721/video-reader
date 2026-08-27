package com.videoagent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.CriticResult;
import com.videoagent.dto.VerificationReport;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Critic 角色（方案 §6.3）：核验覆盖与证据，只检查不改写。
 * 输出 passed + 反馈 + 未覆盖要求 + 无证据结论 + 需定向补检索的时间戳。
 */
@Service
public class Critic {

    private static final Logger log = LoggerFactory.getLogger(Critic.class);

    private final ObjectMapper objectMapper;

    public Critic(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CriticResult critique(LlmClient model, AgentPlan plan, AnalysisResult result,
                                 EvidencePackService.EvidencePack pack, ModeProfile profile,
                                 VerificationReport verification) {
        String sectionsCheck = profile.requiredSections().isEmpty()
                ? "（无模式化段落要求）"
                : "模式化段落要求：" + profile.requiredSections().stream()
                        .map(s -> s.key() + "/" + s.title()).reduce((a, b) -> a + ", " + b).orElse("");
        String verificationHint = (verification == null || verification.unsupportedConclusions() == 0)
                ? "（验证报告无 UNSUPPORTED 结论）"
                : "证据验证报告提示：以下结论未通过验证（UNSUPPORTED），必须列入 unsupportedClaims 并给出补证据时间戳：\n"
                        + verification.verdicts().stream()
                        .filter(v -> "UNSUPPORTED".equals(v.status()))
                        .map(v -> "  - " + trim(v.claim(), 100) + "（" + trim(v.reason(), 60) + "）")
                        .reduce((a, b) -> a + "\n" + b).orElse("");

        String prompt = """
                你是视频分析批评家。核验执行者产出，只检查不改写。
                检查项：
                1. 计划任务是否基本被结论覆盖：允许把相近任务合并到同一结论；
                   仅当存在重要任务完全未被任何结论触及时才判不通过并列入 missingRequirements；
                2. 每条结论是否至少绑定一条证据（unsupportedClaims = 无证据结论）；
                3. 结论是否与其绑定证据矛盾、或超出证据内容（例如结论写了原文没有的细节），
                   必须列入 unsupportedClaims 并给出可补检索的证据时间戳；
                4. 结论是否完整、清晰。
                输出要求（引导下一轮执行者，不只是挑毛病）：
                - feedback 每条必须是「可直接执行的下一步指令」：对无证据/弱证据结论，
                  明确写出应在哪个时间戳区域、检索什么关键词、结论应如何改写（删除/拆分/合并/补充细节）；
                - 已被本轮证据覆盖的内容，不要重复列入 missingRequirements；
                - 每条反馈控制在 60 字内，避免空泛的"证据不足"。
                %s
                %s
                只输出 JSON：
                {"passed":true/false,"feedback":["可执行的修改建议"],"missingRequirements":["..."],"unsupportedClaims":["..."],"requiredTimestamps":[12345]}
                计划任务：%s
                可用证据时间戳：%s
                产出：%s
                """.formatted(sectionsCheck, verificationHint, plan.tasks(), pack.coveredTimestamps(), summarize(result));

        try {
            JsonNode node = objectMapper.readTree(extractJson(model.chat(prompt, 500)));
            List<String> feedback = strings(node, "feedback");
            List<String> missing = strings(node, "missingRequirements");
            List<String> unsupported = strings(node, "unsupportedClaims");
            List<Long> timestamps = new ArrayList<>();
            node.path("requiredTimestamps").forEach(t -> timestamps.add(t.asLong(-1)));
            timestamps.removeIf(t -> t < 0);
            boolean passed = node.path("passed").asBoolean(false);
            if (passed && (missing.isEmpty() && unsupported.isEmpty() && feedback.isEmpty())) {
                return CriticResult.ok();
            }
            return new CriticResult(passed, feedback, missing, unsupported, timestamps);
        } catch (Exception e) {
            log.warn("Critic 解析失败，按不通过处理: {}", e.getMessage());
            return new CriticResult(false, List.of("Critic 解析异常，请重试"), List.of(), List.of(), List.of());
        }
    }

    /** 裁剪后的结果摘要（控制提示词长度）。 */
    private static String summarize(AnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("title: ").append(result.title()).append('\n');
        for (int i = 0; i < result.conclusions().size(); i++) {
            sb.append("结论").append(i + 1).append(": ").append(trim(result.conclusions().get(i), 200)).append('\n');
        }
        for (int i = 0; i < result.evidence().size(); i++) {
            var e = result.evidence().get(i);
            sb.append("证据").append(i + 1).append(": ts=").append(e.timestampMs())
                    .append(" src=").append(e.source())
                    .append(" content=").append(trim(e.content(), 120)).append('\n')
                    .append("   claim=").append(trim(e.claim(), 100)).append('\n');
        }
        return sb.toString();
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        node.path(field).forEach(n -> {
            String v = n.asText("").strip();
            if (!v.isBlank()) {
                out.add(v);
            }
        });
        return out;
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

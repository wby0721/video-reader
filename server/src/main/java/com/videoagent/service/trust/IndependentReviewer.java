package com.videoagent.service.trust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.VerificationReport;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L3 独立复核（方案 §6.4）：独立角色复核 Critic 的 passed 判定与验证报告，
 * 阻断「自评偏差」——保证「通过结论经得起二次检验」。
 * 复核结果仅为附加标注，不阻断主链路（验证是增强能力）。
 */
@Component
public class IndependentReviewer {

    private static final Logger log = LoggerFactory.getLogger(IndependentReviewer.class);

    private final ObjectMapper objectMapper;

    public IndependentReviewer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 复核最终结果，返回一句话结论。 */
    public String review(AnalysisResult result, VerificationReport report, LlmClient model) {
        if (model == null) {
            return "L3 独立复核跳过（无 LLM）";
        }
        String verdictsText = report.verdicts().stream()
                .map(v -> "  - " + v.status() + " | " + trim(v.claim(), 60))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（无）");
        try {
            String json = model.chat("""
                    你是独立审查员。复核以下视频分析结果与证据验证报告，判断结论是否可信、是否存在过度宣称。
                    只输出 JSON：{"credible":true或false,"note":"不超过40字的独立意见"}
                    分析标题：%s
                    结论数：%d，验证报告：支持%d / 不支持%d / 无法验证%d
                    验证明细：
                    %s
                    Critic 最终判定：%s
                    """.formatted(result.title(), result.conclusions().size(),
                    report.supportedConclusions(), report.unsupportedConclusions(), report.unverifiableConclusions(),
                    verdictsText,
                    result.warning() == null ? "通过（无警告）" : "未通过（" + trim(result.warning(), 80) + "）"), 150);
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return "L3 独立复核：判定格式异常";
            }
            JsonNode node = objectMapper.readTree(json.substring(s, e + 1));
            boolean credible = node.path("credible").asBoolean(false);
            String note = node.path("note").asText("");
            return (credible ? "L3 独立复核通过" : "L3 独立复核存疑") + (note.isBlank() ? "" : "：" + note);
        } catch (Exception ex) {
            log.warn("L3 独立复核失败: {}", ex.getMessage());
            return "L3 独立复核失败（不阻断）";
        }
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }
}

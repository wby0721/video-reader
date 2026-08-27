package com.videoagent.service.trust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.utils.EmbeddingClient;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L2 语义蕴含（方案 §6.4）：证明「引用真的能证明结论」。
 *
 * <pre>
 * 对每条结论 claim：
 *   ├─ L1 保真校验 → 失败则 UNSUPPORTED
 *   ├─ embedding 相似度 (claim vs evidence.content)
 *   │    ├─ < 阈值 → UNSUPPORTED（省一次 LLM 调用）
 *   │    └─ ≥ 阈值 → 独立 LLM 蕴含判定 → SUPPORTED / UNSUPPORTED
 *   └─ 降级矩阵：
 *        LLM 失败 → 回退相似度判定
 *        embedding + LLM 都失败 → UNVERIFIABLE（安全放行）
 * </pre>
 */
@Component
public class EntailmentChecker {

    private static final Logger log = LoggerFactory.getLogger(EntailmentChecker.class);

    /** embedding 相似度闸门阈值（BGE-M3 归一化余弦；v6.3 由 0.55 放宽至 0.50——
     *  多证据绑定后证据质量提升，闸门只拦明显无关，细粒度判定交给 LLM 蕴含） */
    public static final double GATE_THRESHOLD = 0.50;

    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public EntailmentChecker(EmbeddingClient embeddingClient, ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单条 claim 与其证据内容做语义蕴含判定。
     *
     * @return 判定结果：supported / unsupported / unverifiable + 相似度 + 理由
     */
    public Verdict judge(String claim, List<String> evidenceContents, LlmClient model) {
        if (evidenceContents == null || evidenceContents.isEmpty()) {
            return Verdict.unsupported(-1, "结论未绑定任何证据");
        }
        String content = String.join(" ", evidenceContents);

        // 1) embedding 相似度闸门
        double similarity;
        try {
            List<Float> c = embeddingClient.embed(claim);
            List<Float> e = embeddingClient.embed(content);
            similarity = cosine(c, e);
        } catch (Exception ex) {
            log.warn("Embedding 不可用: {}", ex.getMessage());
            return judgeByLlmOnly(claim, content, model); // embedding 失败 → 直接 LLM（或 UNVERIFIABLE）
        }

        // 2) 闸门：低于阈值 → UNSUPPORTED（省一次 LLM 调用）
        if (similarity < GATE_THRESHOLD) {
            return Verdict.unsupported(similarity, "语义相似度 " + round(similarity) + " 低于闸门阈值 " + GATE_THRESHOLD);
        }

        // 3) 独立 LLM 蕴含判定
        return judgeByLlm(claim, content, similarity, model);
    }

    private Verdict judgeByLlm(String claim, String content, double similarity, LlmClient model) {
        if (model == null) {
            return Verdict.supported(similarity, "LLM 不可用，按闸门判定放行");
        }
        try {
            String json = model.chat("""
                    你是证据蕴含判定者。判断「结论」是否被「证据」语义支持（证据是视频原文引用）。
                    只输出 JSON：{"supported":true或false,"reason":"一句话理由（≤30字）"}
                    结论：%s
                    证据：%s
                    """.formatted(trim(claim, 200), trim(content, 800)), 120);
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return Verdict.supported(similarity, "LLM 判定格式异常，按闸门判定放行");
            }
            JsonNode node = objectMapper.readTree(json.substring(s, e + 1));
            boolean supported = node.path("supported").asBoolean(false);
            String reason = node.path("reason").asText("");
            return supported
                    ? Verdict.supported(similarity, "LLM 蕴含判定支持" + (reason.isBlank() ? "" : "：" + reason))
                    : Verdict.unsupported(similarity, "LLM 蕴含判定不支持" + (reason.isBlank() ? "" : "：" + reason));
        } catch (Exception ex) {
            log.warn("LLM 蕴含判定失败，回退相似度判定: {}", ex.getMessage());
            // 降级：LLM 失败 → 回退相似度判定（闸门已通过 → 支持）
            return Verdict.supported(similarity, "LLM 失败，按相似度判定放行");
        }
    }

    private Verdict judgeByLlmOnly(String claim, String content, LlmClient model) {
        if (model == null) {
            return Verdict.unverifiable("Embedding 与 LLM 均不可用，安全放行");
        }
        try {
            return judgeByLlm(claim, content, -1, model);
        } catch (Exception ex) {
            return Verdict.unverifiable("验证设施不可用，安全放行");
        }
    }

    /** 纯相似度闸门判定（供测试）。 */
    public static Verdict decideByGate(double similarity) {
        return similarity >= GATE_THRESHOLD
                ? Verdict.supported(similarity, "相似度通过闸门")
                : Verdict.unsupported(similarity, "相似度低于闸门");
    }

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }

    /** L2 判定结果。 */
    public record Verdict(String status, double supportScore, String reason) {
        public static Verdict supported(double score, String reason) {
            return new Verdict("SUPPORTED", score, reason);
        }

        public static Verdict unsupported(double score, String reason) {
            return new Verdict("UNSUPPORTED", score, reason);
        }

        public static Verdict unverifiable(String reason) {
            return new Verdict("UNVERIFIABLE", -1, reason);
        }
    }
}

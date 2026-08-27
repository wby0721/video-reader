package com.videoagent.dto;

/**
 * 单条结论的验证判定（方案 §5.5）。
 *
 * @param claim       结论原文
 * @param status      SUPPORTED | UNSUPPORTED | UNVERIFIABLE
 * @param supportScore embedding 余弦相似度（无则为 -1）
 * @param reason      一句判定理由
 */
public record ConclusionVerdict(
        String claim,
        String status,
        double supportScore,
        String reason
) {
    public static ConclusionVerdict supported(String claim, double score, String reason) {
        return new ConclusionVerdict(claim, "SUPPORTED", score, reason);
    }

    public static ConclusionVerdict unsupported(String claim, double score, String reason) {
        return new ConclusionVerdict(claim, "UNSUPPORTED", score, reason);
    }

    public static ConclusionVerdict unverifiable(String claim, String reason) {
        return new ConclusionVerdict(claim, "UNVERIFIABLE", -1, reason);
    }
}

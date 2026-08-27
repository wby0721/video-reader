package com.videoagent.dto;

import java.util.List;

/**
 * 结论可信度验证报告（方案 §5.5 / §6.4）：把「证据是否支持结论」从 LLM 自评
 * 升级为系统可独立判定的量化指标。
 *
 * @param totalConclusions        结论总数
 * @param supportedConclusions    有语义支撑
 * @param unsupportedConclusions  疑似幻觉（强制 Critic 判不通过并触发补证据）
 * @param unverifiableConclusions 验证设施不可用，安全放行（不改变结论）
 * @param semanticSupportRate     语义支撑率 = supported / (supported + unsupported)
 * @param hallucinationRate       幻觉率 = unsupported / (supported + unsupported)
 * @param verdicts                逐结论判定
 * @param l3Review                独立复核结论（L3）
 */
public record VerificationReport(
        int totalConclusions,
        int supportedConclusions,
        int unsupportedConclusions,
        int unverifiableConclusions,
        double semanticSupportRate,
        double hallucinationRate,
        List<ConclusionVerdict> verdicts,
        String l3Review
) {
    public static VerificationReport of(List<ConclusionVerdict> verdicts, String l3Review) {
        long supported = verdicts.stream().filter(v -> "SUPPORTED".equals(v.status())).count();
        long unsupported = verdicts.stream().filter(v -> "UNSUPPORTED".equals(v.status())).count();
        long unverifiable = verdicts.stream().filter(v -> "UNVERIFIABLE".equals(v.status())).count();
        long judged = supported + unsupported;
        double supportRate = judged == 0 ? 0 : (double) supported / judged;
        double hallucinationRate = judged == 0 ? 0 : (double) unsupported / judged;
        return new VerificationReport(verdicts.size(), (int) supported, (int) unsupported, (int) unverifiable,
                Math.round(supportRate * 10000) / 10000.0,
                Math.round(hallucinationRate * 10000) / 10000.0,
                verdicts, l3Review);
    }
}

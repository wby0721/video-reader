package com.videoagent.service.eval;

import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.EvaluationReport;
import com.videoagent.dto.VerificationReport;
import com.videoagent.entity.AnalysisFeedback;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import com.videoagent.service.trust.FidelityChecker;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多维评估（方案 §6.5，RAGAS 风格）：
 * structuredValid / claimEvidenceSupportRate / evidenceSupportRate(L1) /
 * semanticSupportRate / hallucinationRate / contextPrecision / userAcceptanceRate。
 */
@Service
public class AgentEvaluationService {

    /** 成本估算单价（元 / 千 token，含输入输出混合，可按模型调整） */
    private static final double PRICE_PER_1K_TOKENS = 0.002;

    private final FidelityChecker fidelityChecker;
    private final VideoEvidenceRetrievalService retrievalService;
    private final AnalysisFeedbackRepository feedbackRepository;

    public AgentEvaluationService(FidelityChecker fidelityChecker,
                                  VideoEvidenceRetrievalService retrievalService,
                                  AnalysisFeedbackRepository feedbackRepository) {
        this.fidelityChecker = fidelityChecker;
        this.retrievalService = retrievalService;
        this.feedbackRepository = feedbackRepository;
    }

    public EvaluationReport evaluate(Long mediaId, String contentHash, String goal, String goalKey,
                                     AnalysisResult result,
                                     com.videoagent.dto.VideoContext context,
                                     VerificationReport verification,
                                     AgentTelemetry.RunTrace telemetry,
                                     Long userId) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 1) 结构完整性
        boolean structuredValid = result != null
                && result.title() != null && !result.title().isBlank()
                && result.conclusions() != null && !result.conclusions().isEmpty()
                && result.evidence() != null && !result.evidence().isEmpty();
        metrics.put("structuredValid", structuredValid);

        // 2) 结论证据绑定率
        double claimEvidenceSupportRate = 0;
        if (result != null && !result.conclusions().isEmpty()) {
            long bound = result.conclusions().stream()
                    .filter(c -> result.evidence().stream().anyMatch(e -> FidelityChecker.claimMatches(c, e)))
                    .count();
            claimEvidenceSupportRate = round((double) bound / result.conclusions().size());
        }
        metrics.put("claimEvidenceSupportRate", claimEvidenceSupportRate);

        // 3) L1 证据原文保真率（逐证据重新核验，本地零成本）
        double evidenceSupportRate = 0;
        if (result != null && result.evidence() != null && !result.evidence().isEmpty()) {
            long faithful = result.evidence().stream()
                    .filter(e -> fidelityChecker.verify(e.content(), e.timestampMs(), e.source(), context))
                    .count();
            evidenceSupportRate = round((double) faithful / result.evidence().size());
        }
        metrics.put("evidenceSupportRate", evidenceSupportRate);

        // 4) L2 语义支撑率 / 幻觉率（来自验证报告）
        if (verification != null) {
            metrics.put("semanticSupportRate", verification.semanticSupportRate());
            metrics.put("hallucinationRate", verification.hallucinationRate());
            metrics.put("supportedConclusions", verification.supportedConclusions());
            metrics.put("unsupportedConclusions", verification.unsupportedConclusions());
            metrics.put("l3Review", verification.l3Review());
        }

        // 5) contextPrecision：检索召回片段与目标的平均相关度（证据检索 TopK 平均混合分）
        double contextPrecision = 0;
        try {
            if (result != null && !result.conclusions().isEmpty()) {
                List<com.videoagent.dto.EvidenceHit> hits = retrievalService.searchNoRewrite(
                        mediaId, contentHash, context, goal, result.conclusions().size(), userId);
                contextPrecision = hits.isEmpty() ? 0 : round(hits.stream().mapToDouble(com.videoagent.dto.EvidenceHit::score).average().orElse(0));
            }
        } catch (Exception ignored) {
            // 检索不可用不影响评估主流程
        }
        metrics.put("contextPrecision", contextPrecision);

        // 6) 用户接受率（👍/👎）
        List<AnalysisFeedback> feedbacks = feedbackRepository.findByMediaIdAndGoalKey(mediaId, goalKey);
        if (feedbacks.isEmpty()) {
            metrics.put("userAcceptanceRate", null);
            metrics.put("feedbackCount", 0);
        } else {
            long up = feedbacks.stream().filter(f -> f.getRating() > 0).count();
            metrics.put("userAcceptanceRate", round((double) up / feedbacks.size()));
            metrics.put("feedbackCount", feedbacks.size());
        }

        // 7) 成本估算（Token × 单价）
        long tokens = telemetry == null ? 0 : telemetry.totalTokensEstimate();
        double cost = tokens / 1000.0 * PRICE_PER_1K_TOKENS;
        metrics.put("costEstimateYuan", round(cost));

        List<EvaluationReport.TelemetryStage> stages = telemetry == null ? List.of()
                : telemetry.stages().stream()
                        .map(s -> new EvaluationReport.TelemetryStage(s.stage(), s.durationMs(), s.llmCalls(), s.tokensEstimate()))
                        .toList();

        return new EvaluationReport(goal, metrics, stages,
                telemetry == null ? 0 : telemetry.totalDurationMs(),
                telemetry == null ? 0 : telemetry.totalLlmCalls(),
                tokens, round(cost));
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}

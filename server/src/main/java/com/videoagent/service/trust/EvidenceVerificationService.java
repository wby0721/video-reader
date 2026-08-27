package com.videoagent.service.trust;

import com.videoagent.dto.AnalysisEvidence;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.ConclusionVerdict;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VerificationReport;
import com.videoagent.utils.LlmClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据验证编排（方案 §6.4）：
 * <ol>
 *   <li>对每条结论：按 claim 匹配绑定证据；</li>
 *   <li>L1 原文保真 → 失败 UNSUPPORTED；</li>
 *   <li>L2 语义蕴含（embedding 闸门 + LLM 判定 + 降级矩阵）；</li>
 *   <li>终轮产出完整报告（含 L3 独立复核）。</li>
 * </ol>
 * 关键约束：验证是增强能力，绝不阻断主链路——UNVERIFIABLE 不改变结论，
 * 仅 UNSUPPORTED 强制 Critic 判不通过并触发定向补证据。
 */
@Service
public class EvidenceVerificationService {

    private final FidelityChecker fidelityChecker;
    private final EntailmentChecker entailmentChecker;
    private final IndependentReviewer independentReviewer;

    public EvidenceVerificationService(FidelityChecker fidelityChecker, EntailmentChecker entailmentChecker,
                                       IndependentReviewer independentReviewer) {
        this.fidelityChecker = fidelityChecker;
        this.entailmentChecker = entailmentChecker;
        this.independentReviewer = independentReviewer;
    }

    /**
     * 每轮验证（供 Critic 参考）：L1 + L2 全量判定。
     *
     * @param useLlmEntailment 是否执行 LLM 蕴含判定（每轮建议 false 省 token，终轮 true）
     */
    public VerificationReport verify(AnalysisResult result, VideoContext context, LlmClient model,
                                     boolean useLlmEntailment) {
        List<ConclusionVerdict> verdicts = new ArrayList<>();
        for (String claim : result.conclusions()) {
            verdicts.add(verifyClaim(claim, result, context, model, useLlmEntailment));
        }
        return VerificationReport.of(verdicts, null);
    }

    /** 终轮验证：全量 L2（含 LLM 蕴含）+ L3 独立复核。 */
    public VerificationReport finalize(AnalysisResult result, VideoContext context, LlmClient model) {
        VerificationReport report = verify(result, context, model, true);
        String l3 = independentReviewer.review(result, report, model);
        return new VerificationReport(report.totalConclusions(), report.supportedConclusions(),
                report.unsupportedConclusions(), report.unverifiableConclusions(),
                report.semanticSupportRate(), report.hallucinationRate(), report.verdicts(), l3);
    }

    private ConclusionVerdict verifyClaim(String claim, AnalysisResult result, VideoContext context,
                                          LlmClient model, boolean useLlmEntailment) {
        List<AnalysisEvidence> bound = result.evidence().stream()
                .filter(e -> FidelityChecker.claimMatches(claim, e))
                .toList();
        if (bound.isEmpty()) {
            return ConclusionVerdict.unsupported(claim, -1, "结论未绑定任何证据");
        }

        // L1 原文保真：证据内容须逐字命中 ASR/OCR 原文
        List<AnalysisEvidence> faithful = bound.stream()
                .filter(e -> fidelityChecker.verify(e.content(), e.timestampMs(), e.source(), context))
                .toList();
        if (faithful.isEmpty()) {
            return ConclusionVerdict.unsupported(claim, -1, "证据未通过 L1 原文保真（引用疑似编造）");
        }

        // L2 语义蕴含：claim vs 保真证据内容
        List<String> contents = faithful.stream().map(AnalysisEvidence::content).toList();
        if (useLlmEntailment) {
            EntailmentChecker.Verdict v = entailmentChecker.judge(claim, contents, model);
            return new ConclusionVerdict(claim, v.status(), v.supportScore(), v.reason());
        }
        // 每轮轻量模式：仅 embedding 闸门（省 LLM 调用），供 Critic 参考
        try {
            return mapVerdict(claim, entailmentChecker.judge(claim, contents, null));
        } catch (Exception e) {
            return ConclusionVerdict.unverifiable(claim, "验证设施不可用，安全放行");
        }
    }

    private ConclusionVerdict mapVerdict(String claim, EntailmentChecker.Verdict v) {
        return new ConclusionVerdict(claim, v.status(), v.supportScore(), v.reason());
    }
}

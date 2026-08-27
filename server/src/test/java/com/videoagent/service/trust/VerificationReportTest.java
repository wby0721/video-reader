package com.videoagent.service.trust;

import com.videoagent.dto.ConclusionVerdict;
import com.videoagent.dto.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationReportTest {

    @Test
    void rates_calculateCorrectly() {
        VerificationReport report = VerificationReport.of(List.of(
                ConclusionVerdict.supported("c1", 0.8, "ok"),
                ConclusionVerdict.supported("c2", 0.7, "ok"),
                ConclusionVerdict.unsupported("c3", 0.3, "low"),
                ConclusionVerdict.unverifiable("c4", "no facility")
        ), "L3 复核通过");

        assertThat(report.totalConclusions()).isEqualTo(4);
        assertThat(report.supportedConclusions()).isEqualTo(2);
        assertThat(report.unsupportedConclusions()).isEqualTo(1);
        assertThat(report.unverifiableConclusions()).isEqualTo(1);
        // 分母仅含可判定（supported + unsupported），报告四舍五入到 4 位小数
        assertThat(report.semanticSupportRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.hallucinationRate()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.l3Review()).contains("通过");
    }

    @Test
    void rates_zeroJudged_isZero() {
        VerificationReport report = VerificationReport.of(List.of(
                ConclusionVerdict.unverifiable("c", "none")
        ), null);
        assertThat(report.semanticSupportRate()).isZero();
        assertThat(report.hallucinationRate()).isZero();
    }

    @Test
    void gateDecision_thresholdBoundary() {
        assertThat(EntailmentChecker.decideByGate(0.7).status()).isEqualTo("SUPPORTED");
        assertThat(EntailmentChecker.decideByGate(0.3).status()).isEqualTo("UNSUPPORTED");
        assertThat(EntailmentChecker.decideByGate(EntailmentChecker.GATE_THRESHOLD).status()).isEqualTo("SUPPORTED");
    }
}

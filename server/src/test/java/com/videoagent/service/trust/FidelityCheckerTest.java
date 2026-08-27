package com.videoagent.service.trust;

import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityCheckerTest {

    private final FidelityChecker checker = new FidelityChecker();

    private static VideoContext ctx() {
        return VideoContext.of("1", "g", List.of(
                VideoSegment.of(0, 60_000, "计算机网络由硬件软件和协议组成 硬件包括主机", List.of("本节内容", "主机即端系统"), List.of()),
                VideoSegment.of(300_000, 360_000, "数据通信是最基本最重要的功能", List.of("数据通信"), List.of())
        ));
    }

    @Test
    void verbatimHit_inTranscript() {
        assertThat(checker.verify("计算机网络由硬件软件和协议组成", 0, "ASR", ctx())).isTrue();
    }

    @Test
    void verbatimHit_inOcr() {
        assertThat(checker.verify("本节内容", 0, "OCR", ctx())).isTrue();
        assertThat(checker.verify("主机即端系统", 0, "ASR+OCR", ctx())).isTrue();
    }

    @Test
    void hit_contentAnywhereInVideo_ignoresCoarseTimestamp() {
        // 新语义：L1 只保证「引用逐字存在于视频任意片段」（反编造），时间戳是分块级粗粒度，
        // 不因时间戳窗口错位而误判为编造（否则块内靠后的原文会被漏检）。
        assertThat(checker.verify("数据通信是最基本最重要的功能", 0, "ASR", ctx())).isTrue();
    }

    @Test
    void miss_sourceFilteredOut() {
        // 该句只在转写中，OCR 源查不到
        assertThat(checker.verify("计算机网络由硬件软件和协议组成", 0, "OCR", ctx())).isFalse();
    }

    @Test
    void miss_fabricatedContent() {
        assertThat(checker.verify("量子计算是未来趋势", 0, "ASR+OCR", ctx())).isFalse();
    }

    @Test
    void miss_blankContent() {
        assertThat(checker.verify("", 0, "ASR", ctx())).isFalse();
    }

    @Test
    void claimMatches_exactOrContain() {
        var ev = new com.videoagent.dto.AnalysisEvidence(0, "ASR", "x", "计算机网络由硬件软件和协议组成");
        assertThat(FidelityChecker.claimMatches("计算机网络由硬件软件和协议组成", ev)).isTrue();
        assertThat(FidelityChecker.claimMatches("计算机网络由硬件软件和协议组成，硬件包括主机", ev)).isTrue();
        assertThat(FidelityChecker.claimMatches("完全无关的结论", ev)).isFalse();
    }
}

package com.videoagent.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenSetEvaluatorTest {

    @Test
    void keywordCoverage_passThreshold() {
        GoldenSetEvaluator.GoldenTask task = new GoldenSetEvaluator.GoldenTask(
                "学习计算机网络的组成与功能", List.of("硬件", "软件", "协议", "主机"), "LEARNING");
        GoldenSetEvaluator.GoldenResult r = GoldenSetEvaluator.evaluateTexts(task,
                List.of("计算机网络由硬件、软件和协议组成", "主机包括电脑、手机、服务器"));
        assertThat(r.keywordCoverage()).isEqualTo(1.0);
        assertThat(r.pass()).isTrue();
        assertThat(r.coveredKeywords()).containsExactly("硬件", "软件", "协议", "主机");
    }

    @Test
    void keywordCoverage_partialBelowThreshold() {
        GoldenSetEvaluator.GoldenTask task = new GoldenSetEvaluator.GoldenTask(
                "审查讲解", List.of("漏洞", "夸大", "遗漏", "存疑"), "REVIEW");
        GoldenSetEvaluator.GoldenResult r = GoldenSetEvaluator.evaluateTexts(task,
                List.of("计算机网络的组成与功能", "数据通信"));
        assertThat(r.keywordCoverage()).isEqualTo(0.0);
        assertThat(r.pass()).isFalse();
    }
}

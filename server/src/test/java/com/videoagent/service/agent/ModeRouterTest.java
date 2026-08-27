package com.videoagent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.AnalysisMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModeRouterTest {

    private final ModeRouter router = new ModeRouter(new ObjectMapper());

    @Test
    void keywordFallback_learning() {
        assertThat(router.route("帮我学习计算机网络知识点", null)).isEqualTo(AnalysisMode.LEARNING);
        assertThat(router.route("给出学习大纲", null)).isEqualTo(AnalysisMode.LEARNING);
    }

    @Test
    void keywordFallback_review() {
        assertThat(router.route("审查这个视频的逻辑漏洞", null)).isEqualTo(AnalysisMode.REVIEW);
        assertThat(router.route("批判性评价", null)).isEqualTo(AnalysisMode.REVIEW);
    }

    @Test
    void keywordFallback_creation() {
        assertThat(router.route("帮我写一个口播文案", null)).isEqualTo(AnalysisMode.CREATION);
    }

    @Test
    void keywordFallback_general() {
        assertThat(router.route("总结视频主要内容", null)).isEqualTo(AnalysisMode.GENERAL);
    }

    @Test
    void modeParse_handlesInvalidAndBlank() {
        assertThat(AnalysisMode.parse("learning")).isEqualTo(AnalysisMode.LEARNING);
        assertThat(AnalysisMode.parse("  review ")).isEqualTo(AnalysisMode.REVIEW);
        assertThat(AnalysisMode.parse("")).isEqualTo(AnalysisMode.GENERAL);
        assertThat(AnalysisMode.parse("BOGUS")).isEqualTo(AnalysisMode.GENERAL);
        assertThat(AnalysisMode.parse(null)).isEqualTo(AnalysisMode.GENERAL);
    }

    @Test
    void goalKey_isStableAndModeSensitive() {
        String k1 = AgentLoopService.goalKey("总结视频", AnalysisMode.GENERAL);
        String k2 = AgentLoopService.goalKey("总结视频", AnalysisMode.GENERAL);
        String k3 = AgentLoopService.goalKey("总结视频", AnalysisMode.LEARNING);
        assertThat(k1).isEqualTo(k2);
        assertThat(k1).isNotEqualTo(k3);
        assertThat(k1).startsWith("goal-");
    }
}

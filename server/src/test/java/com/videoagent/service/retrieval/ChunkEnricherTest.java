package com.videoagent.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkEnricherTest {

    @Test
    void fallback_extractsSummaryFromTranscript() {
        ChunkEnricher.Enriched e = ChunkEnricher.fallback("前序遍历的顺序是根节点左子树右子树，中序遍历是左子树根节点右子树。", List.of());
        assertThat(e.summary()).startsWith("前序遍历的顺序是根节点");
        assertThat(e.summary().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void fallback_keywordsFromVisualTextsAndFreqTerms() {
        ChunkEnricher.Enriched e = ChunkEnricher.fallback(
                "二叉树遍历 二叉树遍历 前序遍历 前序遍历 中序遍历",
                List.of("二叉树", "王道考研"));
        assertThat(e.keywords()).contains("二叉树", "王道考研");
        // 高频词兜底
        assertThat(e.keywords().size()).isBetween(2, 8);
    }

    @Test
    void fallback_blankEverything_stillReturnsSummary() {
        ChunkEnricher.Enriched e = ChunkEnricher.fallback("", List.of());
        assertThat(e.summary()).isNotNull();
        assertThat(e.keywords()).isEmpty();
    }
}

package com.videoagent.service.retrieval;

import com.videoagent.config.AppProperties;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.RetrievalAssessment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.videoagent.dto.RetrievalAssessment.Status.*;

/** One policy shared by chat, Agent evidence and global search. No evaluator labels. */
@Service
public class RelevancePolicy {
    private final double minScore;
    private final boolean rejectLow;

    @Autowired
    public RelevancePolicy(AppProperties properties) {
        this(properties.retrieval() == null ? new AppProperties.Retrieval(null) : properties.retrieval());
    }
    public RelevancePolicy() { this(new AppProperties.Retrieval(null)); }
    public RelevancePolicy(AppProperties.Retrieval config) {
        minScore = config.minRerankerScore();
        rejectLow = config.rejectLowRelevance();
    }

    public Decision evaluate(List<EvidenceHit> input) {
        return evaluate(input, "QDRANT");
    }

    public Decision evaluate(List<EvidenceHit> input, String denseSource) {
        List<EvidenceHit> hits = input == null ? List.of() : List.copyOf(input);
        boolean recallDegraded = denseSource != null && (denseSource.contains("UNAVAILABLE") || denseSource.contains("LOCAL_COSINE"));
        if (hits.isEmpty() && recallDegraded) return new Decision(hits, new RetrievalAssessment(DEGRADED, true,
                "检索服务降级且没有获得候选，暂时无法判断视频是否包含答案，请稍后重试。"));
        if (hits.isEmpty()) return new Decision(hits, new RetrievalAssessment(NO_EVIDENCE, false,
                "本次未检索到可用证据，不代表视频一定没有相关内容。"));
        boolean unscored = hits.stream().anyMatch(h -> h.scoreType() != EvidenceHit.ScoreType.RERANKER_SIGMOID
                || !Double.isFinite(h.score()) || h.score() < 0 || h.score() > 1);
        boolean degraded = recallDegraded || unscored || hits.stream().anyMatch(h -> h.source() != null
                && (h.source().contains("LOCAL_COSINE") || h.source().contains("UNAVAILABLE")));
        if (unscored) return new Decision(hits, new RetrievalAssessment(DEGRADED, true,
                "精排分数不可用或来自旧记录；保留候选但不能据此判断问题无关。只回答原文明确支持的内容。"));
        List<EvidenceHit> strong = hits.stream().filter(h -> h.score() >= minScore).toList();
        boolean low = strong.isEmpty();
        var status = low ? LOW_RELEVANCE : degraded ? DEGRADED : CANDIDATE_EVIDENCE;
        String hint = low
                ? "当前问题与检索证据可能相关性不强；若原文没有明确答案，请说明证据不足，不要补造细节。"
                : "候选证据相关不等于足以回答；仅使用原文明确支持的信息，未给出的细节必须说明不足。";
        if (degraded) hint = "部分检索链路已降级。" + hint;
        return new Decision(rejectLow ? strong : hits, new RetrievalAssessment(status, degraded, hint));
    }

    public double minScore() { return minScore; }

    public boolean rejectLow() { return rejectLow; }

    public record Decision(List<EvidenceHit> hits, RetrievalAssessment assessment) {}
}

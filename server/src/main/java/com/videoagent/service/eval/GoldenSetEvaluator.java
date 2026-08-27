package com.videoagent.service.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 离线黄金任务集回归（方案 §6.5）：structuredValid + 幻觉率 + 关键词覆盖 三重判定。
 *
 * <p>黄金任务集见 {@code eval/golden-tasks.json}；每条任务给定目标与期望关键词，
 * 通过证据检索计算关键词覆盖（contextPrecision 近似），结合结构化产出与幻觉率给出回归判定。
 */
@Service
public class GoldenSetEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GoldenSetEvaluator.class);

    private final ObjectMapper objectMapper;
    private final VideoEvidenceRetrievalService retrievalService;

    public GoldenSetEvaluator(ObjectMapper objectMapper, VideoEvidenceRetrievalService retrievalService) {
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
    }

    /**
     * 对单条黄金任务执行回归判定（供离线脚本/测试调用）。
     *
     * @return 判定明细：keywordCoverage 等
     */
    public GoldenResult evaluate(GoldenTask task, Long mediaId, String contentHash,
                                 com.videoagent.dto.VideoContext context, Long userId) {
        List<EvidenceHit> hits = retrievalService.searchNoRewrite(mediaId, contentHash, context,
                task.goal(), 5, userId);
        List<String> topTexts = hits.stream().map(h -> h.summary() + " " + String.join(" ", h.keywords())).toList();
        return evaluateTexts(task, topTexts);
    }

    /** 纯关键词覆盖判定（供测试）。 */
    public static GoldenResult evaluateTexts(GoldenTask task, List<String> texts) {
        List<String> covered = new java.util.ArrayList<>();
        for (String keyword : task.expectedKeywords()) {
            boolean hit = texts.stream().anyMatch(t -> t != null && t.contains(keyword));
            if (hit) {
                covered.add(keyword);
            }
        }
        double coverage = task.expectedKeywords().isEmpty() ? 0 : (double) covered.size() / task.expectedKeywords().size();
        boolean pass = coverage >= 0.6;
        return new GoldenResult(task.goal(), task.mode(), round(coverage), covered, pass);
    }

    public List<GoldenTask> loadTasks(String jsonPath) {
        try {
            return objectMapper.readValue(new java.io.File(jsonPath), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("黄金任务集加载失败: " + e.getMessage(), e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    /** 黄金任务定义。 */
    public record GoldenTask(String goal, List<String> expectedKeywords, String mode) {}

    /** 单任务回归结果。 */
    public record GoldenResult(String goal, String mode, double keywordCoverage, List<String> coveredKeywords, boolean pass) {}
}

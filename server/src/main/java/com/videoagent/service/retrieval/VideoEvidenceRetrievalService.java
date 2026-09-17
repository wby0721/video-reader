package com.videoagent.service.retrieval;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.EvidenceBounds;
import com.videoagent.dto.RetrievalTrace;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.LlmClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单视频检索兼容 Facade。索引准备和可选查询改写仍保留在这里，
 * Dense/BM25/OCR 召回及 RRF 排序统一委托给 {@link HybridRetrievalService}。
 */
@Service
public class VideoEvidenceRetrievalService {

    private final RetrievalIndexService indexService;
    private final HybridRetrievalService hybridRetrievalService;
    private final QueryRewriter queryRewriter;
    private final LlmProvider llmProvider;

    public VideoEvidenceRetrievalService(RetrievalIndexService indexService,
                                         HybridRetrievalService hybridRetrievalService,
                                         QueryRewriter queryRewriter,
                                         LlmProvider llmProvider) {
        this.indexService = indexService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.queryRewriter = queryRewriter;
        this.llmProvider = llmProvider;
    }

    public List<EvidenceHit> search(Long mediaId, String contentHash, VideoContext context, String query,
                                    int topK, Long userId) {
        return searchInternal(mediaId, contentHash, context, query, topK, userId, true);
    }

    /** 检索但不做生成式意图改写；Agent 的明确任务和全局定位搜索使用。 */
    public List<EvidenceHit> searchNoRewrite(Long mediaId, String contentHash, VideoContext context, String query,
                                             int topK, Long userId) {
        return searchInternal(mediaId, contentHash, context, query, topK, userId, false);
    }

    /** Agent 多任务批量入口：索引只准备一次，Embedding 与 Reranker 跨任务微批处理。 */
    public Map<String, List<EvidenceHit>> searchNoRewriteBatch(
            Long mediaId, String contentHash, VideoContext context,
            List<String> queries, int topK, Long userId) {
        Map<String, List<EvidenceHit>> hits = new LinkedHashMap<>();
        searchNoRewriteBatchPrepared(mediaId, contentHash, context, queries, topK, userId)
                .forEach((q, result) -> hits.put(q, result.hits()));
        return hits;
    }

    /** Batch with diagnostic metadata, including empty/degraded results. */
    public Map<String, PreparedSearch> searchNoRewriteBatchPrepared(
            Long mediaId, String contentHash, VideoContext context,
            List<String> queries, int topK, Long userId) {
        if (queries == null || queries.isEmpty()) {
            return Map.of();
        }
        List<VideoChunk> chunks = indexService.index(mediaId, contentHash, context, userId);
        if (chunks.isEmpty()) {
            return queries.stream().distinct().collect(java.util.stream.Collectors.toMap(
                    query -> query, query -> new PreparedSearch(List.of(), List.of(), "NOT_RUN"),
                    (a, b) -> a, LinkedHashMap::new));
        }
        List<HybridQuery> hybridQueries = queries.stream()
                .map(query -> {
                    QueryRewriter.Rewrite rewritten = fallback(query);
                    return new HybridQuery(query, rewritten.semanticQuery(),
                            rewritten.keywords(), rewritten.visualKeywords());
                }).toList();
        List<RetrievalResult> results = hybridRetrievalService.retrieveBatch(
                RetrievalScope.singleMedia(userId, mediaId, contentHash),
                hybridQueries, chunks, Math.max(1, topK));
        Map<String, PreparedSearch> byQuery = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            RetrievalResult result = i < results.size()
                    ? results.get(i) : new RetrievalResult(List.of(), "NOT_RUN");
            byQuery.put(queries.get(i), new PreparedSearch(toHits(result, chunks, hybridQueries.get(i)),
                    chunks, result.denseSource(), result.trace()));
        }
        return byQuery;
    }

    private List<EvidenceHit> searchInternal(Long mediaId, String contentHash, VideoContext context, String query,
                                             int topK, Long userId, boolean rewrite) {
        LlmClient model = rewrite ? llmProvider.forUser(userId) : null;
        QueryRewriter.Rewrite rewritten = rewrite ? queryRewriter.rewrite(query, model) : fallback(query);
        HybridQuery hybridQuery = new HybridQuery(
                query, rewritten.semanticQuery(), rewritten.keywords(), rewritten.visualKeywords());
        return searchPrepared(mediaId, contentHash, context, hybridQuery, topK, userId).hits();
    }

    /** 已完成历史感知改写时直接检索，避免再次调用改写模型；同时返回本次实际索引块供原文回读。 */
    public PreparedSearch searchPrepared(Long mediaId, String contentHash, VideoContext context,
                                         HybridQuery hybridQuery, int topK, Long userId) {
        List<VideoChunk> chunks = indexService.index(mediaId, contentHash, context, userId);
        if (chunks.isEmpty()) {
            return new PreparedSearch(List.of(), List.of());
        }
        RetrievalResult result = hybridRetrievalService.retrieve(
                RetrievalScope.singleMedia(userId, mediaId, contentHash),
                hybridQuery, chunks, Math.max(1, topK));

        return new PreparedSearch(toHits(result, chunks, hybridQuery), chunks, result.denseSource(), result.trace());
    }

    private static List<EvidenceHit> toHits(RetrievalResult result, List<VideoChunk> chunks,
                                            HybridQuery hybridQuery) {
        Map<String, VideoChunk> chunksById = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) {
            if (chunk.chunkId() != null) {
                chunksById.put(chunk.chunkId(), chunk);
            }
        }

        List<EvidenceHit> hits = new ArrayList<>();
        for (RetrievalCandidate candidate : result.candidates()) {
            VideoChunk chunk = chunksById.get(candidate.chunkId());
            if (chunk == null) {
                continue;
            }
            hits.add(new EvidenceHit(
                    EvidenceBounds.of(chunk).startMs(), EvidenceBounds.of(chunk).endMs(), chunk.chunkId(),
                    chunk.segmentSummary(), chunk.keywords(), round(candidate.rerankerScore() == null
                            ? candidate.rrfScore() : candidate.rerankerScore()),
                    matchedTerms(textOf(chunk), hybridQuery.keywords()),
                    sourceOf(candidate.sources(), result.denseSource()),
                    candidate.rerankerScore() == null ? EvidenceHit.ScoreType.RRF : EvidenceHit.ScoreType.RERANKER_SIGMOID));
        }
        return hits;
    }

    private static QueryRewriter.Rewrite fallback(String query) {
        List<String> terms = simpleTerms(query);
        return new QueryRewriter.Rewrite(query, terms, terms);
    }

    /** 无 LLM 时的简单切词（对齐 QueryRewriter fallback）。 */
    private static List<String> simpleTerms(String query) {
        String safeQuery = query == null ? "" : query;
        List<String> terms = new ArrayList<>();
        for (String term : safeQuery.split("[\\s，。、；：,.!?;:]+")) {
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return terms.isEmpty() && !safeQuery.isBlank() ? List.of(safeQuery) : terms;
    }

    private static String sourceOf(Set<RetrievalChannel> sources, String denseSource) {
        List<String> names = new ArrayList<>();
        if (sources.contains(RetrievalChannel.DENSE)) names.add(denseSource);
        if (sources.contains(RetrievalChannel.BM25)) names.add("BM25");
        if (sources.contains(RetrievalChannel.OCR)) names.add("OCR");
        if (!sources.contains(RetrievalChannel.DENSE) && denseSource != null
                && (denseSource.contains("UNAVAILABLE") || denseSource.contains("LOCAL_COSINE"))) names.add(denseSource);
        return String.join("+", names);
    }

    /** 保留给已有评估测试使用；线上排序已不再直接混合原始分数。 */
    static double hitRate(String text, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        long hits = terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .filter(term -> lower.contains(term.toLowerCase(Locale.ROOT)))
                .count();
        long total = terms.stream().filter(term -> term != null && !term.isBlank()).count();
        return total == 0 ? 0 : (double) hits / total;
    }

    static List<String> matchedTerms(String text, List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .filter(term -> lower.contains(term.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String textOf(VideoChunk chunk) {
        return (chunk.segmentSummary() == null ? "" : chunk.segmentSummary() + " ")
                + String.join(" ", chunk.keywords() == null ? List.of() : chunk.keywords()) + " "
                + (chunk.transcript() == null ? "" : chunk.transcript());
    }

    private static double round(double score) {
        return Math.round(score * 1_000_000d) / 1_000_000d;
    }

    public record PreparedSearch(List<EvidenceHit> hits, List<VideoChunk> chunks, String denseSource,
                                 RetrievalTrace trace) {
        public PreparedSearch(List<EvidenceHit> hits, List<VideoChunk> chunks) {
            this(hits, chunks, "UNKNOWN", null);
        }
        public PreparedSearch(List<EvidenceHit> hits, List<VideoChunk> chunks, String denseSource) {
            this(hits, chunks, denseSource, null);
        }
    }
}

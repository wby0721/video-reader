package com.videoagent.service.retrieval;

import com.videoagent.dto.EvidenceBounds;
import com.videoagent.dto.RetrievalTrace;
import com.videoagent.dto.VideoChunk;
import com.videoagent.utils.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dense、BM25、OCR 三路召回与 RRF 融合的统一内核。 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    public static final int DENSE_LIMIT = 25;
    public static final int BM25_LIMIT = 25;
    public static final int OCR_LIMIT = 10;
    public static final int FUSED_LIMIT = 10;
    public static final int RERANK_LIMIT = 5;
    // 全局先精排整个三路候选并集，再由业务层做每视频配额，不能提前截成10条。
    public static final int GLOBAL_CANDIDATE_LIMIT = DENSE_LIMIT + BM25_LIMIT + OCR_LIMIT;
    private static final int RERANK_TEXT_CAP = 8_000;

    private final QdrantVectorStore vectorStore;
    private final Bm25IndexService bm25IndexService;
    private final EmbeddingClient embeddingClient;
    private final WeightedRrfFusion fusion;
    private final RerankerClient rerankerClient;

    public HybridRetrievalService(QdrantVectorStore vectorStore, Bm25IndexService bm25IndexService,
                                  EmbeddingClient embeddingClient, WeightedRrfFusion fusion,
                                  RerankerClient rerankerClient) {
        this.vectorStore = vectorStore;
        this.bm25IndexService = bm25IndexService;
        this.embeddingClient = embeddingClient;
        this.fusion = fusion;
        this.rerankerClient = rerankerClient;
    }

    public RetrievalResult retrieve(RetrievalScope scope, HybridQuery query,
                                    List<VideoChunk> chunks, int limit) {
        long started = System.nanoTime();
        if (chunks == null || chunks.isEmpty() || query.originalQuery().isBlank()) {
            return new RetrievalResult(List.of(), "NOT_RUN");
        }
        Set<String> allowedChunkIds = new HashSet<>();
        for (VideoChunk chunk : chunks) {
            if (chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
                allowedChunkIds.add(chunk.chunkId());
            }
        }

        DenseRanking dense = denseRanking(scope, query.semanticQuery(), chunks, allowedChunkIds);
        SparseRanking bm25 = bm25Ranking(scope, query.bm25Query(), allowedChunkIds);
        SparseRanking ocr = ocrRanking(scope, query.ocrQuery(), allowedChunkIds);

        Map<RetrievalChannel, List<String>> rankings = new EnumMap<>(RetrievalChannel.class);
        rankings.put(RetrievalChannel.DENSE, dense.chunkIds());
        rankings.put(RetrievalChannel.BM25, bm25.chunkIds());
        rankings.put(RetrievalChannel.OCR, ocr.chunkIds());
        List<RetrievalCandidate> fused = fusion.fuse(rankings,
                scope.type() == ScopeType.USER_ALL ? GLOBAL_CANDIDATE_LIMIT : FUSED_LIMIT);
        int maxFinal = scope.type() == ScopeType.USER_ALL ? GLOBAL_CANDIDATE_LIMIT : RERANK_LIMIT;
        int finalLimit = Math.min(maxFinal, Math.max(0, limit));
        RerankOutcome reranked = rerank(query.semanticQuery(), fused, chunks, finalLimit);
        String denseDiagnostics = diagnostics(dense.source(), bm25, ocr);
        RetrievalTrace trace = trace(query, chunks, dense, bm25, ocr, fused, reranked,
                denseDiagnostics, finalLimit, scope.type() == ScopeType.USER_ALL
                        ? GLOBAL_CANDIDATE_LIMIT : FUSED_LIMIT,
                (System.nanoTime() - started) / 1_000_000);
        return new RetrievalResult(reranked.candidates(), denseDiagnostics, trace);
    }

    /**
     * Agent 首轮多任务微批处理：查询向量和 Cross-Encoder 候选只各发一次 HTTP 请求。
     * Qdrant/BM25/OCR 仍逐查询独立召回和融合，避免不同任务的排名相互污染。
     */
    public List<RetrievalResult> retrieveBatch(RetrievalScope scope, List<HybridQuery> queries,
                                               List<VideoChunk> chunks, int limit) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        if (chunks == null || chunks.isEmpty()) {
            return queries.stream().map(query -> new RetrievalResult(List.of(), "NOT_RUN")).toList();
        }
        Set<String> allowedChunkIds = new HashSet<>();
        for (VideoChunk chunk : chunks) {
            if (chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
                allowedChunkIds.add(chunk.chunkId());
            }
        }

        boolean hasDenseIndex = chunks.stream().anyMatch(HybridRetrievalService::hasEmbedding);
        List<List<Float>> queryVectors = null;
        String unavailableSource = hasDenseIndex ? "EMBEDDING_UNAVAILABLE" : "DENSE_UNAVAILABLE";
        if (hasDenseIndex) {
            try {
                queryVectors = embeddingClient.embedAll(
                        queries.stream().map(HybridQuery::semanticQuery).toList());
            } catch (Exception e) {
                log.warn("批量查询 Embedding 不可用，Dense 通道跳过: {}", e.getMessage());
            }
        }

        List<BatchWork> works = new java.util.ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            HybridQuery query = queries.get(i);
            if (query.originalQuery().isBlank()) {
                works.add(new BatchWork(query, List.of(), "NOT_RUN", 0));
                continue;
            }
            DenseRanking dense = queryVectors == null
                    ? new DenseRanking(List.of(), unavailableSource)
                    : denseRanking(scope, queryVectors.get(i), chunks, allowedChunkIds);
            Map<RetrievalChannel, List<String>> rankings = new EnumMap<>(RetrievalChannel.class);
            rankings.put(RetrievalChannel.DENSE, dense.chunkIds());
            SparseRanking bm25 = bm25Ranking(scope, query.bm25Query(), allowedChunkIds);
            SparseRanking ocr = ocrRanking(scope, query.ocrQuery(), allowedChunkIds);
            rankings.put(RetrievalChannel.BM25, bm25.chunkIds());
            rankings.put(RetrievalChannel.OCR, ocr.chunkIds());
            int maxFinal = scope.type() == ScopeType.USER_ALL ? GLOBAL_CANDIDATE_LIMIT : RERANK_LIMIT;
            int finalLimit = Math.min(maxFinal, Math.max(0, limit));
            works.add(new BatchWork(query, fusion.fuse(rankings,
                    scope.type() == ScopeType.USER_ALL ? GLOBAL_CANDIDATE_LIMIT : FUSED_LIMIT),
                    diagnostics(dense.source(), bm25, ocr), finalLimit));
        }

        Map<String, VideoChunk> chunksById = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) {
            chunksById.put(chunk.chunkId(), chunk);
        }
        List<RerankerClient.BatchInput> inputs = works.stream()
                .map(work -> new RerankerClient.BatchInput(work.query().semanticQuery(),
                        documents(work.fused(), chunksById), Math.max(1, work.topN())))
                .toList();
        try {
            List<List<RerankerClient.RankedDocument>> rankedBatches = rerankerClient.rerankBatch(inputs);
            if (rankedBatches.size() != works.size()) {
                throw new IllegalStateException("Reranker 批量结果数量不匹配");
            }
            List<RetrievalResult> results = new java.util.ArrayList<>();
            for (int i = 0; i < works.size(); i++) {
                BatchWork work = works.get(i);
                List<RetrievalCandidate> ranked = work.topN() <= 0 || work.fused().isEmpty()
                        ? List.of()
                        : applyRanked(work.fused(), rankedBatches.get(i), work.topN());
                if (!work.fused().isEmpty() && ranked.isEmpty()) {
                    throw new IllegalStateException("Reranker 未返回有效候选");
                }
                results.add(new RetrievalResult(ranked, work.denseSource()));
            }
            return results;
        } catch (Exception e) {
            log.warn("批量 Reranker 不可用，全部任务降级为各自 RRF TopN: {}", e.getMessage());
            return works.stream()
                    .map(work -> new RetrievalResult(
                            work.fused().stream().limit(work.topN()).toList(), work.denseSource()))
                    .toList();
        }
    }

    private RerankOutcome rerank(String query, List<RetrievalCandidate> fused,
                                 List<VideoChunk> chunks, int topN) {
        if (fused.isEmpty() || topN <= 0) {
            return new RerankOutcome(List.of(), "NOT_RUN");
        }
        Map<String, VideoChunk> chunksById = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) {
            chunksById.put(chunk.chunkId(), chunk);
        }
        try {
            List<RerankerClient.RankedDocument> ranked = rerankerClient.rerank(
                    query, documents(fused, chunksById), topN);
            List<RetrievalCandidate> result = applyRanked(fused, ranked, topN);
            if (result.isEmpty()) {
                throw new IllegalStateException("Reranker 未返回有效候选");
            }
            return new RerankOutcome(result, "APPLIED");
        } catch (Exception e) {
            log.warn("Reranker 不可用，降级为 RRF Top {}: {}", topN, e.getMessage());
            return new RerankOutcome(fused.stream().limit(topN).toList(), "FALLBACK_RRF");
        }
    }

    private static RetrievalTrace trace(HybridQuery query, List<VideoChunk> chunks,
                                        DenseRanking dense, SparseRanking bm25, SparseRanking ocr,
                                        List<RetrievalCandidate> fused, RerankOutcome reranked,
                                        String denseSource, int rerankerTopK, int fusedTopK,
                                        long durationMs) {
        Map<String, VideoChunk> chunksById = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) {
            if (chunk.chunkId() != null) chunksById.put(chunk.chunkId(), chunk);
        }
        RetrievalTrace.Query tracedQuery = new RetrievalTrace.Query(
                query.originalQuery(), null, query.semanticQuery(), query.bm25Query(), query.ocrQuery(),
                query.keywords(), query.visualKeywords());
        RetrievalTrace.Parameters parameters = new RetrievalTrace.Parameters(
                DENSE_LIMIT, BM25_LIMIT, OCR_LIMIT, fusedTopK, rerankerTopK,
                WeightedRrfFusion.RRF_K,
                Map.of("DENSE", WeightedRrfFusion.DENSE_WEIGHT,
                        "BM25", WeightedRrfFusion.BM25_WEIGHT,
                        "OCR", WeightedRrfFusion.OCR_WEIGHT),
                null, null);
        return new RetrievalTrace(tracedQuery, parameters,
                recallTrace(dense.hits(), dense.source().equals("LOCAL_COSINE")
                        ? "COSINE_SIMILARITY_LOCAL" : "COSINE_SIMILARITY", chunksById),
                recallTrace(bm25.hits(), "LUCENE_BM25", chunksById),
                recallTrace(ocr.hits(), "LUCENE_BM25_OCR", chunksById),
                candidateTrace(fused, chunksById),
                candidateTrace(reranked.candidates(), chunksById),
                denseSource, reranked.status(), durationMs, null);
    }

    private static List<RetrievalTrace.RecallHit> recallTrace(
            List<ScoredChunk> hits, String scoreType, Map<String, VideoChunk> chunksById) {
        List<RetrievalTrace.RecallHit> result = new java.util.ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            ScoredChunk hit = hits.get(i);
            VideoChunk chunk = chunksById.get(hit.chunkId());
            if (chunk == null) continue;
            EvidenceBounds bounds = EvidenceBounds.of(chunk);
            result.add(new RetrievalTrace.RecallHit(i + 1, hit.chunkId(), hit.score(), scoreType,
                    bounds.startMs(), bounds.endMs(), chunk.segmentSummary(), chunk.keywords()));
        }
        return List.copyOf(result);
    }

    private static List<RetrievalTrace.CandidateHit> candidateTrace(
            List<RetrievalCandidate> candidates, Map<String, VideoChunk> chunksById) {
        List<RetrievalTrace.CandidateHit> result = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate candidate = candidates.get(i);
            VideoChunk chunk = chunksById.get(candidate.chunkId());
            if (chunk == null) continue;
            EvidenceBounds bounds = EvidenceBounds.of(chunk);
            result.add(new RetrievalTrace.CandidateHit(i + 1, candidate.chunkId(),
                    bounds.startMs(), bounds.endMs(), chunk.segmentSummary(), chunk.keywords(),
                    candidate.rrfScore(), candidate.denseRank(), candidate.bm25Rank(), candidate.ocrRank(),
                    contribution(candidate.denseRank(), WeightedRrfFusion.DENSE_WEIGHT),
                    contribution(candidate.bm25Rank(), WeightedRrfFusion.BM25_WEIGHT),
                    contribution(candidate.ocrRank(), WeightedRrfFusion.OCR_WEIGHT),
                    candidate.sources().stream().map(Enum::name).sorted().toList(),
                    candidate.rerankerScore()));
        }
        return List.copyOf(result);
    }

    private static Double contribution(Integer rank, double weight) {
        return rank == null ? null : weight / (WeightedRrfFusion.RRF_K + rank);
    }

    private static List<RerankerClient.DocumentInput> documents(
            List<RetrievalCandidate> fused, Map<String, VideoChunk> chunksById) {
        return fused.stream()
                .map(candidate -> chunksById.get(candidate.chunkId()))
                .filter(java.util.Objects::nonNull)
                .map(chunk -> new RerankerClient.DocumentInput(chunk.chunkId(), rerankerText(chunk)))
                .toList();
    }

    private static List<RetrievalCandidate> applyRanked(
            List<RetrievalCandidate> fused, List<RerankerClient.RankedDocument> ranked, int topN) {
        Map<String, RetrievalCandidate> candidatesById = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : fused) {
            candidatesById.put(candidate.chunkId(), candidate);
        }
        List<RetrievalCandidate> result = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RerankerClient.RankedDocument item : ranked) {
            if (!Double.isFinite(item.score()) || item.score() < 0 || item.score() > 1) {
                throw new IllegalStateException("Invalid sigmoid reranker score");
            }
            RetrievalCandidate candidate = candidatesById.get(item.id());
            if (candidate != null && seen.add(item.id())) {
                result.add(candidate.withRerankerScore(item.score()));
                if (result.size() == topN) {
                    break;
                }
            }
        }
        if (result.size() != Math.min(topN, fused.size())) {
            throw new IllegalStateException("Incomplete reranker response");
        }
        return result;
    }

    private DenseRanking denseRanking(RetrievalScope scope, String semanticQuery,
                                      List<VideoChunk> chunks, Set<String> allowedChunkIds) {
        if (semanticQuery == null || semanticQuery.isBlank()
                || chunks.stream().noneMatch(HybridRetrievalService::hasEmbedding)) {
            return new DenseRanking(List.of(), "DENSE_UNAVAILABLE");
        }
        final List<Float> queryVector;
        try {
            queryVector = embeddingClient.embed(semanticQuery);
        } catch (Exception e) {
            log.warn("查询 Embedding 不可用，Dense 通道跳过: {}", e.getMessage());
            return new DenseRanking(List.of(), "EMBEDDING_UNAVAILABLE");
        }

        return denseRanking(scope, queryVector, chunks, allowedChunkIds);
    }

    private DenseRanking denseRanking(RetrievalScope scope, List<Float> queryVector,
                                      List<VideoChunk> chunks, Set<String> allowedChunkIds) {
        try {
            List<ScoredChunk> hits = vectorStore.search(queryVector, DENSE_LIMIT, scope.userId(),
                            scope.contentHash(), RetrievalIndexService.INDEX_VERSION).stream()
                    .filter(hit -> hit.score() > 0)
                    .map(hit -> new ScoredChunk(resolveChunkId(hit, chunks), hit.score()))
                    .filter(hit -> allowedChunkIds.contains(hit.chunkId()))
                    .toList();
            return new DenseRanking(hits, "QDRANT");
        } catch (Exception e) {
            log.warn("Qdrant 检索不可用，Dense 通道降级本地余弦: {}", e.getMessage());
            List<ScoredChunk> hits = chunks.stream()
                    .filter(HybridRetrievalService::hasEmbedding)
                    .map(chunk -> new ScoredChunk(chunk.chunkId(), cosine(queryVector, chunk.embedding())))
                    .filter(item -> item.score() > 0)
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .limit(DENSE_LIMIT)
                    .toList();
            return new DenseRanking(hits, "LOCAL_COSINE");
        }
    }

    private SparseRanking bm25Ranking(RetrievalScope scope, String query, Set<String> allowedChunkIds) {
        try {
            return new SparseRanking(bm25IndexService.searchContent(scope.userId(), scope.contentHash(),
                            RetrievalIndexService.INDEX_VERSION, query, BM25_LIMIT).stream()
                    .filter(hit -> allowedChunkIds.contains(hit.chunkId()))
                    .map(hit -> new ScoredChunk(hit.chunkId(), hit.score()))
                    .toList(), true);
        } catch (Exception e) {
            log.warn("BM25 召回不可用，本次跳过: {}", e.getMessage());
            return new SparseRanking(List.of(), false);
        }
    }

    private SparseRanking ocrRanking(RetrievalScope scope, String query, Set<String> allowedChunkIds) {
        try {
            return new SparseRanking(bm25IndexService.searchOcr(scope.userId(), scope.contentHash(),
                            RetrievalIndexService.INDEX_VERSION, query, OCR_LIMIT).stream()
                    .filter(hit -> allowedChunkIds.contains(hit.chunkId()))
                    .map(hit -> new ScoredChunk(hit.chunkId(), hit.score()))
                    .toList(), true);
        } catch (Exception e) {
            log.warn("OCR 召回不可用，本次跳过: {}", e.getMessage());
            return new SparseRanking(List.of(), false);
        }
    }

    private static String resolveChunkId(QdrantVectorStore.Hit hit, List<VideoChunk> chunks) {
        if (hit.chunkId() != null && !hit.chunkId().isBlank()) {
            return hit.chunkId();
        }
        return hit.index() >= 0 && hit.index() < chunks.size()
                ? chunks.get(hit.index()).chunkId() : null;
    }

    private static boolean hasEmbedding(VideoChunk chunk) {
        return chunk.embedding() != null && !chunk.embedding().isEmpty();
    }

    private static String rerankerText(VideoChunk chunk) {
        String text = "[摘要] " + value(chunk.segmentSummary())
                + "\n[关键词] " + String.join(" ", chunk.keywords() == null ? List.of() : chunk.keywords())
                + "\n[转写] " + value(chunk.transcript())
                + "\n[画面] " + String.join(" ", chunk.visualTexts() == null ? List.of() : chunk.visualTexts());
        return text.length() <= RERANK_TEXT_CAP ? text : text.substring(0, RERANK_TEXT_CAP);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record DenseRanking(List<ScoredChunk> hits, String source) {
        private List<String> chunkIds() { return hits.stream().map(ScoredChunk::chunkId).toList(); }
    }
    private record SparseRanking(List<ScoredChunk> hits, boolean available) {
        private List<String> chunkIds() { return hits.stream().map(ScoredChunk::chunkId).toList(); }
    }
    private static String diagnostics(String dense, SparseRanking bm25, SparseRanking ocr) {
        return dense + (bm25.available() ? "" : "+BM25_UNAVAILABLE") + (ocr.available() ? "" : "+OCR_UNAVAILABLE");
    }
    private record ScoredChunk(String chunkId, double score) {}
    private record RerankOutcome(List<RetrievalCandidate> candidates, String status) {}
    private record BatchWork(HybridQuery query, List<RetrievalCandidate> fused,
                             String denseSource, int topN) {}
}

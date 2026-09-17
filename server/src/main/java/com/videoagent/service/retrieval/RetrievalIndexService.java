package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.EmbeddingClient;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检索索引服务：分块 → 摘要/关键词（LLM，可降级）→ Embedding → Qdrant upsert。
 *
 * <ul>
 *   <li>幂等：点 ID 由 contentHash 派生，重复索引覆盖不重复建；</li>
 *   <li>断点：索引状态 + 分块元数据（含向量、不含原始片段）落 Checkpoint，
 *       重启后直接从 Checkpoint 恢复（无需重跑 LLM/Embedding）；</li>
 *   <li>降级：Qdrant / Embedding 任一不可用时索引标记 FAILED，
 *       检索侧退化为本地关键词 + 本地余弦（Checkpoint 中的向量）。</li>
 * </ul>
 */
@Service
public class RetrievalIndexService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalIndexService.class);

    public static final String CP_RETRIEVAL_INDEX = "retrieval-index";
    public static final int INDEX_VERSION = 2;
    private static final int EMBEDDING_TEXT_CAP = 6_000;
    private static final TypeReference<List<VideoChunk>> CHUNK_LIST_TYPE = new TypeReference<>() {};

    private final QdrantVectorStore vectorStore;
    private final Bm25IndexService bm25IndexService;
    private final EmbeddingClient embeddingClient;
    private final ChunkEnricher enricher;
    private final CheckpointService checkpointService;
    private final LlmProvider llmProvider;

    /** 进程内缓存：Qdrant 不可用时的本地检索兜底。 */
    private final Map<Long, List<VideoChunk>> chunkCache = new ConcurrentHashMap<>();

    public RetrievalIndexService(QdrantVectorStore vectorStore, Bm25IndexService bm25IndexService,
                                 EmbeddingClient embeddingClient,
                                 ChunkEnricher enricher, CheckpointService checkpointService,
                                 LlmProvider llmProvider) {
        this.vectorStore = vectorStore;
        this.bm25IndexService = bm25IndexService;
        this.embeddingClient = embeddingClient;
        this.enricher = enricher;
        this.checkpointService = checkpointService;
        this.llmProvider = llmProvider;
    }

    /**
     * 建立检索索引（幂等）。上下文就绪后由 ingest 管线调用，也可由检索触发懒索引。
     *
     * @param userId 触发索引的用户（其自带的 LLM Key 优先用于摘要；内容级结果共享）
     * @return 索引后的分块列表（无论 Qdrant 成败，本地缓存始终可用）
     */
    public List<VideoChunk> index(Long mediaId, String contentHash, VideoContext context, Long userId) {
        return indexInternal(mediaId, contentHash, context, userId, true);
    }

    /** Global search must never initiate a generative LLM call, even on a cold index. */
    public List<VideoChunk> indexForSearch(Long mediaId, String contentHash, VideoContext context, Long userId) {
        return indexInternal(mediaId, contentHash, context, userId, false);
    }

    private List<VideoChunk> indexInternal(Long mediaId, String contentHash, VideoContext context, Long userId,
                                          boolean allowGenerativeEnrichment) {
        // 1) 内存缓存命中
        List<VideoChunk> cached = chunkCache.get(mediaId);
        if (cached != null) {
            return cached;
        }
        // 2) Checkpoint 恢复（重启后无需重跑 LLM/Embedding）
        Optional<List<VideoChunk>> fromCp = checkpointService.loadIfStage(
                mediaId, CP_RETRIEVAL_INDEX, "INDEXED", CHUNK_LIST_TYPE);
        if (fromCp.isPresent() && validCurrentVersion(fromCp.get())) {
            chunkCache.put(mediaId, fromCp.get());
            rehydrateVectorStore(userId, contentHash, mediaId, fromCp.get());
            rehydrateBm25(userId, contentHash, mediaId, fromCp.get());
            return fromCp.get();
        }

        List<VideoChunk> chunks = VideoChunkingService.chunk(
                context, userId, contentHash, INDEX_VERSION);
        if (chunks.isEmpty()) {
            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "EMPTY", List.of());
            return List.of();
        }

        try {
            // 3) 摘要与关键词（当前用户 LLM 优先，无 Key 自动抽取式降级）；分块并行，提速 4x
            LlmClient model = allowGenerativeEnrichment ? llmProvider.forUser(userId) : null;
            int n = chunks.size();
            int concurrency = Math.min(4, Math.max(1, n));
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<VideoChunk> enriched = new ArrayList<>(Collections.nCopies(n, null));
            try {
                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    VideoChunk c = chunks.get(i);
                    final int idx = i;
                    tasks.add(CompletableFuture.runAsync(() -> {
                        ChunkEnricher.Enriched e = enricher.enrich(c.transcript(), c.visualTexts(), model);
                        enriched.set(idx, c.withEnrichment(e.summary(), e.keywords()));
                    }, pool));
                }
                for (CompletableFuture<Void> t : tasks) {
                    t.join();
                }

                // 4) Embedding 批量请求；文本包含摘要、关键词、核心转写和 OCR。
                List<List<Float>> vectors = embeddingClient.embedAll(
                        enriched.stream().map(RetrievalIndexService::embeddingText).toList());
                for (int i = 0; i < n; i++) {
                    enriched.set(i, enriched.get(i).withEmbedding(vectors.get(i)));
                }
            } finally {
                pool.shutdown();
            }

            // 5) Qdrant upsert（失败抛异常 → 降级标记）
            upsertVectorStore(userId, contentHash, mediaId, enriched);
            rehydrateBm25(userId, contentHash, mediaId, enriched);

            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "INDEXED", enriched);
            chunkCache.put(mediaId, enriched);
            log.info("检索索引就绪 mediaId={} chunks={} collection={}", mediaId, enriched.size(), vectorStore.collection());
            return enriched;
        } catch (Exception e) {
            log.warn("索引写入失败（检索将降级为本地）: {}", e.getMessage());
            List<VideoChunk> fallback = enrichedOrEmpty(chunks);
            // Dense 链路失败不妨碍关键词/OCR 索引独立可用。
            rehydrateBm25(userId, contentHash, mediaId, fallback);
            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "FAILED", fallback);
            // FAILED 不写永久内存缓存；下次请求允许自动重试建索引。
            return fallback;
        }
    }

    private void rehydrateVectorStore(Long userId, String contentHash, Long mediaId,
                                      List<VideoChunk> chunks) {
        if (chunks.stream().anyMatch(c -> c.embedding() == null || c.embedding().isEmpty())) {
            return;
        }
        try {
            upsertVectorStore(userId, contentHash, mediaId, chunks);
        } catch (Exception e) {
            // Checkpoint 中已有向量，本地余弦仍然可用；Qdrant 恢复不阻断读取。
            log.warn("Qdrant 索引恢复失败（将使用本地余弦）mediaId={}: {}", mediaId, e.getMessage());
        }
    }

    private void rehydrateBm25(Long userId, String contentHash, Long mediaId,
                               List<VideoChunk> chunks) {
        try {
            bm25IndexService.index(userId, mediaId, contentHash, INDEX_VERSION, chunks);
        } catch (Exception e) {
            // 倒排索引是独立召回通道；失败时 Dense / 本地余弦仍可继续工作。
            log.warn("Lucene 索引写入失败 mediaId={}: {}", mediaId, e.getMessage());
        }
    }

    private void upsertVectorStore(Long userId, String contentHash, Long mediaId,
                                   List<VideoChunk> chunks) {
        vectorStore.ensureCollection();
        List<QdrantVectorStore.Point> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            VideoChunk c = chunks.get(i);
            points.add(new QdrantVectorStore.Point(i, c.chunkId(), c.startTime(), c.endTime(),
                    c.segmentSummary(), c.keywords(), c.embedding()));
        }
        vectorStore.upsert(userId, contentHash, mediaId, INDEX_VERSION, points);
    }

    /** 索引失败时仍保留分块元数据（摘要/转写/关键词），供本地关键词检索。 */
    private List<VideoChunk> enrichedOrEmpty(List<VideoChunk> chunks) {
        List<VideoChunk> out = new ArrayList<>();
        for (VideoChunk c : chunks) {
            String summary = c.segmentSummary() == null ? truncate(c.transcript()) : c.segmentSummary();
            out.add(c.withEnrichment(summary, c.keywords()));
        }
        return out;
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private static boolean validCurrentVersion(List<VideoChunk> chunks) {
        return chunks != null && !chunks.isEmpty() && chunks.stream().allMatch(c ->
                c.indexVersion() == INDEX_VERSION
                        && c.chunkId() != null && !c.chunkId().isBlank()
                        && c.embedding() != null && !c.embedding().isEmpty());
    }

    private static String embeddingText(VideoChunk c) {
        String text = "[摘要] " + value(c.segmentSummary())
                + "\n[关键词] " + String.join(" ", c.keywords() == null ? List.of() : c.keywords())
                + "\n[转写] " + value(c.transcript())
                + "\n[画面] " + String.join(" ", c.visualTexts() == null ? List.of() : c.visualTexts());
        return text.length() <= EMBEDDING_TEXT_CAP ? text : text.substring(0, EMBEDDING_TEXT_CAP);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    /** 获取已索引分块（未索引则返回空）。 */
    public List<VideoChunk> cached(Long mediaId) {
        return chunkCache.getOrDefault(mediaId, List.of());
    }

    public void invalidate(Long mediaId) {
        chunkCache.remove(mediaId);
    }

    /** 最后一条内容引用删除后，清理用户范围内的倒排索引。 */
    public void deleteContentIndex(Long userId, String contentHash) {
        try {
            bm25IndexService.deleteContent(userId, contentHash);
        } catch (Exception e) {
            log.warn("Lucene 索引删除失败 userId={} contentHash={}: {}",
                    userId, contentHash, e.getMessage());
        }
    }
}

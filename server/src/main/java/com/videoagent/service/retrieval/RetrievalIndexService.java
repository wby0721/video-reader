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
    private static final TypeReference<List<VideoChunk>> CHUNK_LIST_TYPE = new TypeReference<>() {};

    private final QdrantVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final ChunkEnricher enricher;
    private final CheckpointService checkpointService;
    private final LlmProvider llmProvider;

    /** 进程内缓存：Qdrant 不可用时的本地检索兜底。 */
    private final Map<Long, List<VideoChunk>> chunkCache = new ConcurrentHashMap<>();

    public RetrievalIndexService(QdrantVectorStore vectorStore, EmbeddingClient embeddingClient,
                                 ChunkEnricher enricher, CheckpointService checkpointService,
                                 LlmProvider llmProvider) {
        this.vectorStore = vectorStore;
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
        // 1) 内存缓存命中
        List<VideoChunk> cached = chunkCache.get(mediaId);
        if (cached != null) {
            return cached;
        }
        // 2) Checkpoint 恢复（重启后无需重跑 LLM/Embedding）
        Optional<List<VideoChunk>> fromCp = checkpointService.load(mediaId, CP_RETRIEVAL_INDEX, CHUNK_LIST_TYPE);
        if (fromCp.isPresent() && !fromCp.get().isEmpty()) {
            chunkCache.put(mediaId, fromCp.get());
            return fromCp.get();
        }

        List<VideoChunk> chunks = VideoChunkingService.chunk(context);
        if (chunks.isEmpty()) {
            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "EMPTY", List.of());
            return List.of();
        }

        try {
            // 3) 摘要与关键词（当前用户 LLM 优先，无 Key 自动抽取式降级）；分块并行，提速 4x
            LlmClient model = llmProvider.forUser(userId);
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
                        enriched.set(idx, VideoChunk.of(c.startTime(), c.endTime(), e.summary(), e.keywords(),
                                c.transcript(), c.visualTexts(), c.rawSegments(), null));
                    }, pool));
                }
                for (CompletableFuture<Void> t : tasks) {
                    t.join();
                }

                // 4) Embedding（本地 BGE-M3；失败抛异常 → 整段降级标记，关键词检索仍可用）并行
                tasks.clear();
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    tasks.add(CompletableFuture.runAsync(() -> {
                        VideoChunk c = enriched.get(idx);
                        List<Float> vector = embeddingClient.embed(
                                c.segmentSummary() + " " + String.join(" ", c.keywords()));
                        enriched.set(idx, VideoChunk.of(c.startTime(), c.endTime(), c.segmentSummary(), c.keywords(),
                                c.transcript(), c.visualTexts(), c.rawSegments(), vector));
                    }, pool));
                }
                for (CompletableFuture<Void> t : tasks) {
                    t.join();
                }
            } finally {
                pool.shutdown();
            }

            // 5) Qdrant upsert（失败抛异常 → 降级标记）
            vectorStore.ensureCollection();
            List<QdrantVectorStore.Point> points = new ArrayList<>();
            for (int i = 0; i < enriched.size(); i++) {
                VideoChunk c = enriched.get(i);
                points.add(new QdrantVectorStore.Point(i, c.startTime(), c.endTime(),
                        c.segmentSummary(), c.keywords(), c.embedding()));
            }
            vectorStore.upsert(contentHash, mediaId, points);

            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "INDEXED", enriched);
            chunkCache.put(mediaId, enriched);
            log.info("检索索引就绪 mediaId={} chunks={} collection={}", mediaId, enriched.size(), vectorStore.collection());
            return enriched;
        } catch (Exception e) {
            log.warn("索引写入失败（检索将降级为本地）: {}", e.getMessage());
            checkpointService.save(mediaId, CP_RETRIEVAL_INDEX, "FAILED", enrichedOrEmpty(chunks));
            chunkCache.put(mediaId, chunkCache.getOrDefault(mediaId, enrichedOrEmpty(chunks)));
            return chunkCache.get(mediaId);
        }
    }

    /** 索引失败时仍保留分块元数据（摘要/转写/关键词），供本地关键词检索。 */
    private List<VideoChunk> enrichedOrEmpty(List<VideoChunk> chunks) {
        List<VideoChunk> out = new ArrayList<>();
        for (VideoChunk c : chunks) {
            out.add(VideoChunk.of(c.startTime(), c.endTime(),
                    c.segmentSummary() == null ? truncate(c.transcript()) : c.segmentSummary(),
                    c.keywords(), c.transcript(), c.visualTexts(), c.rawSegments(), c.embedding()));
        }
        return out;
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    /** 获取已索引分块（未索引则返回空）。 */
    public List<VideoChunk> cached(Long mediaId) {
        return chunkCache.getOrDefault(mediaId, List.of());
    }

    public void invalidate(Long mediaId) {
        chunkCache.remove(mediaId);
    }
}

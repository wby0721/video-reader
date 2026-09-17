package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.ChatEvidence;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.eval.RagRetrievalMetrics;
import com.videoagent.support.RagFixtureLoader;
import com.videoagent.utils.EmbeddingClient;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实模型与真实 Qdrant 的六题端到端检索测试。
 *
 * <p>仅在 {@code -Drag.real-models=true} 时运行。它真实调用 BGE-M3(8000)、
 * Qdrant(6333)、Lucene BM25/OCR 和 bge-reranker-v2-m3(8003)，不允许使用这些
 * 环节的 Mock。Checkpoint 与 LLM 仍在测试边界内隔离，因为固定黄金集已经提供
 * 独立查询，本测试不验证数据库持久化或生成式回答。</p>
 */
@EnabledIfSystemProperty(named = "rag.real-models", matches = "true")
class RagRealModelsEndToEndTest {

    private static final String EMBEDDING_URL = "http://127.0.0.1:8000";
    private static final String QDRANT_URL = "http://127.0.0.1:6333";
    private static final String RERANKER_URL = "http://127.0.0.1:8003";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sixGoldenQueriesUseRealEmbeddingQdrantAndReranker() throws Exception {
        AppProperties properties = properties();
        EmbeddingClient embeddingClient = new EmbeddingClient(properties);
        QdrantVectorStore vectorStore = new QdrantVectorStore(properties);
        RerankerClient rerankerClient = new RerankerClient(properties);
        RagFixtureLoader.GoldenSet goldenSet = RagFixtureLoader.loadGoldenSet(mapper);
        StringBuilder report = new StringBuilder()
                .append("RAG 六题真实模型全流程报告\n")
                .append("Embedding: BGE-M3 @ ").append(EMBEDDING_URL).append("\n")
                .append("Vector store: Qdrant @ ").append(QDRANT_URL).append("\n")
                .append("Sparse retrieval: Lucene BM25 + OCR\n")
                .append("Fusion: Weighted RRF Top10\n")
                .append("Reranker: bge-reranker-v2-m3 @ ").append(RERANKER_URL).append("\n")
                .append("Generation LLM: NOT USED\n\n");

        vectorStore.ensureCollection();
        for (RagFixtureLoader.GoldenVideo goldenVideo : goldenSet.videos()) {
            runVideo(goldenVideo, embeddingClient, vectorStore, rerankerClient, report);
        }

        Path output = Path.of("target", "rag-test-reports",
                "real-models-six-query-full-flow.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("真实模型全流程报告：" + output.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private void runVideo(RagFixtureLoader.GoldenVideo goldenVideo,
                          EmbeddingClient embeddingClient,
                          QdrantVectorStore vectorStore,
                          RerankerClient rerankerClient,
                          StringBuilder report) throws Exception {
        RagFixtureLoader.Fixture fixture = switch (goldenVideo.fixture()) {
            case "20min" -> RagFixtureLoader.load20Minutes(mapper);
            case "40min" -> RagFixtureLoader.load40Minutes(mapper);
            default -> throw new AssertionError("未知 fixture: " + goldenVideo.fixture());
        };
        VideoContext context = fixture.align(
                String.valueOf(goldenVideo.mediaId()), "真实模型 RAG 检索评估");
        CheckpointService checkpointService = mock(CheckpointService.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        AtomicReference<List<VideoChunk>> cachedChunks = new AtomicReference<>();
        when(checkpointService.loadIfStage(
                eq(goldenVideo.mediaId()), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), any(TypeReference.class))).thenAnswer(invocation ->
                Optional.ofNullable(cachedChunks.get()));
        doAnswer(invocation -> {
            if (RetrievalIndexService.CP_RETRIEVAL_INDEX.equals(invocation.getArgument(1))) {
                cachedChunks.set(List.copyOf(invocation.getArgument(3)));
            }
            return null;
        }).when(checkpointService).save(
                eq(goldenVideo.mediaId()), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), any());
        when(llmProvider.forUser(goldenVideo.userId())).thenReturn(null);

        try (Bm25IndexService bm25 = new Bm25IndexService(new ByteBuffersDirectory())) {
            RetrievalIndexService indexService = new RetrievalIndexService(
                    vectorStore, bm25, embeddingClient, new ChunkEnricher(mapper),
                    checkpointService, llmProvider);
            WeightedRrfFusion fusion = new WeightedRrfFusion();
            HybridRetrievalService hybrid = new HybridRetrievalService(
                    vectorStore, bm25, embeddingClient, fusion, rerankerClient);
            VideoEvidenceRetrievalService retrieval = new VideoEvidenceRetrievalService(
                    indexService, hybrid, new QueryRewriter(mapper), llmProvider);

            long indexStart = System.nanoTime();
            List<VideoChunk> chunks = indexService.index(
                    goldenVideo.mediaId(), goldenVideo.contentHash(), context, goldenVideo.userId());
            long indexMs = elapsedMs(indexStart);
            assertThat(chunks).isNotEmpty().allMatch(chunk ->
                    chunk.embedding() != null && chunk.embedding().size() == 1024);
            Map<String, VideoChunk> chunksById = chunks.stream().collect(Collectors.toMap(
                    VideoChunk::chunkId, Function.identity(), (left, right) -> left,
                    LinkedHashMap::new));

            report.append("============================================================\n")
                    .append(goldenVideo.fixture()).append(" / ").append(goldenVideo.title())
                    .append(" / chunks=").append(chunks.size())
                    .append(" / realIndexMs=").append(indexMs).append("\n")
                    .append("============================================================\n\n");

            for (RagFixtureLoader.GoldenQuestion question : goldenVideo.questions()) {
                String query = question.standaloneQuery();
                List<String> terms = terms(query);
                long queryStart = System.nanoTime();
                List<EvidenceHit> finalHits = retrieval.searchNoRewrite(
                        goldenVideo.mediaId(), goldenVideo.contentHash(), context,
                        query, 5, goldenVideo.userId());
                long queryMs = elapsedMs(queryStart);

                List<Float> queryVector = embeddingClient.embed(query);
                List<QdrantVectorStore.Hit> dense = vectorStore.search(
                        queryVector, HybridRetrievalService.DENSE_LIMIT,
                        goldenVideo.userId(), goldenVideo.contentHash(),
                        RetrievalIndexService.INDEX_VERSION);
                List<Bm25IndexService.Hit> bm25Hits = bm25.searchContent(
                        goldenVideo.userId(), goldenVideo.contentHash(),
                        RetrievalIndexService.INDEX_VERSION, String.join(" ", terms),
                        HybridRetrievalService.BM25_LIMIT);
                List<Bm25IndexService.Hit> ocrHits = bm25.searchOcr(
                        goldenVideo.userId(), goldenVideo.contentHash(),
                        RetrievalIndexService.INDEX_VERSION, String.join(" ", terms),
                        HybridRetrievalService.OCR_LIMIT);
                Map<RetrievalChannel, List<String>> rankings = new EnumMap<>(RetrievalChannel.class);
                rankings.put(RetrievalChannel.DENSE,
                        dense.stream().map(QdrantVectorStore.Hit::chunkId).toList());
                rankings.put(RetrievalChannel.BM25,
                        bm25Hits.stream().map(Bm25IndexService.Hit::chunkId).toList());
                rankings.put(RetrievalChannel.OCR,
                        ocrHits.stream().map(Bm25IndexService.Hit::chunkId).toList());
                List<RetrievalCandidate> fused = fusion.fuse(
                        rankings, HybridRetrievalService.FUSED_LIMIT);

                Map<String, Integer> gains = question.expectedTop7().stream().collect(
                        Collectors.toMap(RagFixtureLoader.GoldenChunk::chunkId,
                                RagFixtureLoader.GoldenChunk::relevance));
                RagRetrievalMetrics.Report denseMetrics = metrics(
                        dense.stream().map(QdrantVectorStore.Hit::chunkId).toList(), gains, 25);
                RagRetrievalMetrics.Report bm25Metrics = metrics(
                        bm25Hits.stream().map(Bm25IndexService.Hit::chunkId).toList(), gains, 25);
                RagRetrievalMetrics.Report ocrMetrics = metrics(
                        ocrHits.stream().map(Bm25IndexService.Hit::chunkId).toList(), gains, 10);
                RagRetrievalMetrics.Report rrfMetrics = metrics(
                        fused.stream().map(RetrievalCandidate::chunkId).toList(), gains, 10);
                RagRetrievalMetrics.Report finalMetrics = metrics(
                        finalHits.stream().map(EvidenceHit::chunkId).toList(), gains, 5);

                assertThat(dense).as("真实 Qdrant Dense 结果").isNotEmpty();
                assertThat(finalHits).as("真实 Reranker 结果").isNotEmpty();
                assertThat(finalHits).allMatch(hit -> hit.source().contains("QDRANT"));
                assertThat(rrfMetrics.hitAtK()).as("%s Q%d RRF Hit@10",
                        goldenVideo.fixture(), question.turn()).isEqualTo(1);
                assertThat(finalMetrics.hitAtK()).as("%s Q%d Reranker Hit@5",
                        goldenVideo.fixture(), question.turn()).isEqualTo(1);

                report.append("---------------- Q").append(question.turn()).append(" ----------------\n")
                        .append("用户追问: ").append(question.userQuestion()).append("\n")
                        .append("独立查询: ").append(query).append("\n")
                        .append("realQueryMs: ").append(queryMs).append("\n")
                        .append("Dense: ").append(format(denseMetrics)).append("\n")
                        .append("BM25: ").append(format(bm25Metrics)).append("\n")
                        .append("OCR: ").append(format(ocrMetrics)).append("\n")
                        .append("RRF: ").append(format(rrfMetrics)).append("\n")
                        .append("Real Reranker: ").append(format(finalMetrics)).append("\n\n");
                appendDense(report, dense, chunksById);
                appendSparse(report, "BM25 Top25", bm25Hits, chunksById);
                appendSparse(report, "OCR Top10", ocrHits, chunksById);
                appendRrf(report, fused, chunksById);
                appendFinal(report, finalHits);

                List<ChatEvidence> evidence = new ChatEvidenceService().build(
                        goldenVideo.mediaId(), chunks, finalHits);
                report.append("完整 EvidencePack:\n")
                        .append(new ChatEvidenceService().toPromptText(
                                goldenVideo.title(), evidence)).append("\n\n");
            }
        }
    }

    private static RagRetrievalMetrics.Report metrics(
            List<String> ids, Map<String, Integer> gains, int k) {
        return RagRetrievalMetrics.evaluate(ids, gains, k);
    }

    private static String format(RagRetrievalMetrics.Report value) {
        return "Hit=" + value.hitAtK() + ", Precision=" + value.precisionAtK()
                + ", Recall=" + value.recallAtK() + ", MRR=" + value.mrr()
                + ", nDCG=" + value.ndcgAtK();
    }

    private static void appendDense(StringBuilder report, List<QdrantVectorStore.Hit> hits,
                                    Map<String, VideoChunk> chunks) {
        report.append("Dense/Qdrant Top25:\n");
        for (int i = 0; i < hits.size(); i++) {
            QdrantVectorStore.Hit hit = hits.get(i);
            appendLine(report, i, hit.chunkId(), hit.score(), chunks.get(hit.chunkId()));
        }
        report.append('\n');
    }

    private static void appendSparse(StringBuilder report, String title,
                                     List<Bm25IndexService.Hit> hits,
                                     Map<String, VideoChunk> chunks) {
        report.append(title).append(":\n");
        for (int i = 0; i < hits.size(); i++) {
            Bm25IndexService.Hit hit = hits.get(i);
            appendLine(report, i, hit.chunkId(), hit.score(), chunks.get(hit.chunkId()));
        }
        report.append('\n');
    }

    private static void appendRrf(StringBuilder report, List<RetrievalCandidate> hits,
                                  Map<String, VideoChunk> chunks) {
        report.append("Weighted RRF Top10:\n");
        for (int i = 0; i < hits.size(); i++) {
            RetrievalCandidate hit = hits.get(i);
            appendLine(report, i, hit.chunkId(), hit.rrfScore(), chunks.get(hit.chunkId()));
        }
        report.append('\n');
    }

    private static void appendFinal(StringBuilder report, List<EvidenceHit> hits) {
        report.append("真实 bge-reranker-v2-m3 Top5:\n");
        for (int i = 0; i < hits.size(); i++) {
            EvidenceHit hit = hits.get(i);
            report.append(String.format("[%02d] chunkId=%s time=%d~%d score=%.8f source=%s%n",
                    i + 1, hit.chunkId(), hit.startMs(), hit.endMs(), hit.score(), hit.source()));
        }
        report.append('\n');
    }

    private static void appendLine(StringBuilder report, int index, String id,
                                   double score, VideoChunk chunk) {
        report.append(String.format("[%02d] chunkId=%s time=%d~%d score=%.8f%n",
                index + 1, id, chunk.startTime(), chunk.endTime(), score));
    }

    private static List<String> terms(String query) {
        return java.util.Arrays.stream(query.split("[\\s，。、；：,.!?;:]+"))
                .filter(term -> !term.isBlank()).toList();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static AppProperties properties() {
        return new AppProperties(
                null, null, null,
                new AppProperties.Qdrant(QDRANT_URL),
                null, null,
                new AppProperties.Ai(
                        null,
                        new AppProperties.Ai.Embedding(
                                EMBEDDING_URL, "", "bge-m3", 1024),
                        null,
                        null,
                        new AppProperties.Ai.Reranker(RERANKER_URL)),
                null, null, null);
    }
}

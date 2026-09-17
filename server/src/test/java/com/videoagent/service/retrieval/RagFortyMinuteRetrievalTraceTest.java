package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 40 分钟 fixture 的三轮无 LLM 检索审计测试。
 *
 * <p>报告完整记录 Dense/BM25/OCR 粗召回、RRF Top10、Reranker Top5 和最终
 * EvidencePack。Lucene 使用内存目录；Embedding、Qdrant、Reranker 只替换远程
 * I/O，Checkpoint 不落库，因此不会触碰任何开发或生产基础设施。</p>
 */
class RagFortyMinuteRetrievalTraceTest {

    private static final long MEDIA_ID = 900_040L;
    private static final long USER_ID = 7L;
    private static final String CONTENT_HASH =
            "77779ccb824803476e788e7e73594dcf92cd4e29a77a828e4c81709d6bce6c10";

    private final ObjectMapper mapper = new ObjectMapper();
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankerClient rerankerClient = mock(RerankerClient.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final AtomicReference<List<QdrantVectorStore.Point>> vectorPoints =
            new AtomicReference<>(List.of());
    private final AtomicReference<RerankTrace> latestRerank = new AtomicReference<>();

    private Bm25IndexService bm25IndexService;
    private VideoEvidenceRetrievalService retrievalService;
    private VideoContext context;
    private List<VideoChunk> chunks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        RagFixtureLoader.Fixture fixture = RagFixtureLoader.load40Minutes(mapper);
        context = fixture.align(String.valueOf(MEDIA_ID), "验证网络排障课程检索效果");
        bm25IndexService = new Bm25IndexService(new ByteBuffersDirectory());
        configureDeterministicRemoteBoundaries();

        when(checkpointService.loadIfStage(
                eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX), eq("INDEXED"),
                any(TypeReference.class))).thenReturn(Optional.empty());
        when(llmProvider.forUser(USER_ID)).thenReturn(null);

        RetrievalIndexService indexService = new RetrievalIndexService(
                vectorStore,
                bm25IndexService,
                embeddingClient,
                new ChunkEnricher(mapper),
                checkpointService,
                llmProvider);
        HybridRetrievalService hybrid = new HybridRetrievalService(
                vectorStore,
                bm25IndexService,
                embeddingClient,
                new WeightedRrfFusion(),
                rerankerClient);
        retrievalService = new VideoEvidenceRetrievalService(
                indexService, hybrid, new QueryRewriter(mapper), llmProvider);
        chunks = indexService.index(MEDIA_ID, CONTENT_HASH, context, USER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        bm25IndexService.close();
    }

    @Test
    void threeRoundRetrievalWritesCoarseFineAndCompleteEvidencePack() throws Exception {
        assertThat(context.segments()).hasSize(100);
        assertThat(chunks).hasSize(32);
        assertThat(vectorPoints.get()).hasSize(32);

        RagFixtureLoader.GoldenVideo goldenVideo = RagFixtureLoader.goldenVideo(mapper, "40min");
        List<String> queries = goldenVideo.questions().stream()
                .map(RagFixtureLoader.GoldenQuestion::standaloneQuery)
                .toList();
        StringBuilder report = new StringBuilder()
                .append("40分钟视频三轮检索全链路报告（不调用LLM）\n")
                .append("Chunk：32个；窗口90秒；重叠15秒\n")
                .append("粗召回：Dense Top25 + BM25 Top25 + OCR Top10\n")
                .append("融合：加权RRF Top10；精排：Reranker Top5\n\n");

        for (int round = 0; round < queries.size(); round++) {
            String query = queries.get(round);
            List<EvidenceHit> finalHits = retrievalService.searchNoRewrite(
                    MEDIA_ID, CONTENT_HASH, context, query, 5, USER_ID);
            RerankTrace rerankTrace = latestRerank.get();
            assertThat(rerankTrace).isNotNull();
            assertThat(rerankTrace.query()).isEqualTo(query);

            List<QdrantVectorStore.Hit> dense = searchVectors(semanticVector(query), 25);
            List<Bm25IndexService.Hit> bm25 = bm25IndexService.searchContent(
                    USER_ID, CONTENT_HASH, RetrievalIndexService.INDEX_VERSION, query, 25);
            List<Bm25IndexService.Hit> ocr = bm25IndexService.searchOcr(
                    USER_ID, CONTENT_HASH, RetrievalIndexService.INDEX_VERSION, query, 10);
            List<RetrievalCandidate> fused = fuse(dense, bm25, ocr);
            Map<String, Integer> gains = goldenVideo.questions().get(round).expectedTop7().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            RagFixtureLoader.GoldenChunk::chunkId,
                            RagFixtureLoader.GoldenChunk::relevance));
            RagRetrievalMetrics.Report denseMetrics = RagRetrievalMetrics.evaluate(
                    dense.stream().map(QdrantVectorStore.Hit::chunkId).toList(), gains, 25);
            RagRetrievalMetrics.Report bm25Metrics = RagRetrievalMetrics.evaluate(
                    bm25.stream().map(Bm25IndexService.Hit::chunkId).toList(), gains, 25);
            RagRetrievalMetrics.Report ocrMetrics = RagRetrievalMetrics.evaluate(
                    ocr.stream().map(Bm25IndexService.Hit::chunkId).toList(), gains, 10);
            RagRetrievalMetrics.Report rrfMetrics = RagRetrievalMetrics.evaluate(
                    fused.stream().map(RetrievalCandidate::chunkId).toList(), gains, 10);
            RagRetrievalMetrics.Report rerankerMetrics = RagRetrievalMetrics.evaluate(
                    finalHits.stream().map(EvidenceHit::chunkId).toList(), gains, 5);

            assertThat(rerankTrace.documents()).extracting(RerankerClient.DocumentInput::id)
                    .containsExactlyElementsOf(fused.stream().map(RetrievalCandidate::chunkId).toList());
            assertThat(finalHits).extracting(EvidenceHit::chunkId)
                    .containsExactlyElementsOf(rerankTrace.ranked().stream()
                            .map(RerankerClient.RankedDocument::id).toList());
            assertThat(rrfMetrics.hitAtK()).as("第%d轮 RRF Hit@10", round + 1).isEqualTo(1);
            assertThat(rerankerMetrics.hitAtK()).as("第%d轮 Reranker Hit@5", round + 1).isEqualTo(1);

            List<ChatEvidence> evidence = new ChatEvidenceService().build(
                    MEDIA_ID, chunks, finalHits);
            report.append("============================================================\n")
                    .append("第").append(round + 1).append("轮\n")
                    .append("独立检索问题：").append(query).append("\n")
                    .append("指标：Dense=").append(formatMetrics(denseMetrics))
                    .append("；BM25=").append(formatMetrics(bm25Metrics))
                    .append("；OCR=").append(formatMetrics(ocrMetrics))
                    .append("；RRF=").append(formatMetrics(rrfMetrics))
                    .append("；Reranker=").append(formatMetrics(rerankerMetrics)).append("\n")
                    .append("============================================================\n\n");
            appendDense(report, dense);
            appendLucene(report, "BM25 粗召回 Top25", bm25);
            appendLucene(report, "OCR 粗召回 Top10", ocr);
            appendFused(report, fused);
            appendReranked(report, finalHits);
            report.append("\n---------------- 最终完整 EvidencePack ----------------\n")
                    .append(new ChatEvidenceService().toPromptText(
                            "网络分层与故障排查测试视频", evidence))
                    .append('\n');

            String evidenceText = evidence.stream().map(ChatEvidence::quote)
                    .reduce("", (left, right) -> left + "\n" + right);
            if (round == 0) {
                assertThat(evidenceText).contains("反向代理", "五百零二", "上游");
            } else {
                assertThat(finalHits.getFirst().startMs()).isLessThan(2_100_000L);
                assertThat(finalHits.getFirst().endMs()).isGreaterThan(2_040_000L);
                assertThat(evidenceText).contains("主机可以 ping 通", "目标端口没有进程监听", "健康检查");
            }
        }

        Path output = Path.of("target", "rag-test-reports",
                "40min-three-round-retrieval-trace.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("40分钟检索审计报告：" + output.toAbsolutePath());
        verify(checkpointService).save(
                eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), anyList());
    }

    private List<RetrievalCandidate> fuse(
            List<QdrantVectorStore.Hit> dense,
            List<Bm25IndexService.Hit> bm25,
            List<Bm25IndexService.Hit> ocr) {
        Map<RetrievalChannel, List<String>> rankings = new EnumMap<>(RetrievalChannel.class);
        rankings.put(RetrievalChannel.DENSE,
                dense.stream().map(QdrantVectorStore.Hit::chunkId).toList());
        rankings.put(RetrievalChannel.BM25,
                bm25.stream().map(Bm25IndexService.Hit::chunkId).toList());
        rankings.put(RetrievalChannel.OCR,
                ocr.stream().map(Bm25IndexService.Hit::chunkId).toList());
        return new WeightedRrfFusion().fuse(rankings, HybridRetrievalService.FUSED_LIMIT);
    }

    private static String formatMetrics(RagRetrievalMetrics.Report report) {
        return "Hit=" + report.hitAtK() + ",Precision=" + report.precisionAtK()
                + ",Recall=" + report.recallAtK()
                + ",MRR=" + report.mrr() + ",nDCG=" + report.ndcgAtK();
    }

    private void appendDense(StringBuilder report, List<QdrantVectorStore.Hit> hits) {
        report.append("---------------- Dense 粗召回 Top25 ----------------\n");
        for (int i = 0; i < hits.size(); i++) {
            QdrantVectorStore.Hit hit = hits.get(i);
            appendCandidate(report, i + 1, hit.chunkId(), hit.score(), "cosine");
        }
        report.append('\n');
    }

    private void appendLucene(StringBuilder report, String title, List<Bm25IndexService.Hit> hits) {
        report.append("---------------- ").append(title).append(" ----------------\n");
        for (int i = 0; i < hits.size(); i++) {
            Bm25IndexService.Hit hit = hits.get(i);
            appendCandidate(report, i + 1, hit.chunkId(), hit.score(), "luceneScore");
        }
        report.append('\n');
    }

    private void appendFused(StringBuilder report, List<RetrievalCandidate> candidates) {
        report.append("---------------- RRF 粗排融合 Top10 ----------------\n");
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate candidate = candidates.get(i);
            VideoChunk chunk = chunk(candidate.chunkId());
            report.append(String.format(
                    "[%02d] chunkId=%s  time=%dms~%dms  rrf=%.8f  denseRank=%s  bm25Rank=%s  ocrRank=%s  channels=%s%n",
                    i + 1, candidate.chunkId(), chunk.startTime(), chunk.endTime(),
                    candidate.rrfScore(), candidate.denseRank(), candidate.bm25Rank(),
                    candidate.ocrRank(), candidate.sources()));
            appendExcerpt(report, chunk);
        }
        report.append('\n');
    }

    private void appendReranked(StringBuilder report, List<EvidenceHit> hits) {
        report.append("---------------- Reranker 精排 Top5 ----------------\n");
        for (int i = 0; i < hits.size(); i++) {
            EvidenceHit hit = hits.get(i);
            VideoChunk chunk = chunk(hit.chunkId());
            report.append(String.format(
                    "[%02d] chunkId=%s  time=%dms~%dms  reranker=%.6f  source=%s%n",
                    i + 1, hit.chunkId(), hit.startMs(), hit.endMs(), hit.score(), hit.source()));
            report.append("摘要：").append(value(chunk.segmentSummary())).append('\n')
                    .append("关键词：").append(String.join("，", chunk.keywords())).append('\n')
                    .append("完整ASR：").append(value(chunk.transcript())).append('\n')
                    .append("完整OCR：").append(String.join("；", chunk.visualTexts())).append("\n\n");
        }
    }

    private void appendCandidate(StringBuilder report, int rank, String chunkId,
                                 double score, String scoreName) {
        VideoChunk chunk = chunk(chunkId);
        report.append(String.format("[%02d] chunkId=%s  time=%dms~%dms  %s=%.6f%n",
                rank, chunkId, chunk.startTime(), chunk.endTime(), scoreName, score));
        appendExcerpt(report, chunk);
    }

    private static void appendExcerpt(StringBuilder report, VideoChunk chunk) {
        report.append("摘要：").append(value(chunk.segmentSummary())).append('\n')
                .append("关键词：").append(String.join("，", chunk.keywords())).append('\n')
                .append("ASR摘录：").append(clip(chunk.transcript(), 360)).append('\n')
                .append("OCR摘录：").append(clip(String.join("；", chunk.visualTexts()), 240))
                .append("\n\n");
    }

    private VideoChunk chunk(String id) {
        return chunks.stream().filter(item -> id.equals(item.chunkId()))
                .findFirst().orElseThrow();
    }

    private void configureDeterministicRemoteBoundaries() {
        when(embeddingClient.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(RagFortyMinuteRetrievalTraceTest::semanticVector).toList();
        });
        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                semanticVector(invocation.getArgument(0)));
        doAnswer(invocation -> {
            List<QdrantVectorStore.Point> points = invocation.getArgument(4);
            vectorPoints.set(List.copyOf(points));
            return null;
        }).when(vectorStore).upsert(anyLong(), anyString(), anyLong(), anyInt(), anyList());
        when(vectorStore.search(anyList(), anyInt(), anyLong(), any(), anyInt()))
                .thenAnswer(invocation -> searchVectors(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(rerankerClient.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            List<RerankerClient.DocumentInput> documents = invocation.getArgument(1);
            int topN = invocation.getArgument(2);
            List<RerankerClient.RankedDocument> ranked = rerank(query, documents, topN);
            latestRerank.set(new RerankTrace(query, List.copyOf(documents), ranked));
            return ranked;
        });
    }

    private List<QdrantVectorStore.Hit> searchVectors(List<Float> query, int limit) {
        return vectorPoints.get().stream()
                .map(point -> new QdrantVectorStore.Hit(
                        point.index(), point.chunkId(), cosine(query, point.vector()),
                        point.startMs(), point.endMs(), point.summary(), point.keywords()))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(QdrantVectorStore.Hit::score).reversed()
                        .thenComparingInt(QdrantVectorStore.Hit::index))
                .limit(limit)
                .toList();
    }

    private static List<RerankerClient.RankedDocument> rerank(
            String query, List<RerankerClient.DocumentInput> documents, int topN) {
        List<Float> queryVector = semanticVector(query);
        return documents.stream()
                .map(document -> new RerankerClient.RankedDocument(
                        document.id(), cosine(queryVector, semanticVector(document.text()))))
                .sorted(Comparator.comparingDouble(RerankerClient.RankedDocument::score).reversed()
                        .thenComparing(RerankerClient.RankedDocument::id))
                .limit(topN)
                .toList();
    }

    private static List<Float> semanticVector(String text) {
        String safe = text == null ? "" : text;
        float[] raw = new float[] {
                containsAny(safe, "网络", "HTTP", "TCP", "代理") ? 0.15f : 0f,
                containsAny(safe, "502", "五百零二", "反向代理", "上游连接", "无效响应") ? 1f : 0f,
                containsAny(safe, "健康检查", "端口没有进程监听", "目标端口", "发布流程", "发布脚本", "ping 通") ? 1f : 0f,
                containsAny(safe, "504", "五百零四", "超时预算", "上游超时") ? 1f : 0f,
                containsAny(safe, "DNS", "域名", "解析器") ? 1f : 0f,
                containsAny(safe, "三次握手", "SYN", "第三次确认") ? 1f : 0f,
                containsAny(safe, "P99", "连接池", "慢数据库", "尾部延迟") ? 1f : 0f,
                safe.contains("NET-TRACE-Z9") ? 1.5f : 0f,
                containsAny(safe, "主机可以 ping 通", "主机ping通", "端口没有进程监听",
                        "发布失败", "发布脚本启动失败") ? 1.25f : 0f
        };
        double norm = 0;
        for (float value : raw) norm += value * value;
        if (norm == 0) return List.of(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        double divisor = Math.sqrt(norm);
        List<Float> vector = new ArrayList<>(raw.length);
        for (float value : raw) vector.add((float) (value / divisor));
        return List.copyOf(vector);
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    private static double cosine(List<Float> left, List<Float> right) {
        double dot = 0;
        double a = 0;
        double b = 0;
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            dot += left.get(i) * right.get(i);
            a += left.get(i) * left.get(i);
            b += right.get(i) * right.get(i);
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    private static String clip(String text, int max) {
        String safe = value(text);
        return safe.length() <= max ? safe : safe.substring(0, max) + "……";
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private record RerankTrace(
            String query,
            List<RerankerClient.DocumentInput> documents,
            List<RerankerClient.RankedDocument> ranked
    ) {}
}

package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.ApiResponse;
import com.videoagent.controller.AnalysisController;
import com.videoagent.dto.ChatEvidence;
import com.videoagent.dto.ChatEntry;
import com.videoagent.dto.ChatRequest;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.agent.EvidencePackService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.service.eval.AgentEvaluationService;
import com.videoagent.service.eval.AgentTelemetry;
import com.videoagent.service.eval.RagRetrievalMetrics;
import com.videoagent.service.retrieval.QdrantVectorStore.Hit;
import com.videoagent.service.retrieval.QdrantVectorStore.Point;
import com.videoagent.service.trust.FidelityChecker;
import com.videoagent.support.RagFixtureLoader;
import com.videoagent.utils.CurrentUser;
import com.videoagent.utils.EmbeddingClient;
import com.videoagent.utils.LlmClient;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 20 分钟过程文件的隔离业务流测试。
 *
 * <p>真实复用生产代码中的 ASR/OCR 对齐、分块、抽取式摘要、Lucene BM25/OCR、
 * 加权 RRF、Top5 精排结果转换以及两类 EvidencePack 构建。只有 Qdrant、Embedding、
 * Reranker 和 Checkpoint 持久化这些外部边界使用内存实现或 Mock，不连接任何开发/生产服务。</p>
 */
class RagTwentyMinuteBusinessFlowTest {

    private static final long MEDIA_ID = 900_020L;
    private static final long USER_ID = 7L;
    private static final String CONTENT_HASH =
            "ed71ab902e50825af242646596322410b86b1b89443b392a5047f63dc61b1d2f";

    private final ObjectMapper mapper = new ObjectMapper();
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankerClient rerankerClient = mock(RerankerClient.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final AtomicReference<List<Point>> vectorPoints = new AtomicReference<>(List.of());

    private Bm25IndexService bm25IndexService;
    private RetrievalIndexService indexService;
    private VideoEvidenceRetrievalService retrievalService;
    private VideoContext context;
    private List<VideoChunk> chunks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        RagFixtureLoader.Fixture fixture = RagFixtureLoader.load20Minutes(mapper);
        context = fixture.align(String.valueOf(MEDIA_ID), "验证 Redis 缓存课程的检索效果");

        // 真实 Lucene 内存 Directory：执行生产 BM25 建索引和查询代码，但不写磁盘。
        bm25IndexService = new Bm25IndexService(new ByteBuffersDirectory());
        configureDeterministicModelBoundaries();

        when(checkpointService.loadIfStage(
                eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX), eq("INDEXED"),
                any(TypeReference.class))).thenReturn(Optional.empty());
        when(llmProvider.forUser(USER_ID)).thenReturn(null);

        indexService = new RetrievalIndexService(
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

        // 与业务入口一致：VideoContext 就绪后建立索引；后续检索复用进程内索引缓存。
        chunks = indexService.index(MEDIA_ID, CONTENT_HASH, context, USER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        bm25IndexService.close();
    }

    @Test
    void fixtureRunsThroughAlignmentChunkingEnrichmentAndAllIndexes() {
        assertThat(context.segments()).hasSize(50);
        assertThat(chunks).hasSize(16);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.chunkId()).isNotBlank();
            assertThat(chunk.indexVersion()).isEqualTo(RetrievalIndexService.INDEX_VERSION);
            assertThat(chunk.segmentSummary()).isNotBlank();
            assertThat(chunk.keywords()).isNotEmpty();
            assertThat(chunk.embedding()).isNotEmpty();
            assertThat(chunk.rawSegments()).isNotEmpty();
        });
        assertThat(chunks.getFirst().startTime()).isZero();
        assertThat(chunks.getLast().endTime()).isEqualTo(1_200_000L);
        assertThat(vectorPoints.get()).hasSize(16);

        assertThat(bm25IndexService.searchContent(
                USER_ID, CONTENT_HASH, RetrievalIndexService.INDEX_VERSION,
                "缓存穿透 布隆过滤器", 25)).isNotEmpty();
        assertThat(bm25IndexService.searchOcr(
                USER_ID, CONTENT_HASH, RetrievalIndexService.INDEX_VERSION,
                "CACHE-SHIELD-X7", 10)).isNotEmpty();

        verify(vectorStore).upsert(
                eq(USER_ID), eq(CONTENT_HASH), eq(MEDIA_ID),
                eq(RetrievalIndexService.INDEX_VERSION), anyList());
        verify(checkpointService).save(
                eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), anyList());
    }

    @Test
    void hybridSearchFindsCachePenetrationDespiteConversationalNoise() {
        List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                MEDIA_ID,
                CONTENT_HASH,
                context,
                "不存在的数据怎样避免请求打垮数据库，布隆过滤器有什么用",
                5,
                USER_ID);

        assertThat(hits).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(hits.getFirst().startMs()).isLessThan(300_000L);
        assertThat(hits.getFirst().endMs()).isGreaterThan(240_000L);
        assertThat(hits.getFirst().source()).contains("QDRANT", "BM25");
        assertThat(hits).extracting(EvidenceHit::chunkId).doesNotHaveDuplicates();
    }

    @Test
    void ocrOnlyTokenUsesOcrChannelAndLocatesTheFinalMinute() {
        List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                MEDIA_ID, CONTENT_HASH, context, "CACHE-SHIELD-X7", 5, USER_ID);

        assertThat(hits).isNotEmpty();
        EvidenceHit first = hits.getFirst();
        assertThat(first.startMs()).isLessThanOrEqualTo(1_165_000L);
        assertThat(first.endMs()).isGreaterThan(1_165_000L);
        assertThat(first.source()).contains("OCR");
        VideoChunk selected = chunks.stream()
                .filter(chunk -> chunk.chunkId().equals(first.chunkId()))
                .findFirst().orElseThrow();
        assertThat(selected.transcript()).doesNotContain("CACHE-SHIELD-X7");
        assertThat(selected.visualTexts()).contains("CACHE-SHIELD-X7");
    }

    @Test
    void rerankedChunksReadBackOriginalTextAndMergeIntoChatEvidencePack() {
        List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                MEDIA_ID,
                CONTENT_HASH,
                context,
                "缓存击穿和缓存雪崩有什么区别，TTL 随机抖动如何处理",
                5,
                USER_ID);
        ChatEvidenceService evidenceService = new ChatEvidenceService();

        List<ChatEvidence> evidence = evidenceService.build(MEDIA_ID, chunks, hits);
        String prompt = evidenceService.toPromptText("Redis 缓存设计测试视频", evidence);

        assertThat(evidence).isNotEmpty();
        assertThat(evidence).flatExtracting(ChatEvidence::chunkIds).hasSizeLessThanOrEqualTo(5);
        assertThat(prompt).contains("[证据1]", "转写原文:", "缓存击穿", "缓存雪崩");
        // 相邻重叠 Chunk 中的同一条原始 ASR 只应回读一次。
        String quotes = evidence.stream().map(ChatEvidence::quote).collect(Collectors.joining("\n"));
        assertThat(occurrences(quotes, "缓存击穿通常发生")).isEqualTo(1);
    }

    @Test
    void agentEvidencePackReusesBatchRetrievalAndDirectTimestampReadback() {
        EvidencePackService evidencePackService = new EvidencePackService(retrievalService, indexService);

        EvidencePackService.EvidencePack pack = evidencePackService.build(
                MEDIA_ID,
                CONTENT_HASH,
                context,
                chunks,
                List.of("缓存穿透 布隆过滤器 不存在的数据"),
                List.of(1_165_000L),
                USER_ID);

        assertThat(pack.items()).anySatisfy(item ->
                assertThat(item.content()).contains("缓存穿透", "布隆过滤器"));
        assertThat(pack.items().stream()
                .filter(item -> "TARGETED".equals(item.source())).toList())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.startMs()).isLessThanOrEqualTo(1_165_000L);
                    assertThat(item.endMs()).isGreaterThan(1_165_000L);
                });
        assertThat(pack.coveredTimestamps()).isNotEmpty();
        assertThat(pack.toPromptText()).contains("[证据1]", "来源=ASR+OCR");
        verify(rerankerClient).rerankBatch(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void threeRoundVideoFollowUpKeepsHistoryRewritesReferencesAndSkipsAgentLoop() {
        MediaFileRepository mediaRepository = mock(MediaFileRepository.class);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        AgentLoopService agentLoopService = mock(AgentLoopService.class);
        LlmClient conversationModel = mock(LlmClient.class);
        AtomicReference<List<ChatEntry>> persistedHistory = new AtomicReference<>(new ArrayList<>());

        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        media.setFilename("rag-fixture-20min.mp4");
        media.setTitle("Redis 缓存设计测试视频");
        media.setContentHash(CONTENT_HASH);
        media.setDurationMs(1_200_000L);
        media.setStatus(MediaFile.STATUS_CONTEXT_READY);
        when(mediaRepository.findByIdAndUserId(MEDIA_ID, USER_ID)).thenReturn(Optional.of(media));
        when(rateLimitService.tryAcquireUser(USER_ID)).thenReturn(true);
        when(rateLimitService.tryAcquireGlobal()).thenReturn(true);
        when(checkpointService.loadVideoContext(MEDIA_ID)).thenReturn(Optional.of(context));
        when(checkpointService.load(
                eq(MEDIA_ID), eq("media-chat"), any(TypeReference.class))).thenAnswer(invocation ->
                Optional.of(new ArrayList<>(persistedHistory.get())));
        doAnswer(invocation -> {
            if ("media-chat".equals(invocation.getArgument(1))) {
                List<ChatEntry> saved = invocation.getArgument(3);
                persistedHistory.set(new ArrayList<>(saved));
            }
            return null;
        }).when(checkpointService).save(eq(MEDIA_ID), anyString(), anyString(), any());

        when(llmProvider.forUser(USER_ID)).thenReturn(conversationModel);
        when(conversationModel.chat(anyString(), eq(220))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("当前问题：缓存穿透怎么处理？")) {
                return """
                        {"standaloneQuestion":"缓存穿透怎么处理？",\
                        "semanticQuery":"缓存穿透 不存在的数据 布隆过滤器 空值缓存",\
                        "keywords":["缓存穿透","不存在的数据","布隆过滤器","空值"],\
                        "ocrKeywords":["缓存穿透","布隆过滤器"]}
                        """;
            }
            if (prompt.contains("当前问题：那它和缓存击穿有什么区别？")) {
                return """
                        {"standaloneQuestion":"缓存穿透和缓存击穿有什么区别？",\
                        "semanticQuery":"缓存穿透与缓存击穿的区别 不存在数据 热点键过期",\
                        "keywords":["缓存穿透","缓存击穿","不存在的数据","热点键"],\
                        "ocrKeywords":["缓存穿透","缓存击穿"]}
                        """;
            }
            if (prompt.contains("当前问题：如果是大量键一起过期呢？")) {
                return """
                        {"standaloneQuestion":"大量缓存键一起过期形成缓存雪崩时怎么办？",\
                        "semanticQuery":"缓存雪崩 大量键同时过期 TTL随机抖动 限流熔断",\
                        "keywords":["缓存雪崩","大量键","同时过期","随机抖动","限流","熔断"],\
                        "ocrKeywords":["缓存雪崩","TTL随机抖动"]}
                        """;
            }
            throw new AssertionError("未识别的连续追问改写输入：" + prompt);
        });
        when(conversationModel.chat(anyString(), eq(1_200))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("独立问题：缓存穿透怎么处理？")) {
                return "视频建议缓存短期空值，或在入口使用布隆过滤器。[证据1]";
            }
            if (prompt.contains("独立问题：缓存穿透和缓存击穿有什么区别？")) {
                return "穿透针对不存在的数据，击穿针对单个热点键过期。[证据1]";
            }
            if (prompt.contains("独立问题：大量缓存键一起过期形成缓存雪崩时怎么办？")) {
                return "大量键同时过期属于缓存雪崩，可增加 TTL 随机抖动并配合限流、熔断。[证据1]";
            }
            throw new AssertionError("回答提示词缺少预期独立问题：" + prompt);
        });

        AnalysisController controller = new AnalysisController(
                mediaRepository,
                checkpointService,
                mock(StageEventPublisher.class),
                rateLimitService,
                mock(KafkaTemplate.class),
                mapper,
                retrievalService,
                agentLoopService,
                mock(RedissonClient.class),
                mock(AnalysisFeedbackRepository.class),
                mock(AgentTelemetry.class),
                mock(AgentEvaluationService.class),
                mock(FidelityChecker.class),
                llmProvider,
                mock(GlobalKnowledgeSearchService.class),
                new QueryRewriter(mapper),
                new ChatEvidenceService(),
                new com.videoagent.service.ChatHistoryViewService(checkpointService));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(CurrentUser.ATTR_USER_ID)).thenReturn(USER_ID);

        ApiResponse<AnalysisController.ChatResponse> first = controller.chat(
                new ChatRequest(MEDIA_ID, "缓存穿透怎么处理？", List.of()), request);
        ApiResponse<AnalysisController.ChatResponse> second = controller.chat(
                new ChatRequest(MEDIA_ID, "那它和缓存击穿有什么区别？", List.of()), request);
        ApiResponse<AnalysisController.ChatResponse> third = controller.chat(
                new ChatRequest(MEDIA_ID, "如果是大量键一起过期呢？", List.of()), request);

        assertThat(first.data().answer()).contains("空值", "布隆过滤器", "[证据1]");
        assertThat(joinQuotes(first.data().evidence())).contains("缓存穿透", "布隆过滤器");
        assertThat(first.data().evidence()).anyMatch(item ->
                item.startMs() < 300_000L && item.endMs() > 240_000L);

        assertThat(second.data().answer()).contains("不存在的数据", "热点键", "[证据1]");
        assertThat(joinQuotes(second.data().evidence())).contains("缓存穿透", "缓存击穿");

        assertThat(third.data().answer()).contains("缓存雪崩", "随机抖动", "限流", "熔断", "[证据1]");
        assertThat(joinQuotes(third.data().evidence())).contains("缓存雪崩", "随机抖动");
        assertThat(third.data().evidence()).anyMatch(item ->
                item.startMs() < 420_000L && item.endMs() > 360_000L);

        assertThat(persistedHistory.get()).hasSize(6)
                .extracting(ChatEntry::role)
                .containsExactly("user", "assistant", "user", "assistant", "user", "assistant");
        assertThat(persistedHistory.get().get(1).evidence()).isNotEmpty();
        assertThat(persistedHistory.get().get(3).evidence()).isNotEmpty();
        assertThat(persistedHistory.get().get(5).evidence()).isNotEmpty();

        ArgumentCaptor<String> rewritePrompts = ArgumentCaptor.forClass(String.class);
        verify(conversationModel, times(3)).chat(rewritePrompts.capture(), eq(220));
        assertThat(rewritePrompts.getAllValues().get(1))
                .contains("user：缓存穿透怎么处理？", "assistant：视频建议缓存短期空值");
        assertThat(rewritePrompts.getAllValues().get(2))
                .contains("user：那它和缓存击穿有什么区别？", "assistant：穿透针对不存在的数据");
        verify(conversationModel, times(3)).chat(anyString(), eq(1_200));
        verify(agentLoopService, never()).run(any(), anyString(), any(), any());
        verify(checkpointService, times(3))
                .save(eq(MEDIA_ID), eq("media-chat"), eq("CHAT"), any());
    }

    @Test
    void threeRoundRetrievalWithoutLlmWritesFinalEvidencePacks() throws Exception {
        clearInvocations(llmProvider);
        RagFixtureLoader.GoldenVideo goldenVideo = RagFixtureLoader.goldenVideo(mapper, "20min");
        List<String> standaloneQueries = goldenVideo.questions().stream()
                .map(RagFixtureLoader.GoldenQuestion::standaloneQuery)
                .toList();
        ChatEvidenceService evidenceService = new ChatEvidenceService();
        StringBuilder report = new StringBuilder("20分钟视频三轮检索 EvidencePack（不调用LLM）\n\n");

        for (int i = 0; i < standaloneQueries.size(); i++) {
            String query = standaloneQueries.get(i);
            List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                    MEDIA_ID, CONTENT_HASH, context, query, 5, USER_ID);
            List<ChatEvidence> evidence = evidenceService.build(MEDIA_ID, chunks, hits);
            Map<String, Integer> gains = goldenVideo.questions().get(i).expectedTop7().stream()
                    .collect(Collectors.toMap(
                            RagFixtureLoader.GoldenChunk::chunkId,
                            RagFixtureLoader.GoldenChunk::relevance));
            RagRetrievalMetrics.Report metrics = RagRetrievalMetrics.evaluate(
                    hits.stream().map(EvidenceHit::chunkId).toList(), gains, 5);

            assertThat(hits).isNotEmpty().hasSizeLessThanOrEqualTo(5);
            assertThat(evidence).isNotEmpty();
            assertThat(metrics.hitAtK()).as("第%d轮 Reranker Hit@5", i + 1).isEqualTo(1);
            report.append("================ 第").append(i + 1).append("轮 ================\n")
                    .append("独立检索问题：").append(query).append("\n")
                    .append("精排命中数：").append(hits.size()).append("\n\n")
                    .append("Ground Truth 指标：Hit@5=").append(metrics.hitAtK())
                    .append(" Precision@5=").append(metrics.precisionAtK())
                    .append(" Recall@5=").append(metrics.recallAtK())
                    .append(" MRR@5=").append(metrics.mrr())
                    .append(" nDCG@5=").append(metrics.ndcgAtK()).append("\n\n")
                    .append("EvidencePack:\n")
                    .append(evidenceService.toPromptText("Redis 缓存设计测试视频", evidence))
                    .append('\n');
        }

        String output = report.toString();
        assertThat(output)
                .contains("缓存穿透", "布隆过滤器", "缓存击穿", "缓存雪崩", "随机抖动")
                .doesNotContain("检索分数:");
        verify(llmProvider, never()).forUser(anyLong());

        Path outputPath = Path.of("target", "rag-test-reports",
                "20min-three-round-evidencepack.txt");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, output, StandardCharsets.UTF_8);
        System.out.println("EvidencePack 报告：" + outputPath.toAbsolutePath());
    }

    private void configureDeterministicModelBoundaries() {
        when(embeddingClient.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(RagTwentyMinuteBusinessFlowTest::semanticVector).toList();
        });
        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                semanticVector(invocation.getArgument(0)));

        doAnswer(invocation -> {
            List<Point> points = invocation.getArgument(4);
            vectorPoints.set(List.copyOf(points));
            return null;
        }).when(vectorStore).upsert(anyLong(), anyString(), anyLong(), anyInt(), anyList());
        when(vectorStore.search(anyList(), anyInt(), anyLong(), any(), anyInt()))
                .thenAnswer(invocation -> searchVectors(
                        invocation.getArgument(0), invocation.getArgument(1)));

        when(rerankerClient.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> rerank(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(rerankerClient.rerankBatch(anyList())).thenAnswer(invocation -> {
            List<RerankerClient.BatchInput> batches = invocation.getArgument(0);
            return batches.stream()
                    .map(batch -> rerank(batch.query(), batch.documents(), batch.topN()))
                    .toList();
        });
    }

    private List<Hit> searchVectors(List<Float> query, int limit) {
        return vectorPoints.get().stream()
                .map(point -> new Hit(
                        point.index(), point.chunkId(), cosine(query, point.vector()),
                        point.startMs(), point.endMs(), point.summary(), point.keywords()))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparingInt(Hit::index))
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

    /**
     * 测试替身只模拟远端 BGE 的语义边界；生产分块文本仍原样传入，且输出保持归一化向量。
     * 各维分别表示缓存通用、穿透、击穿、雪崩、一致性、持久化、集群和 OCR-only 标记。
     */
    private static List<Float> semanticVector(String text) {
        String safe = text == null ? "" : text;
        float[] raw = new float[] {
                containsAny(safe, "缓存", "Redis", "瑞迪斯") ? 0.25f : 0f,
                containsAny(safe, "穿透", "布隆", "不存在的数据", "本来就不存在", "查询不存在", "空值") ? 1f : 0f,
                containsAny(safe, "击穿", "热门键", "互斥", "单键重建") ? 1f : 0f,
                containsAny(safe, "雪崩", "随机抖动", "大量键", "熔断") ? 1f : 0f,
                containsAny(safe, "一致性", "删除缓存", "旧值写回", "版本号") ? 1f : 0f,
                containsAny(safe, "RDB", "AOF", "持久化", "快照") ? 1f : 0f,
                containsAny(safe, "集群", "哈希槽", "主从", "哨兵") ? 1f : 0f,
                safe.contains("CACHE-SHIELD-X7") ? 1.5f : 0f
        };
        double norm = 0;
        for (float value : raw) norm += value * value;
        if (norm == 0) return List.of(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
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

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0;
        double left = 0;
        double right = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            dot += a.get(i) * b.get(i);
            left += a.get(i) * a.get(i);
            right += b.get(i) * b.get(i);
        }
        return left == 0 || right == 0 ? 0 : dot / Math.sqrt(left * right);
    }

    private static int occurrences(String text, String target) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(target, from)) >= 0) {
            count++;
            from += target.length();
        }
        return count;
    }

    private static String joinQuotes(List<ChatEvidence> evidence) {
        return evidence.stream().map(ChatEvidence::quote).collect(Collectors.joining("\n"));
    }
}

package com.videoagent.service.retrieval;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.ai.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoEvidenceRetrievalServiceTest {

    private static final long MEDIA_ID = 11L;
    private static final long USER_ID = 7L;
    private static final String CONTENT_HASH = "content-hash";

    private final RetrievalIndexService indexService = mock(RetrievalIndexService.class);
    private final HybridRetrievalService hybrid = mock(HybridRetrievalService.class);
    private final QueryRewriter queryRewriter = mock(QueryRewriter.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);

    private VideoEvidenceRetrievalService service;
    private VideoContext context;
    private VideoChunk chunk;

    @BeforeEach
    void setUp() {
        service = new VideoEvidenceRetrievalService(indexService, hybrid, queryRewriter, llmProvider);
        context = VideoContext.of(String.valueOf(MEDIA_ID), "goal", List.of());
        chunk = VideoChunk.indexed(0, 90_000, "TCP 三次握手", List.of("SYN ACK"), List.of(),
                        "chunk-a", 0, CONTENT_HASH, 2)
                .withEnrichment("握手摘要", List.of("TCP"))
                .withEmbedding(List.of(1f, 0f));
        when(indexService.index(MEDIA_ID, CONTENT_HASH, context, USER_ID)).thenReturn(List.of(chunk));
    }

    @Test
    void searchNoRewriteMapsUnifiedCandidateToLegacyEvidenceHit() {
        when(hybrid.retrieve(any(), any(), eq(List.of(chunk)), eq(5)))
                .thenReturn(new RetrievalResult(List.of(new RetrievalCandidate(
                        "chunk-a", .0163934426, 1, 1, null,
                        Set.of(RetrievalChannel.DENSE, RetrievalChannel.BM25), null)), "QDRANT"));

        List<EvidenceHit> hits = service.searchNoRewrite(
                MEDIA_ID, CONTENT_HASH, context, "TCP", 5, USER_ID);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunkId()).isEqualTo("chunk-a");
        assertThat(hits.getFirst().source()).isEqualTo("QDRANT+BM25");
        assertThat(hits.getFirst().matchedTerms()).containsExactly("TCP");
        assertThat(hits.getFirst().score()).isEqualTo(.016393);
    }

    @Test
    void staleCandidateNotPresentInCurrentChunksIsIgnored() {
        when(hybrid.retrieve(any(), any(), eq(List.of(chunk)), eq(5)))
                .thenReturn(new RetrievalResult(List.of(new RetrievalCandidate(
                        "stale", .1, 1, null, null, Set.of(RetrievalChannel.DENSE), null)), "QDRANT"));

        assertThat(service.searchNoRewrite(
                MEDIA_ID, CONTENT_HASH, context, "TCP", 5, USER_ID)).isEmpty();
    }

    @Test
    void searchWithRewritePassesStructuredQueriesToUnifiedCore() {
        when(queryRewriter.rewrite(eq("它为什么发生"), any()))
                .thenReturn(new QueryRewriter.Rewrite(
                        "TCP 握手发生原因", List.of("TCP", "握手"), List.of("SYN")));
        when(hybrid.retrieve(any(), any(), eq(List.of(chunk)), eq(5)))
                .thenReturn(new RetrievalResult(List.of(), "QDRANT"));

        service.search(MEDIA_ID, CONTENT_HASH, context, "它为什么发生", 5, USER_ID);

        verify(hybrid).retrieve(
                eq(RetrievalScope.singleMedia(USER_ID, MEDIA_ID, CONTENT_HASH)),
                eq(new HybridQuery("它为什么发生", "TCP 握手发生原因",
                        List.of("TCP", "握手"), List.of("SYN"))),
                eq(List.of(chunk)), eq(5));
    }

    @Test
    void batchSearchPreparesIndexOnceAndMapsEachTaskIndependently() {
        List<HybridQuery> queries = List.of(
                new HybridQuery("TCP", "TCP", List.of("TCP"), List.of("TCP")),
                new HybridQuery("SYN", "SYN", List.of("SYN"), List.of("SYN")));
        RetrievalCandidate candidate = new RetrievalCandidate(
                "chunk-a", .01, 1, null, null, Set.of(RetrievalChannel.BM25), .8);
        when(hybrid.retrieveBatch(
                RetrievalScope.singleMedia(USER_ID, MEDIA_ID, CONTENT_HASH),
                queries, List.of(chunk), 2))
                .thenReturn(List.of(
                        new RetrievalResult(List.of(candidate), "DENSE_UNAVAILABLE"),
                        new RetrievalResult(List.of(), "DENSE_UNAVAILABLE")));

        Map<String, List<EvidenceHit>> results = service.searchNoRewriteBatch(
                MEDIA_ID, CONTENT_HASH, context, List.of("TCP", "SYN"), 2, USER_ID);

        assertThat(results.get("TCP")).hasSize(1);
        assertThat(results.get("SYN")).isEmpty();
        verify(indexService).index(MEDIA_ID, CONTENT_HASH, context, USER_ID);
        verify(hybrid).retrieveBatch(
                RetrievalScope.singleMedia(USER_ID, MEDIA_ID, CONTENT_HASH),
                queries, List.of(chunk), 2);
    }
}

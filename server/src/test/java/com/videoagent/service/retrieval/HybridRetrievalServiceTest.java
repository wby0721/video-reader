package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import com.videoagent.utils.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final Bm25IndexService bm25 = mock(Bm25IndexService.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final RerankerClient reranker = mock(RerankerClient.class);
    private HybridRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new HybridRetrievalService(
                vectorStore, bm25, embeddings, new WeightedRrfFusion(), reranker);
        when(reranker.rerank(any(), any(), anyInt())).thenAnswer(invocation -> {
            List<RerankerClient.DocumentInput> documents = invocation.getArgument(1);
            int topN = invocation.getArgument(2);
            return documents.stream().limit(topN)
                    .map(doc -> new RerankerClient.RankedDocument(doc.id(), 1.0))
                    .toList();
        });
    }

    @Test
    void retrieveUsesConfiguredChannelLimitsAndRrfDeduplication() {
        List<VideoChunk> chunks = List.of(
                chunk("a", List.of(1f, 0f)), chunk("b", List.of(0f, 1f)));
        when(embeddings.embed("semantic query")).thenReturn(List.of(1f, 0f));
        when(vectorStore.search(any(), eq(25), eq(7L), eq("hash"), eq(2)))
                .thenReturn(List.of(qdrant("a", 0, .9), qdrant("b", 1, .8)));
        when(bm25.searchContent(7L, "hash", 2, "TCP SYN", 25))
                .thenReturn(List.of(lucene("b", .8f), lucene("a", .7f)));
        when(bm25.searchOcr(7L, "hash", 2, "latency", 10))
                .thenReturn(List.of(lucene("b", .9f)));

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "semantic query", List.of("TCP", "SYN"), List.of("latency")),
                chunks, 10);

        assertThat(result.candidates()).extracting(RetrievalCandidate::chunkId)
                .containsExactly("b", "a");
        assertThat(result.candidates().get(1).sources())
                .containsExactlyInAnyOrder(RetrievalChannel.DENSE, RetrievalChannel.BM25);
        assertThat(result.denseSource()).isEqualTo("QDRANT");
        assertThat(result.trace()).isNotNull();
        assertThat(result.trace().parameters()).satisfies(parameters -> {
            assertThat(parameters.denseTopK()).isEqualTo(25);
            assertThat(parameters.bm25TopK()).isEqualTo(25);
            assertThat(parameters.ocrTopK()).isEqualTo(10);
            assertThat(parameters.fusedTopK()).isEqualTo(10);
            assertThat(parameters.rerankerTopK()).isEqualTo(5);
            assertThat(parameters.rrfK()).isEqualTo(60);
            assertThat(parameters.rrfWeights()).containsEntry("DENSE", .5)
                    .containsEntry("BM25", .35).containsEntry("OCR", .15);
        });
        assertThat(result.trace().denseRecall()).extracting(h -> h.nativeScore())
                .containsExactly(.9, .8);
        assertThat(result.trace().bm25Recall().get(0).nativeScore()).isCloseTo(.8,
                org.assertj.core.data.Offset.offset(1e-6));
        assertThat(result.trace().bm25Recall().get(1).nativeScore()).isCloseTo(.7,
                org.assertj.core.data.Offset.offset(1e-6));
        assertThat(result.trace().ocrRecall().getFirst().nativeScore()).isCloseTo(.9,
                org.assertj.core.data.Offset.offset(1e-6));
        assertThat(result.trace().fusedCandidates()).extracting(h -> h.chunkId())
                .containsExactly("b", "a");
        assertThat(result.trace().fusedCandidates().getFirst()).satisfies(hit -> {
            assertThat(hit.denseRank()).isEqualTo(2);
            assertThat(hit.bm25Rank()).isEqualTo(1);
            assertThat(hit.ocrRank()).isEqualTo(1);
            assertThat(hit.rrfScore()).isEqualTo(
                    hit.denseContribution() + hit.bm25Contribution() + hit.ocrContribution());
        });
        assertThat(result.trace().rerankedCandidates()).allMatch(h -> h.rerankerScore() != null);
        assertThat(result.trace().rerankerStatus()).isEqualTo("APPLIED");
        verify(vectorStore).search(any(), eq(25), eq(7L), eq("hash"), eq(2));
        verify(bm25).searchContent(7L, "hash", 2, "TCP SYN", 25);
        verify(bm25).searchOcr(7L, "hash", 2, "latency", 10);
    }

    @Test
    void embeddingFailureStillReturnsBm25Candidate() {
        List<VideoChunk> chunks = List.of(chunk("a", List.of(1f, 0f)));
        when(embeddings.embed("question")).thenThrow(new IllegalStateException("offline"));
        when(bm25.searchContent(7L, "hash", 2, "question", 25))
                .thenReturn(List.of(lucene("a", 1f)));

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().sources()).containsExactly(RetrievalChannel.BM25);
        assertThat(result.denseSource()).isEqualTo("EMBEDDING_UNAVAILABLE");
    }

    @Test
    void qdrantFailureUsesLocalCosineAndOtherChannelFailureDoesNotAbort() {
        List<VideoChunk> chunks = List.of(
                chunk("a", List.of(1f, 0f)), chunk("b", List.of(0f, 1f)));
        when(embeddings.embed("question")).thenReturn(List.of(1f, 0f));
        when(vectorStore.search(any(), eq(25), eq(7L), eq("hash"), eq(2)))
                .thenThrow(new IllegalStateException("qdrant offline"));
        when(bm25.searchContent(any(), any(), eq(2), any(), eq(25)))
                .thenThrow(new IllegalStateException("lucene offline"));

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).extracting(RetrievalCandidate::chunkId).containsExactly("a");
        assertThat(result.denseSource()).isEqualTo("LOCAL_COSINE+BM25_UNAVAILABLE");
    }

    @Test
    void noPositiveChannelMatchReturnsEmpty() {
        List<VideoChunk> chunks = List.of(chunk("a", List.of(1f, 0f)));
        when(embeddings.embed("question")).thenReturn(List.of(0f, 1f));
        when(vectorStore.search(any(), eq(25), eq(7L), eq("hash"), eq(2))).thenReturn(List.of());

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void rerankerReordersRrfCandidatesAndKeepsAtMostFive() {
        List<VideoChunk> chunks = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> chunk("c" + i, List.of()))
                .toList();
        List<Bm25IndexService.Hit> bm25Hits = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> lucene("c" + i, 1f - i * .1f))
                .toList();
        when(bm25.searchContent(7L, "hash", 2, "question", 25)).thenReturn(bm25Hits);
        doReturn(List.of(
                new RerankerClient.RankedDocument("c5", .99),
                new RerankerClient.RankedDocument("c4", .9),
                new RerankerClient.RankedDocument("c3", .8),
                new RerankerClient.RankedDocument("c2", .7),
                new RerankerClient.RankedDocument("c1", .6)))
                .when(reranker).rerank(any(), any(), eq(5));

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).extracting(RetrievalCandidate::chunkId)
                .containsExactly("c5", "c4", "c3", "c2", "c1");
        assertThat(result.candidates()).allMatch(candidate -> candidate.rerankerScore() != null);
        verify(reranker).rerank(eq("question"), any(), eq(5));
    }

    @Test
    void rerankerFailureFallsBackToRrfTopFive() {
        List<VideoChunk> chunks = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> chunk("c" + i, List.of()))
                .toList();
        when(bm25.searchContent(7L, "hash", 2, "question", 25)).thenReturn(
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(i -> lucene("c" + i, 1f)).toList());
        doThrow(new IllegalStateException("reranker offline"))
                .when(reranker).rerank(any(), any(), eq(5));

        RetrievalResult result = service.retrieve(
                RetrievalScope.singleMedia(7L, 11L, "hash"),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).extracting(RetrievalCandidate::chunkId)
                .containsExactly("c0", "c1", "c2", "c3", "c4");
        assertThat(result.candidates()).allMatch(candidate -> candidate.rerankerScore() == null);
        assertThat(result.trace().rerankerStatus()).isEqualTo("FALLBACK_RRF");
        assertThat(result.trace().rerankedCandidates()).allMatch(hit -> hit.rerankerScore() == null);
    }

    @Test
    void userAllScopeQueriesOnceWithoutContentFilterAndCanReturnTen() {
        List<VideoChunk> chunks = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> chunk("c" + i, List.of()))
                .toList();
        when(bm25.searchContent(eq(7L), isNull(), eq(2), eq("question"), eq(25)))
                .thenReturn(java.util.stream.IntStream.range(0, 6)
                        .mapToObj(i -> lucene("c" + i, 1f)).toList());

        RetrievalResult result = service.retrieve(
                RetrievalScope.userAll(7L),
                new HybridQuery("question", "question", List.of("question"), List.of()), chunks, 10);

        assertThat(result.candidates()).hasSize(6);
        verify(bm25).searchContent(7L, null, 2, "question", 25);
        verify(reranker).rerank(eq("question"), any(), eq(10));
    }

    @Test
    void retrieveBatchUsesOneEmbeddingAndOneRerankerHttpBatchForMultipleTasks() {
        List<VideoChunk> chunks = List.of(
                chunk("a", List.of(1f, 0f)), chunk("b", List.of(0f, 1f)));
        when(embeddings.embedAll(List.of("semantic-1", "semantic-2")))
                .thenReturn(List.of(List.of(1f, 0f), List.of(0f, 1f)));
        when(vectorStore.search(any(), eq(25), eq(7L), eq("hash"), eq(2)))
                .thenReturn(List.of(qdrant("a", 0, .9)), List.of(qdrant("b", 1, .9)));
        when(bm25.searchContent(7L, "hash", 2, "term-1", 25))
                .thenReturn(List.of(lucene("a", 1f)));
        when(bm25.searchContent(7L, "hash", 2, "term-2", 25))
                .thenReturn(List.of(lucene("b", 1f)));
        when(reranker.rerankBatch(any())).thenReturn(List.of(
                List.of(new RerankerClient.RankedDocument("a", .8)),
                List.of(new RerankerClient.RankedDocument("b", .9))));

        List<RetrievalResult> results = service.retrieveBatch(
                RetrievalScope.singleMedia(7L, 11L, "hash"), List.of(
                        new HybridQuery("q1", "semantic-1", List.of("term-1"), List.of()),
                        new HybridQuery("q2", "semantic-2", List.of("term-2"), List.of())),
                chunks, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).candidates()).extracting(RetrievalCandidate::chunkId)
                .containsExactly("a");
        assertThat(results.get(1).candidates()).extracting(RetrievalCandidate::chunkId)
                .containsExactly("b");
        verify(embeddings).embedAll(List.of("semantic-1", "semantic-2"));
        verify(embeddings, never()).embed(any());
        verify(reranker).rerankBatch(any());
    }

    private static VideoChunk chunk(String id, List<Float> embedding) {
        return VideoChunk.indexed(0, 90_000, id + " transcript", List.of(), List.of(),
                        id, "a".equals(id) ? 0 : 1, "hash", 2)
                .withEnrichment(id + " summary", List.of(id))
                .withEmbedding(embedding);
    }

    private static QdrantVectorStore.Hit qdrant(String id, int index, double score) {
        return new QdrantVectorStore.Hit(index, id, score, 0, 90_000, id, List.of());
    }

    private static Bm25IndexService.Hit lucene(String id, float score) {
        return new Bm25IndexService.Hit(id, "hash", 11L, 0, 90_000, score);
    }
}

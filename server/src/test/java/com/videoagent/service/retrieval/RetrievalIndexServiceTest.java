package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalIndexServiceTest {

    private static final long MEDIA_ID = 11L;
    private static final long USER_ID = 7L;
    private static final String HASH = "content-hash";

    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final Bm25IndexService bm25IndexService = mock(Bm25IndexService.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ChunkEnricher enricher = mock(ChunkEnricher.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);

    private RetrievalIndexService service;
    private VideoContext context;

    @BeforeEach
    void setUp() {
        service = new RetrievalIndexService(
                vectorStore, bm25IndexService, embeddingClient, enricher, checkpointService, llmProvider);
        context = VideoContext.of(String.valueOf(MEDIA_ID), "goal", List.of(
                VideoSegment.of(0, 60_000, "TCP 三次握手原始转写", List.of("SYN ACK"), List.of())
        ));
        when(llmProvider.forUser(USER_ID)).thenReturn(null);
        when(enricher.enrich(any(), any(), any()))
                .thenReturn(new ChunkEnricher.Enriched("握手摘要", List.of("TCP", "SYN")));
        when(embeddingClient.embedAll(any())).thenReturn(List.of(List.of(1f, 0f)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void index_buildsVersionedChunksAndEmbedsRichTextInOneBatch() {
        when(checkpointService.loadIfStage(eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), any(TypeReference.class))).thenReturn(Optional.empty());

        List<VideoChunk> chunks = service.index(MEDIA_ID, HASH, context, USER_ID);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().chunkId()).isNotBlank();
        assertThat(chunks.getFirst().indexVersion()).isEqualTo(RetrievalIndexService.INDEX_VERSION);
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedAll(texts.capture());
        assertThat(texts.getValue().getFirst())
                .contains("握手摘要", "TCP", "TCP 三次握手原始转写", "SYN ACK");
        verify(vectorStore).upsert(eq(USER_ID), eq(HASH), eq(MEDIA_ID),
                eq(RetrievalIndexService.INDEX_VERSION), any());
        verify(bm25IndexService).index(eq(USER_ID), eq(MEDIA_ID), eq(HASH),
                eq(RetrievalIndexService.INDEX_VERSION), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void index_oldCheckpointVersion_isRebuilt() {
        VideoChunk legacy = VideoChunk.of(0, 60_000, "旧摘要", List.of("旧"),
                "旧转写", List.of(), List.of(), List.of(1f, 0f));
        when(checkpointService.loadIfStage(eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), any(TypeReference.class))).thenReturn(Optional.of(List.of(legacy)));

        List<VideoChunk> chunks = service.index(MEDIA_ID, HASH, context, USER_ID);

        assertThat(chunks.getFirst().indexVersion()).isEqualTo(RetrievalIndexService.INDEX_VERSION);
        assertThat(chunks.getFirst().chunkId()).isNotBlank();
        verify(enricher).enrich(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void index_currentCheckpoint_rehydratesQdrantWithoutReembedding() {
        VideoChunk current = VideoChunkingService.chunk(
                        context, USER_ID, HASH, RetrievalIndexService.INDEX_VERSION).getFirst()
                .withEnrichment("摘要", List.of("TCP"))
                .withEmbedding(List.of(1f, 0f));
        when(checkpointService.loadIfStage(eq(MEDIA_ID), eq(RetrievalIndexService.CP_RETRIEVAL_INDEX),
                eq("INDEXED"), any(TypeReference.class))).thenReturn(Optional.of(List.of(current)));

        List<VideoChunk> chunks = service.index(MEDIA_ID, HASH, context, USER_ID);

        assertThat(chunks).containsExactly(current);
        verify(embeddingClient, never()).embedAll(any());
        verify(enricher, never()).enrich(any(), any(), any());
        verify(vectorStore).upsert(eq(USER_ID), eq(HASH), eq(MEDIA_ID),
                eq(RetrievalIndexService.INDEX_VERSION), any());
        verify(bm25IndexService).index(eq(USER_ID), eq(MEDIA_ID), eq(HASH),
                eq(RetrievalIndexService.INDEX_VERSION), any());
    }
}

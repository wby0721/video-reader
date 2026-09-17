package com.videoagent.service.retrieval;

import com.videoagent.dto.GlobalEvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalKnowledgeSearchServiceTest {

    private final MediaFileRepository mediaRepository = mock(MediaFileRepository.class);
    private final CheckpointService checkpoints = mock(CheckpointService.class);
    private final RetrievalIndexService indexes = mock(RetrievalIndexService.class);
    private final HybridRetrievalService hybrid = mock(HybridRetrievalService.class);
    private final GlobalKnowledgeSearchService service = new GlobalKnowledgeSearchService(
            mediaRepository, checkpoints, indexes, hybrid);

    @Test
    @SuppressWarnings("unchecked")
    void searchesAllUserChunksOnceAndLimitsEachVideoToThree() {
        MediaFile first = media(11L, "hash-a", "网络课程", "network.mp4", MediaFile.STATUS_CONTEXT_READY);
        MediaFile second = media(12L, "hash-b", null, "database.mp4", MediaFile.STATUS_CONTEXT_READY);
        MediaFile unfinished = media(13L, "hash-c", "未完成", "raw.mp4", MediaFile.STATUS_UPLOADED);
        when(mediaRepository.findByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(first, second, unfinished));
        VideoContext context = VideoContext.of("video", "goal", List.of());
        when(checkpoints.loadVideoContext(11L)).thenReturn(Optional.of(context));
        when(checkpoints.loadVideoContext(12L)).thenReturn(Optional.of(context));

        List<VideoChunk> firstChunks = chunks("a", "hash-a", 4);
        List<VideoChunk> secondChunks = chunks("b", "hash-b", 2);
        when(indexes.indexForSearch(11L, "hash-a", context, 7L)).thenReturn(firstChunks);
        when(indexes.indexForSearch(12L, "hash-b", context, 7L)).thenReturn(secondChunks);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (VideoChunk chunk : firstChunks) candidates.add(candidate(chunk.chunkId()));
        for (VideoChunk chunk : secondChunks) candidates.add(candidate(chunk.chunkId()));
        when(hybrid.retrieve(any(), any(), any(), eq(HybridRetrievalService.GLOBAL_CANDIDATE_LIMIT)))
                .thenReturn(new RetrievalResult(candidates, "QDRANT"));

        List<GlobalEvidenceHit> result = service.search(7L, "TCP 握手", 10);

        assertThat(result).hasSize(5);
        assertThat(result.stream().filter(hit -> hit.mediaId().equals(11L))).hasSize(3);
        assertThat(result.stream().filter(hit -> hit.mediaId().equals(12L))).hasSize(2);
        assertThat(result.getFirst().title()).isEqualTo("网络课程");
        assertThat(result.get(3).title()).isEqualTo("database.mp4");
        assertThat(result.getFirst().keywords()).containsExactly("TCP");

        ArgumentCaptor<RetrievalScope> scope = ArgumentCaptor.forClass(RetrievalScope.class);
        ArgumentCaptor<List<VideoChunk>> allChunks = ArgumentCaptor.forClass(List.class);
        verify(hybrid).retrieve(scope.capture(), any(), allChunks.capture(), eq(HybridRetrievalService.GLOBAL_CANDIDATE_LIMIT));
        assertThat(scope.getValue()).isEqualTo(RetrievalScope.userAll(7L));
        assertThat(allChunks.getValue()).hasSize(6);
        verify(checkpoints, never()).loadVideoContext(13L);
    }

    @Test
    void emptyKnowledgeBaseDoesNotRunRetrieval() {
        when(mediaRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

        assertThat(service.search(7L, "query", 10)).isEmpty();
        verify(hybrid, never()).retrieve(any(), any(), any(), anyInt());
    }

    private static MediaFile media(long id, String hash, String title, String filename, String status) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(7L);
        media.setContentHash(hash);
        media.setTitle(title);
        media.setFilename(filename);
        media.setStatus(status);
        return media;
    }

    private static List<VideoChunk> chunks(String prefix, String hash, int count) {
        List<VideoChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chunks.add(VideoChunk.indexed(i * 75_000L, i * 75_000L + 90_000,
                            "TCP chunk " + i, List.of(), List.of(), prefix + i, i, hash, 2)
                    .withEnrichment("summary " + i, List.of("TCP"))
                    .withEmbedding(List.of(1f, 0f)));
        }
        return chunks;
    }

    private static RetrievalCandidate candidate(String chunkId) {
        return new RetrievalCandidate(chunkId, .01, 1, 1, null,
                Set.of(RetrievalChannel.DENSE, RetrievalChannel.BM25), .8);
    }
}

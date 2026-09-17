package com.videoagent.service.retrieval;

import com.videoagent.dto.GlobalEvidenceHit;
import com.videoagent.dto.EvidenceBounds;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.RetrievalAssessment;
import org.springframework.beans.factory.annotation.Autowired;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户个人知识库的一次性全局定位检索，不调用查询改写或答案生成 LLM。 */
@Service
public class GlobalKnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalKnowledgeSearchService.class);
    private static final int GLOBAL_LIMIT = 10;
    private static final int PER_MEDIA_LIMIT = 3;

    private final MediaFileRepository mediaFileRepository;
    private final CheckpointService checkpointService;
    private final RetrievalIndexService indexService;
    private final HybridRetrievalService hybridRetrievalService;
    private final RelevancePolicy relevancePolicy;

    public GlobalKnowledgeSearchService(MediaFileRepository mediaFileRepository,
                                        CheckpointService checkpointService,
                                        RetrievalIndexService indexService,
                                        HybridRetrievalService hybridRetrievalService) {
        this(mediaFileRepository, checkpointService, indexService, hybridRetrievalService, new RelevancePolicy());
    }

    @Autowired
    public GlobalKnowledgeSearchService(MediaFileRepository mediaFileRepository, CheckpointService checkpointService,
                                        RetrievalIndexService indexService, HybridRetrievalService hybridRetrievalService,
                                        RelevancePolicy relevancePolicy) {
        this.mediaFileRepository = mediaFileRepository;
        this.checkpointService = checkpointService;
        this.indexService = indexService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.relevancePolicy = relevancePolicy;
    }

    public List<GlobalEvidenceHit> search(Long userId, String query, int requestedLimit) {
        return searchDetailed(userId, query, requestedLimit).hits();
    }

    public SearchResponse searchDetailed(Long userId, String query, int requestedLimit) {
        if (userId == null) throw new IllegalArgumentException("User scope is required");
        if (query == null || query.isBlank()) return new SearchResponse(List.of(), relevancePolicy.evaluate(List.of()).assessment());
        int limit = Math.min(GLOBAL_LIMIT, Math.max(1, requestedLimit));
        Map<String, ChunkOwner> ownerByChunkId = new LinkedHashMap<>();
        Map<String, VideoChunk> uniqueChunks = new LinkedHashMap<>();
        boolean incompleteIndex = false;

        for (MediaFile media : mediaFileRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!MediaFile.STATUS_CONTEXT_READY.equals(media.getStatus())) {
                continue;
            }
            VideoContext context = checkpointService.loadVideoContext(media.getId()).orElse(null);
            if (context == null) {
                incompleteIndex = true;
                continue;
            }
            try {
                List<VideoChunk> chunks = indexService.indexForSearch(
                        media.getId(), media.getContentHash(), context, userId);
                for (VideoChunk chunk : chunks) {
                    if (chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                        continue;
                    }
                    // 同内容重复上传会共享 chunkId；仓库列表按新到旧，保留最新媒体作为定位入口。
                    uniqueChunks.putIfAbsent(chunk.chunkId(), chunk);
                    ownerByChunkId.putIfAbsent(chunk.chunkId(), new ChunkOwner(media, chunk));
                }
            } catch (Exception e) {
                incompleteIndex = true;
                log.warn("全局检索准备索引时跳过 mediaId={}: {}", media.getId(), e.getMessage());
            }
        }
        if (uniqueChunks.isEmpty()) {
            return new SearchResponse(List.of(), relevancePolicy.evaluate(List.of(),
                    incompleteIndex ? "INDEX_UNAVAILABLE" : "NOT_RUN").assessment());
        }

        List<String> terms = simpleTerms(query);
        RetrievalResult result = hybridRetrievalService.retrieve(
                RetrievalScope.userAll(userId),
                new HybridQuery(query, query, terms, terms),
                List.copyOf(uniqueChunks.values()), HybridRetrievalService.GLOBAL_CANDIDATE_LIMIT);

        List<EvidenceHit> scored = result.candidates().stream().map(c -> {
            VideoChunk chunk = uniqueChunks.get(c.chunkId());
            if (chunk == null) return null;
            EvidenceBounds bounds = EvidenceBounds.of(chunk);
            return new EvidenceHit(bounds.startMs(), bounds.endMs(), c.chunkId(), chunk.segmentSummary(), chunk.keywords(),
                    c.rerankerScore() == null ? c.rrfScore() : c.rerankerScore(), List.of(), result.denseSource(),
                    c.rerankerScore() == null ? EvidenceHit.ScoreType.RRF : EvidenceHit.ScoreType.RERANKER_SIGMOID);
        }).filter(java.util.Objects::nonNull).toList();
        RelevancePolicy.Decision decision = relevancePolicy.evaluate(scored, result.denseSource());

        Map<Long, Integer> perMediaCount = new HashMap<>();
        List<GlobalEvidenceHit> hits = new ArrayList<>();
        for (EvidenceHit candidate : decision.hits()) {
            ChunkOwner owner = ownerByChunkId.get(candidate.chunkId());
            if (owner == null) {
                continue;
            }
            Long mediaId = owner.media().getId();
            int count = perMediaCount.getOrDefault(mediaId, 0);
            if (count >= PER_MEDIA_LIMIT) {
                continue;
            }
            MediaFile media = owner.media();
            VideoChunk chunk = owner.chunk();
            hits.add(new GlobalEvidenceHit(
                    mediaId,
                    displayTitle(media),
                    media.getFilename(),
                    chunk.chunkId(),
                    EvidenceBounds.of(chunk).startMs(),
                    EvidenceBounds.of(chunk).endMs(),
                    chunk.segmentSummary(),
                    chunk.keywords() == null ? List.of() : chunk.keywords()));
            perMediaCount.put(mediaId, count + 1);
            if (hits.size() == limit) {
                break;
            }
        }
        RetrievalAssessment assessment = decision.assessment();
        if (incompleteIndex) assessment = new RetrievalAssessment(RetrievalAssessment.Status.DEGRADED, true,
                "部分视频的索引暂不可用，结果可能不完整。" + assessment.hint());
        return new SearchResponse(List.copyOf(hits), assessment);
    }

    /** Existing list endpoint remains supported; new endpoint includes score-free diagnostics. */
    public record SearchResponse(List<GlobalEvidenceHit> hits, RetrievalAssessment retrieval) {}

    private static String displayTitle(MediaFile media) {
        return media.getTitle() == null || media.getTitle().isBlank()
                ? media.getFilename() : media.getTitle();
    }

    private static List<String> simpleTerms(String query) {
        String safe = query == null ? "" : query.trim();
        List<String> terms = new ArrayList<>();
        for (String term : safe.split("[\\s，。、；：,.!?;:]+")) {
            if (!term.isBlank()) terms.add(term);
        }
        return terms.isEmpty() && !safe.isBlank() ? List.of(safe) : terms;
    }

    private record ChunkOwner(MediaFile media, VideoChunk chunk) {}
}

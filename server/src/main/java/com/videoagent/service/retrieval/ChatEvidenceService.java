package com.videoagent.service.retrieval;

import com.videoagent.dto.ChatEvidence;
import com.videoagent.dto.EvidenceBounds;
import com.videoagent.dto.RetrievalAssessment;
import com.videoagent.dto.RetrievalTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 回读精排命中的最多 5 个原始 Chunk，并合并重叠或相邻证据区间。 */
@Service
public class ChatEvidenceService {

    private static final int MAX_CHUNKS = 5;
    private static final long ADJACENT_GAP_MS = 15_000;
    private final RelevancePolicy relevancePolicy;

    @Autowired
    public ChatEvidenceService(RelevancePolicy relevancePolicy) { this.relevancePolicy = relevancePolicy; }
    public ChatEvidenceService() { this(new RelevancePolicy()); }

    public PreparedEvidence prepare(Long mediaId, VideoEvidenceRetrievalService.PreparedSearch search) {
        RelevancePolicy.Decision decision = relevancePolicy.evaluate(search.hits(), search.denseSource());
        RetrievalTrace trace = search.trace() == null ? null : search.trace().withPolicy(
                relevancePolicy.minScore(), relevancePolicy.rejectLow(), decision.assessment());
        return new PreparedEvidence(build(mediaId, search.chunks(), decision.hits()), decision.hits(),
                decision.assessment(), trace);
    }
    public record PreparedEvidence(List<ChatEvidence> evidence, List<EvidenceHit> hits,
                                   RetrievalAssessment retrieval, RetrievalTrace trace) {}

    public List<ChatEvidence> build(Long mediaId, List<VideoChunk> chunks, List<EvidenceHit> hits) {
        if (chunks == null || hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, VideoChunk> chunksById = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) {
            if (chunk.chunkId() != null) chunksById.put(chunk.chunkId(), chunk);
        }
        Set<String> selectedIds = new LinkedHashSet<>();
        List<VideoChunk> selected = new ArrayList<>();
        for (EvidenceHit hit : hits) {
            if (hit.chunkId() == null || !selectedIds.add(hit.chunkId())) continue;
            VideoChunk chunk = chunksById.get(hit.chunkId());
            if (chunk != null) selected.add(chunk);
            if (selected.size() == MAX_CHUNKS) break;
        }
        selected.sort(Comparator.comparingLong(c -> EvidenceBounds.of(c).startMs()));

        List<Builder> intervals = new ArrayList<>();
        for (VideoChunk chunk : selected) {
            EvidenceBounds bounds = EvidenceBounds.of(chunk);
            Builder current = intervals.isEmpty() ? null : intervals.getLast();
            if (current == null || bounds.startMs() > current.endMs + ADJACENT_GAP_MS) {
                current = new Builder(mediaId, bounds.startMs(), bounds.endMs());
                intervals.add(current);
            }
            current.add(chunk);
        }
        return intervals.stream().map(Builder::freeze).toList();
    }

    public String toPromptText(String videoTitle, List<ChatEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "（没有检索到可用的视频原文证据）";
        }
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            ChatEvidence item = evidence.get(i);
            prompt.append("[证据").append(i + 1).append("]\n")
                    .append("chunkIds: ").append(String.join(",", item.chunkIds())).append('\n')
                    .append("视频: ").append(videoTitle == null ? "" : videoTitle).append('\n')
                    .append("时间: ").append(item.startMs()).append("ms~").append(item.endMs()).append("ms\n")
                    .append("转写原文: ").append(item.quote()).append('\n');
            if (!item.ocrTexts().isEmpty()) {
                prompt.append("OCR文字: ").append(String.join("；", item.ocrTexts())).append('\n');
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private static final class Builder {
        private final Long mediaId;
        private final long startMs;
        private long endMs;
        private final List<String> chunkIds = new ArrayList<>();
        private final Map<String, VideoSegment> segments = new LinkedHashMap<>();
        private final Set<String> transcriptParts = new LinkedHashSet<>();
        private final Set<String> ocrTexts = new LinkedHashSet<>();

        private Builder(Long mediaId, long startMs, long endMs) {
            this.mediaId = mediaId;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        private void add(VideoChunk chunk) {
            chunkIds.add(chunk.chunkId());
            endMs = Math.max(endMs, EvidenceBounds.of(chunk).endMs());
            if (chunk.rawSegments() != null && !chunk.rawSegments().isEmpty()) {
                for (VideoSegment segment : chunk.rawSegments()) {
                    String key = segment.startMs() + ":" + segment.endMs() + ":" + segment.transcript();
                    segments.putIfAbsent(key, segment);
                }
            } else if (chunk.transcript() != null && !chunk.transcript().isBlank()) {
                transcriptParts.add(chunk.transcript().trim());
            }
            if (chunk.visualTexts() != null) {
                chunk.visualTexts().stream()
                        .filter(text -> text != null && !text.isBlank())
                        .map(String::trim)
                        .forEach(ocrTexts::add);
            }
        }

        private ChatEvidence freeze() {
            List<String> quoteParts = new ArrayList<>();
            if (!segments.isEmpty()) {
                segments.values().stream()
                        .sorted(Comparator.comparingLong(VideoSegment::startMs)
                                .thenComparingLong(VideoSegment::endMs))
                        .map(VideoSegment::transcript)
                        .filter(text -> text != null && !text.isBlank())
                        .forEach(quoteParts::add);
            } else {
                quoteParts.addAll(transcriptParts);
            }
            return new ChatEvidence(List.copyOf(chunkIds), mediaId, startMs, endMs,
                    String.join("\n", quoteParts), List.copyOf(ocrTexts));
        }
    }
}

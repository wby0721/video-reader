package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 5 分钟知识块分块（纯函数）：把时序多模态 VideoContext 按 5 分钟窗口聚合为检索单元。
 *
 * <p>窗口边界固定（0-300s、300-600s…），窗口内片段合并语音转写、汇总画面文字，
 * 保留原始片段用于证据回溯。摘要与关键词由 {@link ChunkEnricher} 生成。
 */
public final class VideoChunkingService {

    /** 5 分钟知识块（毫秒） */
    public static final long CHUNK_MS = 5 * 60_000L;

    private VideoChunkingService() {
    }

    public static List<VideoChunk> chunk(VideoContext context) {
        List<VideoSegment> segments = context.segments() == null ? List.of() : context.segments();
        if (segments.isEmpty()) {
            return List.of();
        }
        long minStart = segments.stream().mapToLong(VideoSegment::startMs).min().orElse(0);
        long maxEnd = segments.stream().mapToLong(VideoSegment::endMs).max().orElse(minStart + CHUNK_MS);
        long firstWindow = (minStart / CHUNK_MS) * CHUNK_MS;
        long lastWindow = ((maxEnd + CHUNK_MS - 1) / CHUNK_MS) * CHUNK_MS;

        Map<Long, List<VideoSegment>> byWindow = new LinkedHashMap<>();
        for (VideoSegment seg : segments) {
            long window = (seg.startMs() / CHUNK_MS) * CHUNK_MS;
            byWindow.computeIfAbsent(window, k -> new ArrayList<>()).add(seg);
        }

        List<VideoChunk> chunks = new ArrayList<>();
        for (long w = firstWindow; w <= lastWindow; w += CHUNK_MS) {
            List<VideoSegment> windowSegments = byWindow.getOrDefault(w, List.of());
            if (windowSegments.isEmpty()) {
                continue;
            }
            long start = w;
            long end = windowSegments.stream().mapToLong(VideoSegment::endMs).max().orElse(w + CHUNK_MS);
            StringBuilder transcript = new StringBuilder();
            List<String> visual = new ArrayList<>();
            for (VideoSegment seg : windowSegments) {
                if (seg.transcript() != null && !seg.transcript().isBlank()) {
                    if (!transcript.isEmpty()) {
                        transcript.append(' ');
                    }
                    transcript.append(seg.transcript());
                }
                if (seg.ocrTexts() != null) {
                    visual.addAll(seg.ocrTexts());
                }
            }
            chunks.add(VideoChunk.of(start, end, null, List.of(), transcript.toString(),
                    visual.stream().distinct().toList(), windowSegments, null));
        }
        return chunks;
    }
}

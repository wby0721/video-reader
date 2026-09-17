package com.videoagent.service.retrieval;

import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 重叠知识块分块（纯函数）：把时序多模态 VideoContext 聚合为短检索单元。
 *
 * <p>默认窗口 90 秒、重叠 15 秒。窗口内合并语音转写、汇总画面文字，
 * 保留完整原始片段用于证据回溯。摘要与关键词由 {@link ChunkEnricher} 生成。
 */
public final class VideoChunkingService {

    public static final long CHUNK_MS = 90_000L;
    public static final long OVERLAP_MS = 15_000L;
    public static final long STEP_MS = CHUNK_MS - OVERLAP_MS;
    private static final long MIN_TAIL_MS = 15_000L;

    private VideoChunkingService() {
    }

    public static List<VideoChunk> chunk(VideoContext context, Long userId, String contentHash,
                                         int indexVersion) {
        List<VideoSegment> segments = context.segments() == null ? List.of() : context.segments();
        if (segments.isEmpty()) {
            return List.of();
        }
        long minStart = segments.stream().mapToLong(VideoSegment::startMs).min().orElse(0);
        long maxEnd = segments.stream().mapToLong(VideoSegment::endMs).max().orElse(minStart + CHUNK_MS);
        long firstWindow = (minStart / STEP_MS) * STEP_MS;

        List<VideoChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (long windowStart = firstWindow; windowStart < maxEnd; windowStart += STEP_MS) {
            // 不创建已经被上一窗口完整覆盖的极短尾窗。
            if (windowStart > firstWindow && maxEnd - windowStart <= MIN_TAIL_MS) {
                break;
            }
            long nominalEnd = Math.min(windowStart + CHUNK_MS, maxEnd);
            List<VideoSegment> windowSegments = new ArrayList<>();
            for (VideoSegment segment : segments) {
                if (segment.endMs() > windowStart && segment.startMs() < nominalEnd) {
                    windowSegments.add(segment);
                }
            }
            if (windowSegments.isEmpty()) {
                continue;
            }

            // 单个 ASR 片段跨过窗口边界时保留完整片段，避免切断语句。
            long chunkEnd = Math.max(nominalEnd,
                    windowSegments.stream().mapToLong(VideoSegment::endMs).max().orElse(nominalEnd));
            StringBuilder transcript = new StringBuilder();
            List<String> visualTexts = new ArrayList<>();
            for (VideoSegment segment : windowSegments) {
                if (segment.transcript() != null && !segment.transcript().isBlank()) {
                    if (!transcript.isEmpty()) {
                        transcript.append(' ');
                    }
                    transcript.append(segment.transcript());
                }
                if (segment.ocrTexts() != null) {
                    visualTexts.addAll(segment.ocrTexts());
                }
            }

            String chunkId = chunkId(userId, contentHash, indexVersion, windowStart, chunkEnd);
            chunks.add(VideoChunk.indexed(windowStart, chunkEnd, transcript.toString(),
                    visualTexts.stream().distinct().toList(), windowSegments,
                    chunkId, chunkIndex++, contentHash, indexVersion));
        }
        return chunks;
    }

    static String chunkId(Long userId, String contentHash, int indexVersion,
                          long startMs, long endMs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = userId + ":" + contentHash + ":" + indexVersion + ":" + startMs + ":" + endMs;
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 chunkId", e);
        }
    }
}

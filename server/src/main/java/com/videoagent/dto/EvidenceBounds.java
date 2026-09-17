package com.videoagent.dto;

/** Evidence display boundaries, separate from stable indexing windows/chunk IDs. */
public record EvidenceBounds(long startMs, long endMs) {
    public static EvidenceBounds of(VideoChunk chunk) {
        if (chunk.rawSegments() == null || chunk.rawSegments().isEmpty()) {
            return new EvidenceBounds(chunk.startTime(), chunk.endTime());
        }
        return new EvidenceBounds(
                chunk.rawSegments().stream().mapToLong(VideoSegment::startMs).min().orElse(chunk.startTime()),
                chunk.rawSegments().stream().mapToLong(VideoSegment::endMs).max().orElse(chunk.endTime()));
    }
}

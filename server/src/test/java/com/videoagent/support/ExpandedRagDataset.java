package com.videoagent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.ingest.VideoContextBuilder;
import java.nio.file.*;
import java.util.List;

/** Retrieval-facing DTOs deliberately have NO relevance labels or ground truth. */
public final class ExpandedRagDataset {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static Path root() {
        for (Path p : List.of(Path.of("../test/fixtures/rag/expanded-v1"),
                              Path.of("test/fixtures/rag/expanded-v1"))) {
            if (Files.isRegularFile(p.resolve("manifest.json"))) return p;
        }
        throw new IllegalStateException("Generate expanded-v1 fixtures first");
    }
    public static Manifest load() throws Exception {
        return JSON.readValue(root().resolve("manifest.json").toFile(), Manifest.class);
    }
    public static List<VideoContextBuilder.AsrSeg> asr(Video v) throws Exception {
        return JSON.readValue(root().resolve(v.id()+"/ingest-asr.json").toFile(), new TypeReference<>() {});
    }
    public static List<VideoContextBuilder.OcrFrame> ocr(Video v) throws Exception {
        return JSON.readValue(root().resolve(v.id()+"/ingest-ocr.json").toFile(), new TypeReference<>() {});
    }
    public static VideoContext context(Video v) throws Exception {
        return VideoContext.of(v.mediaId().toString(), "", VideoContextBuilder.align(asr(v), ocr(v), v.durationMs()));
    }
    public record Manifest(int schemaVersion, String provenance, List<Video> videos) {}
    public record Video(String id, String title, long durationMs, Long mediaId, Long userId,
                        String contentHash, List<Question> questions) {}
    public record Question(String id, int turn, String userQuestion, String standaloneQuery) {}
}

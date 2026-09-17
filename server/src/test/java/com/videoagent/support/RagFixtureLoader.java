package com.videoagent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.VideoContext;
import com.videoagent.service.ingest.VideoContextBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 仅供测试代码使用的 RAG 过程文件加载器。
 *
 * <p>过程文件保留在仓库顶层 test/fixtures 下，不进入生产运行 JAR。加载器同时兼容
 * 从仓库根目录和 server 模块目录启动 Maven 的两种方式。</p>
 */
public final class RagFixtureLoader {

    private static final TypeReference<List<VideoContextBuilder.AsrSeg>> ASR_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<VideoContextBuilder.OcrFrame>> OCR_TYPE =
            new TypeReference<>() {};

    private RagFixtureLoader() {
    }

    public static Fixture load20Minutes(ObjectMapper mapper) throws IOException {
        return load(mapper, "20min", 1_200_000L);
    }

    public static Fixture load40Minutes(ObjectMapper mapper) throws IOException {
        return load(mapper, "40min", 2_400_000L);
    }

    /** 加载固定的 20/40 分钟 RAG 黄金评估集。 */
    public static GoldenSet loadGoldenSet(ObjectMapper mapper) throws IOException {
        return mapper.readValue(fixtureRoot().resolve("golden-set.json").toFile(), GoldenSet.class);
    }

    public static GoldenVideo goldenVideo(ObjectMapper mapper, String fixture) throws IOException {
        return loadGoldenSet(mapper).videos().stream()
                .filter(video -> video.fixture().equals(fixture))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("黄金集不存在 fixture: " + fixture));
    }

    private static Fixture load(ObjectMapper mapper, String name, long durationMs) throws IOException {
        Path root = fixtureRoot().resolve(name);
        List<VideoContextBuilder.AsrSeg> asr = mapper.readValue(
                root.resolve("ingest-asr.json").toFile(), ASR_TYPE);
        List<VideoContextBuilder.OcrFrame> ocr = mapper.readValue(
                root.resolve("ingest-ocr.json").toFile(), OCR_TYPE);
        VideoContext storedContext = mapper.readValue(
                root.resolve("video-context.json").toFile(), VideoContext.class);
        return new Fixture(name, durationMs, asr, ocr, storedContext);
    }

    private static Path fixtureRoot() {
        List<Path> candidates = List.of(
                Path.of("..", "test", "fixtures", "rag"),
                Path.of("test", "fixtures", "rag"));
        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到 RAG fixture 目录；请从仓库根目录或 server 模块目录运行测试"));
    }

    public record Fixture(
            String name,
            long durationMs,
            List<VideoContextBuilder.AsrSeg> asr,
            List<VideoContextBuilder.OcrFrame> ocr,
            VideoContext storedContext
    ) {
        /** 使用生产对齐器从原始 ASR/OCR 重新构造上下文。 */
        public VideoContext align(String mediaId, String userGoal) {
            return VideoContext.of(mediaId, userGoal,
                    VideoContextBuilder.align(asr, ocr, durationMs));
        }
    }

    public record GoldenSet(
            int schemaVersion,
            int indexVersion,
            String annotationPolicy,
            List<GoldenVideo> videos
    ) {}

    public record GoldenVideo(
            String fixture,
            long mediaId,
            long userId,
            String contentHash,
            String title,
            List<GoldenQuestion> questions
    ) {}

    public record GoldenQuestion(
            int turn,
            String userQuestion,
            String standaloneQuery,
            List<GoldenChunk> expectedTop7
    ) {}

    public record GoldenChunk(
            int rank,
            String chunkId,
            long startMs,
            long endMs,
            int relevance,
            String reason
    ) {}
}

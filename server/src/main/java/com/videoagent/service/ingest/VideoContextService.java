package com.videoagent.service.ingest;

import com.videoagent.dto.AnalysisTaskMessage;
import com.videoagent.dto.ProcessingTimeline;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VideoSegment;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.MediaService;
import com.videoagent.service.SettingsService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.auth.UserAsrConfigService;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * VideoContext 构建管线（阶段二核心，方案 §6.1）：
 *
 * <pre>
 * 视频文件
 *   ├─ FFmpeg 音频切片 (60s) ──→ 本地 Whisper 语音转写 ──┐
 *   ├─ 场景检测抽关键帧 ──→ 本地 OCR 画面文字 ────────────┼──→ VideoSegment(时间戳+语音+画面文字+帧)
 *   └─ 30s 保底采样（防静态板书遗漏）────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>双分支并行 + 单路失败保留另一路（优雅降级）；</li>
 *   <li>内容级复用：同 contentHash 已有上下文则直接复制 checkpoint，不重复跑推理；</li>
 *   <li>断点恢复：ASR / OCR 分支结果分别落 Checkpoint，Kafka 重投时跳过已完成分支。</li>
 * </ul>
 */
@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);

    public static final String STAGE_INGEST = "INGEST";
    public static final String STAGE_ASR = "ASR";
    public static final String STAGE_OCR = "OCR";
    public static final String STAGE_ALIGN = "ALIGN";
    public static final String STAGE_READY = "READY";
    public static final String STAGE_FAILED = "FAILED";

    /** media 级处理时间线 Checkpoint（与 goal 无关）。 */
    public static final String CP_TIMELINE = "process-timeline";

    private static final TypeReference<List<VideoContextBuilder.AsrSeg>> ASR_SEG_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<VideoContextBuilder.OcrFrame>> OCR_FRAME_TYPE = new TypeReference<>() {};

    private final MediaFileRepository mediaFileRepository;
    private final MediaService mediaService;
    private final FfmpegService ffmpegService;
    private final TranscriptionService transcriptionService;
    private final OcrService ocrService;
    private final CheckpointService checkpointService;
    private final StageEventPublisher events;
    private final RetrievalIndexService retrievalIndexService;
    private final SettingsService settingsService;
    private final UserAsrConfigService userAsrConfigService;

    public VideoContextService(MediaFileRepository mediaFileRepository, MediaService mediaService,
                               FfmpegService ffmpegService, TranscriptionService transcriptionService,
                               OcrService ocrService, CheckpointService checkpointService,
                               StageEventPublisher events, RetrievalIndexService retrievalIndexService,
                               SettingsService settingsService, UserAsrConfigService userAsrConfigService) {
        this.mediaFileRepository = mediaFileRepository;
        this.mediaService = mediaService;
        this.ffmpegService = ffmpegService;
        this.transcriptionService = transcriptionService;
        this.ocrService = ocrService;
        this.checkpointService = checkpointService;
        this.events = events;
        this.retrievalIndexService = retrievalIndexService;
        this.settingsService = settingsService;
        this.userAsrConfigService = userAsrConfigService;
    }

    /** 入口：由 Kafka 消费者调用。 */
    public void process(AnalysisTaskMessage message) {
        MediaFile media = mediaFileRepository.findById(message.mediaId())
                .orElseThrow(() -> new IllegalStateException("媒体不存在: " + message.mediaId()));
        Long mediaId = media.getId();

        if (MediaFile.STATUS_CONTEXT_READY.equals(media.getStatus())) {
            events.publish(mediaId, STAGE_READY, Map.of("message", "上下文已就绪"));
            return;
        }

        // 内容级复用：同 contentHash 已有 READY 上下文 → 直接复制 checkpoint，不重复跑推理
        if (media.getContentHash() != null && tryReuse(media)) {
            return;
        }

        markStatus(media, MediaFile.STATUS_PROCESSING);
        events.publish(mediaId, STAGE_INGEST, Map.of("contentHash", media.getContentHash()));

        long t0 = System.currentTimeMillis();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("ingest-" + mediaId + "-");
            Path video = workDir.resolve("video.mp4");
            try (InputStream in = mediaService.download(media.getFilePath())) {
                Files.copy(in, video);
            }
            long durationMs = ffmpegService.probeDuration(video);
            media.setDurationMs(durationMs);
            mediaFileRepository.save(media);
            long tDownloadEnd = System.currentTimeMillis();

            // 双分支并行抽取（各自容错 + 断点复用）；各自独立计时（避免 join 顺序污染耗时统计）
            final Path wd = workDir;
            long tBranchStart = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicLong asrMs = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong ocrMs = new java.util.concurrent.atomic.AtomicLong();
            CompletableFuture<List<VideoContextBuilder.AsrSeg>> asrFuture =
                    CompletableFuture.supplyAsync(() -> {
                        long s = System.currentTimeMillis();
                        List<VideoContextBuilder.AsrSeg> r = transcribeBranch(mediaId, media.getUserId(), video, wd);
                        asrMs.set(System.currentTimeMillis() - s);
                        return r;
                    });
            CompletableFuture<List<VideoContextBuilder.OcrFrame>> ocrFuture =
                    CompletableFuture.supplyAsync(() -> {
                        long s = System.currentTimeMillis();
                        List<VideoContextBuilder.OcrFrame> r = ocrBranch(mediaId, video, wd, durationMs);
                        ocrMs.set(System.currentTimeMillis() - s);
                        return r;
                    });

            List<VideoContextBuilder.AsrSeg> asr = asrFuture.join();
            List<VideoContextBuilder.OcrFrame> ocr = ocrFuture.join();

            if (asr.isEmpty() && ocr.isEmpty()) {
                throw new IllegalStateException("ASR 与 OCR 双分支均失败，无法产出上下文");
            }

            long tAlignStart = System.currentTimeMillis();
            events.publish(mediaId, STAGE_ALIGN, Map.of("asrSegments", asr.size(), "ocrFrames", ocr.size()));
            List<VideoSegment> segments = VideoContextBuilder.align(asr, ocr, durationMs);
            VideoContext context = VideoContext.of(String.valueOf(mediaId), message.userGoal(), segments);

            checkpointService.saveVideoContext(mediaId, context);
            markStatus(media, MediaFile.STATUS_CONTEXT_READY);
            events.publish(mediaId, STAGE_READY, Map.of("segments", segments.size(), "durationMs", durationMs));
            log.info("VideoContext 就绪 mediaId={} segments={} asr={} ocr={}", mediaId, segments.size(), asr.size(), ocr.size());
            long tAlignEnd = System.currentTimeMillis();

            // 阶段三：建立检索索引（分块 + 摘要 + Embedding → Qdrant；失败自动降级，不阻断主链路）
            long tIndexStart = System.currentTimeMillis();
            retrievalIndexService.index(mediaId, media.getContentHash(), context, media.getUserId());
            long tIndexEnd = System.currentTimeMillis();

            // 记录处理时间线（供视频库展示各步骤耗时；totalMs 为真实墙钟，ASR/OCR 并行不重复计入）
            List<ProcessingTimeline.Step> steps = List.of(
                    new ProcessingTimeline.Step("download", "下载与解析", tDownloadEnd - t0),
                    new ProcessingTimeline.Step("asr", "语音转写 (ASR)", asrMs.get()),
                    new ProcessingTimeline.Step("ocr", "画面文字 (OCR)", ocrMs.get()),
                    new ProcessingTimeline.Step("align", "对齐合并", tAlignEnd - tAlignStart),
                    new ProcessingTimeline.Step("index", "检索索引 (LLM 摘要)", tIndexEnd - tIndexStart));
            checkpointService.save(mediaId, CP_TIMELINE, STAGE_READY,
                    ProcessingTimeline.of(steps, tIndexEnd - t0, "READY"));
        } catch (Exception e) {
            log.error("VideoContext 构建失败 mediaId={}: {}", mediaId, e.getMessage(), e);
            markStatus(media, MediaFile.STATUS_FAILED);
            events.publish(mediaId, STAGE_FAILED, Map.of("error", e.getMessage()));
            checkpointService.save(mediaId, CP_TIMELINE, STAGE_FAILED, ProcessingTimeline.ofStatus("FAILED"));
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** 内容级复用：把「同用户」同内容的已有上下文复制到当前 media，返回是否已复用（跨用户不共享）。 */
    private boolean tryReuse(MediaFile media) {
        Long mediaId = media.getId();
        Optional<MediaFile> existing = mediaFileRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
                        media.getUserId(), media.getContentHash(), MediaFile.STATUS_CONTEXT_READY);
        if (existing.isEmpty() || existing.get().getId().equals(mediaId)) {
            return false;
        }
        Optional<VideoContext> cached = checkpointService.loadVideoContext(existing.get().getId());
        if (cached.isEmpty()) {
            return false;
        }
        checkpointService.saveVideoContext(mediaId, cached.get());
        markStatus(media, MediaFile.STATUS_CONTEXT_READY);
        events.publish(mediaId, STAGE_READY, Map.of("reused", true, "sourceMediaId", existing.get().getId()));
        log.info("内容级复用：media {} 复用 media {} 的预处理结果", mediaId, existing.get().getId());
        // 检索索引同样内容级：点 ID 按 contentHash 派生，复用场景无需重复建索引（懒索引兜底）
        retrievalIndexService.index(mediaId, media.getContentHash(), cached.get(), media.getUserId());
        // 内容复用：不重复推理，时间线标记 REUSED
        checkpointService.save(mediaId, CP_TIMELINE, STAGE_READY, ProcessingTimeline.ofStatus("REUSED"));
        return true;
    }

    /** ASR 分支：断点复用 → 音频切片 → 逐块转写 → 落 Checkpoint。失败返回空列表（保留 OCR 分支）。 */
    private List<VideoContextBuilder.AsrSeg> transcribeBranch(Long mediaId, Long userId, Path video, Path workDir) {
        try {
            Optional<List<VideoContextBuilder.AsrSeg>> cached = checkpointService.load(mediaId, CheckpointService.CP_INGEST_ASR, ASR_SEG_TYPE);
            if (cached.isPresent()) {
                events.publish(mediaId, STAGE_ASR, Map.of("cached", true, "segments", cached.get().size()));
                return cached.get();
            }
            Path wav = ffmpegService.extractAudio(video, workDir);
            // ASR 引擎（个人设置：local / xfyun）
            String engine = settingsService.getAsrEngine();
            boolean xfyun = "xfyun".equals(engine);
            // 讯飞凭据：用户级优先（AES-GCM 解密后仅内存短暂持有），未配置回退服务端环境变量
            final TranscriptionService.XfCreds xf = (xfyun && userId != null)
                    ? userAsrConfigService.get(userId)
                            .map(c -> new TranscriptionService.XfCreds(c.appId(), c.apiKey(), c.apiSecret()))
                            .orElse(null)
                    : null;
            // 讯飞极速转写：切片大小可配置（本地默认 600s；服务器/跨境链路建议调小，
            // 如 ASR_XFYUN_SLICE_MS=180000 → ~5.8MB/片，避免大文件上传超时整片丢失）
            long xfyunSliceMs = 600_000L;
            if (xfyun) {
                String env = System.getenv("ASR_XFYUN_SLICE_MS");
                if (env != null && !env.isBlank()) {
                    try {
                        xfyunSliceMs = Long.parseLong(env.trim());
                    } catch (NumberFormatException ignored) {
                        // 非法值回退默认
                    }
                }
            }
            long sliceMs = xfyun ? xfyunSliceMs : FfmpegService.AUDIO_SLICE_MS;
            List<FfmpegService.AudioChunk> chunks = ffmpegService.sliceAudio(wav, sliceMs);
            int concurrency = xfyun ? 1 : Math.min(4, Math.max(1, chunks.size()));
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<VideoContextBuilder.AsrSeg> segments = new ArrayList<>();
            try {
                List<CompletableFuture<List<VideoContextBuilder.AsrSeg>>> futures = new ArrayList<>();
                for (FfmpegService.AudioChunk chunk : chunks) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        long t0 = System.currentTimeMillis();
                        try {
                            return transcribeSlice(chunk, engine, xf);
                        } catch (Exception e) {
                            long elapsed = System.currentTimeMillis() - t0;
                            log.warn("切片转写失败（保留其余切片）startMs={} 耗时{}ms: {}",
                                    chunk.startMs(), elapsed, e.getMessage());
                            // 只重试「快速失败」（如跨境连接重置，秒级失败）；
                            // 慢超时（轮询耗尽 300s）不重试，避免最坏耗时翻倍
                            if (elapsed >= 120_000) {
                                return List.of();
                            }
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            try {
                                return transcribeSlice(chunk, engine, xf);
                            } catch (Exception e2) {
                                log.warn("切片转写重试仍失败 startMs={}: {}", chunk.startMs(), e2.getMessage());
                                return List.of();
                            }
                        }
                    }, pool));
                }
                for (CompletableFuture<List<VideoContextBuilder.AsrSeg>> f : futures) {
                    segments.addAll(f.join()); // 按切片顺序 join，保持时序
                }
            } finally {
                pool.shutdown();
            }
            segments.sort(Comparator.comparingLong(VideoContextBuilder.AsrSeg::startMs));
            checkpointService.save(mediaId, CheckpointService.CP_INGEST_ASR, STAGE_ASR, segments);
            events.publish(mediaId, STAGE_ASR, Map.of("segments", segments.size(), "chunks", chunks.size()));
            return segments;
        } catch (Exception e) {
            log.error("ASR 分支失败（保留 OCR 分支）: {}", e.getMessage());
            events.publish(mediaId, "ASR_FAILED", Map.of("error", e.getMessage()));
            return List.of();        }
    }

    /** 转写单个音频切片为带时间戳的文本段（记录每片耗时，用于定位讯飞处理瓶颈）。 */
    private List<VideoContextBuilder.AsrSeg> transcribeSlice(FfmpegService.AudioChunk chunk, String engine,
                                                             TranscriptionService.XfCreds xf) {
        long t0 = System.currentTimeMillis();
        List<VideoContextBuilder.AsrSeg> segs = transcriptionService.transcribe(chunk.path(), chunk.startMs(), engine, xf).stream()
                .map(s -> new VideoContextBuilder.AsrSeg(s.startMs(), s.endMs(), s.text()))
                .toList();
        log.info("切片转写完成 startMs={}ms 耗时={}ms 片段数={}",
                chunk.startMs(), System.currentTimeMillis() - t0, segs.size());
        return segs;
    }

    /** OCR 分支：断点复用 → 关键帧抽取（场景检测+保底采样+phash 去重）→ 逐帧识别并上传证据帧。失败返回空列表。 */
    private List<VideoContextBuilder.OcrFrame> ocrBranch(Long mediaId, Path video, Path workDir, long durationMs) {
        try {
            Optional<List<VideoContextBuilder.OcrFrame>> cached = checkpointService.load(mediaId, CheckpointService.CP_INGEST_OCR, OCR_FRAME_TYPE);
            if (cached.isPresent()) {
                events.publish(mediaId, STAGE_OCR, Map.of("cached", true, "frames", cached.get().size()));
                return cached.get();
            }
            List<FfmpegService.FrameCandidate> frames = ffmpegService.extractKeyFrames(video, workDir, durationMs);
            // 逐帧识别并行化（CPU 下 ~2-3s/帧，16 分钟视频 ~34 帧串行 ≈ 80s，4 路并行 ≈ 20s）
            int concurrency = Math.min(4, Math.max(1, frames.size()));
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<VideoContextBuilder.OcrFrame> result = new ArrayList<>();
            try {
                List<CompletableFuture<VideoContextBuilder.OcrFrame>> futures = new ArrayList<>();
                for (FfmpegService.FrameCandidate frame : frames) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            List<String> lines = ocrService.recognize(frame.image());
                            String frameRef = mediaService.putFrame(frame.image(), mediaId + "/" + frame.timestampMs() + ".jpg");
                            return new VideoContextBuilder.OcrFrame(frame.timestampMs(), lines, frameRef);
                        } catch (Exception e) {
                            log.warn("单帧识别失败（跳过该帧）: {}", e.getMessage());
                            return null;
                        }
                    }, pool));
                }
                for (CompletableFuture<VideoContextBuilder.OcrFrame> f : futures) {
                    VideoContextBuilder.OcrFrame fr = f.join(); // 按帧序 join，保持时序
                    if (fr != null) {
                        result.add(fr);
                    }
                }
            } finally {
                pool.shutdown();
            }
            checkpointService.save(mediaId, CheckpointService.CP_INGEST_OCR, STAGE_OCR, result);
            events.publish(mediaId, STAGE_OCR, Map.of("frames", result.size()));
            return result;
        } catch (Exception e) {
            log.error("OCR 分支失败（保留 ASR 分支）: {}", e.getMessage());
            events.publish(mediaId, "OCR_FAILED", Map.of("error", e.getMessage()));
            return List.of();
        }
    }

    private void markStatus(MediaFile media, String status) {
        media.setStatus(status);
        mediaFileRepository.save(media);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}

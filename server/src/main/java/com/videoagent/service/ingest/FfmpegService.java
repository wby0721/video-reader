package com.videoagent.service.ingest;

import com.videoagent.config.AppProperties;
import com.videoagent.utils.ImagePerceptualHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FFmpeg 封装（方案 §6.1）：
 * <ul>
 *   <li>音频切片：60s 一片，16kHz 单声道 wav，供本地 Whisper 转写；</li>
 *   <li>关键帧抽取：场景检测（select scene）+ 30s 保底采样（防静态板书遗漏）+ 感知哈希去重；</li>
 * </ul>
 * 通过 ProcessBuilder 直接调用 ffmpeg 可执行文件（路径由 app.ffmpeg.path 配置）。
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    /** 音频切片 300s：与检索分块粒度（5min）对齐，16 分钟视频仅 4 次 ASR 调用（60s 切片需 17 次）。 */
    public static final long AUDIO_SLICE_MS = 300_000L;
    public static final long FRAME_SAMPLE_MS = 30_000L;
    /** 场景检测阈值：0.3 以上视为场景切换 */
    private static final double SCENE_THRESHOLD = 0.3;
    /** 感知哈希去重阈值：汉明距离小于该值视为重复帧 */
    private static final int PHASH_DUP_THRESHOLD = 8;

    private static final Pattern PTS_PATTERN = Pattern.compile("pts_time:([0-9.]+)");

    private final String ffmpegPath;

    public FfmpegService(AppProperties properties) {
        this.ffmpegPath = properties.ffmpeg().path();
    }

    /** 探测视频时长（毫秒）：仅解析输入头 Duration: 行，不转码，秒回。 */
    public long probeDuration(Path video) throws IOException, InterruptedException {
        String text = runTolerant("-i", video.toString());
        Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.?\\d*)").matcher(text);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            double sec = Double.parseDouble(m.group(3));
            return (long) ((h * 3600 + min * 60 + sec) * 1000);
        }
        throw new IOException("无法解析视频时长: " + text.lines().findFirst().orElse(""));
    }

    /** 提取完整音频：16kHz 单声道 wav。 */
    public Path extractAudio(Path video, Path workDir) throws IOException, InterruptedException {
        Path wav = workDir.resolve("audio.wav");
        run("-y", "-i", video.toString(), "-vn", "-ac", "1", "-ar", "16000", wav.toString());
        return wav;
    }

    /** 按固定时长切片（默认 60s），返回带时间偏移的音频块。 */
    public List<AudioChunk> sliceAudio(Path wav, long sliceMs) throws IOException, InterruptedException {
        long durationMs = probeDuration(wav);
        List<AudioChunk> chunks = new ArrayList<>();
        long start = 0;
        int index = 0;
        while (start < durationMs) {
            long end = Math.min(start + sliceMs, durationMs);
            Path chunk = wav.getParent().resolve("chunk_" + index + ".wav");
            run("-y", "-ss", msToTime(start), "-i", wav.toString(), "-t", String.valueOf((end - start) / 1000.0), chunk.toString());
            chunks.add(new AudioChunk(chunk, start, end));
            start = end;
            index++;
        }
        return chunks;
    }

    /**
     * 关键帧抽取：场景检测帧 + 30s 保底采样帧，按感知哈希去重后返回。
     *
     * @param video      视频文件
     * @param workDir    工作目录
     * @param durationMs 视频时长
     */
    public List<FrameCandidate> extractKeyFrames(Path video, Path workDir, long durationMs) throws IOException, InterruptedException {
        List<Long> timestamps = new ArrayList<>();

        // 1) 场景检测：单次扫描输出场景切换时间点（showinfo 写 stderr，已重定向到日志）
        String sceneText = run("-i", video.toString(),
                "-vf", "select='gt(scene," + SCENE_THRESHOLD + ")',showinfo",
                "-vsync", "vfr", "-f", "null", "-");
        Matcher m = PTS_PATTERN.matcher(sceneText);
        while (m.find()) {
            long ts = (long) (Double.parseDouble(m.group(1)) * 1000);
            timestamps.add(ts);
        }

        // 2) 30s 保底采样（防静态板书遗漏：画面无变化但含关键文字）
        for (long t = 0; t < durationMs; t += FRAME_SAMPLE_MS) {
            timestamps.add(t);
        }

        // 3) 逐帧抽取 + 感知哈希去重
        List<FrameCandidate> frames = new ArrayList<>();
        List<Long> keptHashes = new ArrayList<>();
        for (long ts : timestamps.stream().distinct().sorted().toList()) {
            Path img = workDir.resolve("frame_" + ts + ".jpg");
            run("-y", "-ss", msToTime(ts), "-i", video.toString(),
                    "-frames:v", "1", "-vf", "scale=640:-1", "-q:v", "3", img.toString());
            if (!Files.exists(img) || Files.size(img) == 0) {
                Files.deleteIfExists(img);
                continue;
            }
            long hash;
            try {
                hash = ImagePerceptualHash.averageHash(img);
            } catch (IOException e) {
                log.warn("关键帧 {} 解码失败，跳过: {}", img.getFileName(), e.getMessage());
                Files.deleteIfExists(img);
                continue;
            }
            boolean dup = keptHashes.stream().anyMatch(h -> ImagePerceptualHash.hammingDistance(h, hash) < PHASH_DUP_THRESHOLD);
            if (dup) {
                Files.deleteIfExists(img);
            } else {
                keptHashes.add(hash);
                frames.add(new FrameCandidate(ts, img, hash));
            }
        }
        log.info("关键帧抽取: 候选 {} 个，去重后保留 {} 个", timestamps.size(), frames.size());
        return frames;
    }

    /** 执行 ffmpeg，stdout/stderr 重定向到临时日志文件（避免管道限制），返回日志内容。 */
    private String run(String... args) throws IOException, InterruptedException {
        return runInternal(true, args);
    }

    /** 容错执行：不校验退出码（如仅打印输入头信息时 ffmpeg 会以退出码 1 结束），仍返回日志内容。 */
    private String runTolerant(String... args) throws IOException, InterruptedException {
        return runInternal(false, args);
    }

    private String runInternal(boolean strict, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.addAll(List.of(args));
        Path log = Files.createTempFile("ffmpeg-", ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = pb.start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg 执行超时: " + String.join(" ", cmd));
        }
        String content = Files.readString(log);
        if (strict && process.exitValue() != 0) {
            String tail = content.lines().reduce((a, b) -> b).orElse("");
            throw new IOException("ffmpeg 执行失败 (" + process.exitValue() + "): " + tail);
        }
        return content;
    }

    private static String msToTime(long ms) {
        long totalSec = ms / 1000;
        return String.format("%02d:%02d:%02d.%03d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, ms % 1000);
    }

    /** 音频切片。 */
    public record AudioChunk(Path path, long startMs, long endMs) {}

    /** 关键帧候选（已去重）。 */
    public record FrameCandidate(long timestampMs, Path image, long phash) {}
}

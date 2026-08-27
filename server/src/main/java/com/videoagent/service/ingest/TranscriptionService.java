package com.videoagent.service.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.videoagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

/**
 * ASR 客户端：调用本地 faster-whisper 推理服务（独立部署，HTTP 接口）。
 * 按 60s 音频块转写，返回带绝对时间戳的文本段。
 */
@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final RestClient restClient;

    public TranscriptionService(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(10 * 60_000); // 长音频转写可达分钟级
        this.restClient = RestClient.builder()
                .baseUrl(properties.ai().asr().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 转写单个音频块（使用服务端环境变量凭据）。
     *
     * @param audio    音频文件（wav）
     * @param offsetMs 该块在视频中的起始时间偏移
     * @param engine   ASR 引擎：local（本地）/ xfyun（讯飞在线）
     * @return 绝对时间戳的文本段（startMs/endMs 为视频时间轴）
     */
    public List<Segment> transcribe(Path audio, long offsetMs, String engine) {
        return transcribe(audio, offsetMs, engine, null);
    }

    /**
     * 转写单个音频块（讯飞引擎可透传用户级凭据，覆盖服务端环境变量）。
     *
     * @param xf 用户级讯飞凭据（可为 null，此时用服务端环境变量）
     */
    public List<Segment> transcribe(Path audio, long offsetMs, String engine, XfCreds xf) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audio.toFile()));
        body.add("engine", engine == null || engine.isBlank() ? "local" : engine);
        if (xf != null) {
            body.add("appid", xf.appId());
            body.add("apikey", xf.apiKey());
            body.add("apisecret", xf.apiSecret());
        }
        TranscribeResponse response;
        try {
            response = restClient.post()
                    .uri("/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(TranscribeResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("ASR 服务调用失败: " + e.getMessage(), e);
        }
        if (response == null || response.segments() == null) {
            return List.of();
        }
        return response.segments().stream()
                .map(s -> new Segment(
                        offsetMs + (long) (s.start() * 1000),
                        offsetMs + (long) (s.end() * 1000),
                        s.text().strip()))
                .filter(s -> !s.text().isBlank())
                .toList();
    }

    /** 讯飞凭据（用户级，随请求透传，仅内存短暂持有）。 */
    public record XfCreds(String appId, String apiKey, String apiSecret) {}

    /** 转写结果段（相对音频块的时间秒）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TranscribeResponse(List<RawSegment> segments) {
        public record RawSegment(double start, double end, String text) {}
    }

    /** 视频时间轴上的文本段。 */
    public record Segment(long startMs, long endMs, String text) {}
}

package com.videoagent.controller;

import com.videoagent.common.ApiResponse;
import com.videoagent.common.BusinessException;
import com.videoagent.dto.BatchDeleteRequest;
import com.videoagent.dto.MediaDto;
import com.videoagent.dto.MediaTitleRequest;
import com.videoagent.dto.MediaUploadCompleteRequest;
import com.videoagent.dto.MediaUploadCompleteResponse;
import com.videoagent.dto.MediaUploadInitRequest;
import com.videoagent.dto.MediaUploadInitResponse;
import com.videoagent.dto.MediaUploadPartResponse;
import com.videoagent.dto.ProcessingTimeline;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.MediaService;
import com.videoagent.service.MediaTitleService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.service.ingest.VideoContextService;
import com.videoagent.utils.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 媒体接口（需鉴权）：MinIO 分片上传 + 断点续传（init → chunk → complete/abort）。
 * 所有资源按 userId 归属校验（数据隔离）。
 */
@RestController
@RequestMapping("/media")
@Validated
public class MediaController {

    private final MediaService mediaService;
    private final MediaFileRepository mediaFileRepository;
    private final CheckpointService checkpointService;
    private final StageEventPublisher events;
    private final MediaTitleService mediaTitleService;
    private final RateLimitService rateLimitService;

    public MediaController(MediaService mediaService, MediaFileRepository mediaFileRepository,
                           CheckpointService checkpointService, StageEventPublisher events,
                           MediaTitleService mediaTitleService, RateLimitService rateLimitService) {
        this.mediaService = mediaService;
        this.mediaFileRepository = mediaFileRepository;
        this.checkpointService = checkpointService;
        this.events = events;
        this.mediaTitleService = mediaTitleService;
        this.rateLimitService = rateLimitService;
    }

    /** 1) 初始化分片上传。 */
    @PostMapping("/upload/init")
    public ApiResponse<MediaUploadInitResponse> initUpload(@Valid @RequestBody MediaUploadInitRequest request,
                                                           HttpServletRequest http) {
        return ApiResponse.ok(mediaService.initUpload(CurrentUser.userId(http), request));
    }

    /** 2) 上传分片（请求体为原始字节流）。 */
    @PostMapping("/upload/chunk")
    public ApiResponse<MediaUploadPartResponse> uploadChunk(
            @RequestParam @NotBlank(message = "uploadId 不能为空") String uploadId,
            @RequestParam @Min(value = 1, message = "partNumber 从 1 开始")
            @Max(value = 10000, message = "partNumber 最大 10000") int partNumber,
            @RequestBody byte[] data,
            HttpServletRequest http) {
        if (data == null || data.length == 0) {
            throw new BusinessException(400, "分片内容不能为空");
        }
        return ApiResponse.ok(mediaService.uploadPart(CurrentUser.userId(http), uploadId, partNumber, data));
    }

    /** 3) 合并分片，完成上传。 */
    @PostMapping("/upload/complete")
    public ApiResponse<MediaUploadCompleteResponse> completeUpload(@Valid @RequestBody MediaUploadCompleteRequest request,
                                                                  HttpServletRequest http) {
        return ApiResponse.ok(mediaService.completeUpload(CurrentUser.userId(http), request.uploadId(), request.parts()));
    }

    /** 4) 中止上传。 */
    @PostMapping("/upload/abort")
    public ApiResponse<Void> abortUpload(@RequestParam @NotBlank String uploadId, HttpServletRequest http) {
        mediaService.abortUpload(CurrentUser.userId(http), uploadId);
        return ApiResponse.ok();
    }

    /** 媒体详情。 */
    @GetMapping("/{mediaId}")
    public ApiResponse<MediaDto> getMedia(@PathVariable Long mediaId, HttpServletRequest http) {
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, CurrentUser.userId(http))
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return ApiResponse.ok(toDetail(media));
    }

    /** 我的媒体列表（附进度 + 各步骤耗时）。 */
    @GetMapping("/list")
    public ApiResponse<List<MediaDto>> listMedia(HttpServletRequest http) {
        List<MediaDto> list = mediaFileRepository.findByUserIdOrderByCreatedAtDesc(CurrentUser.userId(http))
                .stream().map(this::toDetail).toList();
        return ApiResponse.ok(list);
    }

    /** 批量删除媒体及关联数据（列表勾选 / 工作台单条删除共用）。 */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody BatchDeleteRequest request,
                                                        HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        int deleted = 0;
        List<Long> done = new ArrayList<>();
        for (Long id : request.ids()) {
            try {
                mediaService.delete(userId, id);
                deleted++;
                done.add(id);
            } catch (BusinessException e) {
                // 404 = 已被删除/不属于当前用户，静默跳过；其他错误上抛
                if (e.getCode() != 404) {
                    throw e;
                }
            }
        }
        return ApiResponse.ok(Map.of("deleted", deleted, "ids", done));
    }

    /** 修改视频标题（空串清除标题）。 */
    @PutMapping("/{mediaId}/title")
    public ApiResponse<MediaDto> updateTitle(@PathVariable Long mediaId,
                                             @Valid @RequestBody MediaTitleRequest request,
                                             HttpServletRequest http) {
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, CurrentUser.userId(http))
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        String t = request.title() == null ? null : request.title().strip();
        media.setTitle(t == null || t.isBlank() ? null : t);
        mediaFileRepository.save(media);
        return ApiResponse.ok(toDetail(media));
    }

    /** 自动生成标题：标题为空时用最近一次分析结果生成（用户改过则不覆盖）。 */
    @PostMapping("/{mediaId}/title/auto")
    public ApiResponse<MediaDto> autoTitle(@PathVariable Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        // 成本护栏：标题自动生成消耗一次 LLM 调用，与提交分析/追问共用令牌桶额度
        if (!rateLimitService.tryAcquireUser(userId)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        if (!rateLimitService.tryAcquireGlobal()) {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }
        mediaTitleService.autoGenerate(mediaId, userId);
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return ApiResponse.ok(toDetail(media));
    }

    /** 组装列表展示字段：进度（按当前阶段估算）+ 处理时间线 + 完整处理耗时。 */
    private MediaDto toDetail(MediaFile m) {
        StageEventPublisher.StageEvent ev = events.current(m.getId());
        String stage = ev != null ? ev.stage() : null;
        Integer progress = progress(m, stage);
        ProcessingTimeline tl = checkpointService
                .load(m.getId(), VideoContextService.CP_TIMELINE, ProcessingTimeline.class)
                .orElse(null);
        List<ProcessingTimeline.Step> steps = tl != null ? tl.steps() : null;
        Long totalMs = tl != null ? tl.totalMs()
                : (m.getUpdatedAt() != null && m.getCreatedAt() != null
                        ? Duration.between(m.getCreatedAt(), m.getUpdatedAt()).toMillis() : null);
        return MediaDto.detail(m, stage, progress, steps, totalMs);
    }

    /** 阶段 → 进度百分比（处理中记录条进度条用）。 */
    private Integer progress(MediaFile m, String stage) {
        if (MediaFile.STATUS_CONTEXT_READY.equals(m.getStatus())) {
            return 100;
        }
        if (!MediaFile.STATUS_PROCESSING.equals(m.getStatus()) || stage == null) {
            return null;
        }
        return switch (stage) {
            case "SUBMITTED" -> 5;
            case "INGEST" -> 15;
            case "ASR" -> 40;
            case "OCR" -> 55;
            case "ALIGN" -> 75;
            case "READY" -> 100;
            default -> stage.startsWith("AGENT") ? 88 : 80;
        };
    }

    /**
     * 视频流（播放器用，需鉴权）：按文件后缀设置 Content-Type，完整流式返回 MinIO 对象。
     * 前端以 fetch+blob 播放；时间戳跳转由前端 currentTime 控制。
     */
    @GetMapping("/{mediaId}/stream")
    public void stream(@PathVariable Long mediaId, HttpServletRequest http, HttpServletResponse response) throws Exception {
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, CurrentUser.userId(http))
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        String contentType = contentTypes.getOrDefault(
                media.getFilename().substring(media.getFilename().lastIndexOf('.') + 1).toLowerCase(),
                "application/octet-stream");
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        try (InputStream in = mediaService.download(media.getFilePath());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    private static final Map<String, String> contentTypes = Map.of(
            "mp4", "video/mp4", "webm", "video/webm", "mov", "video/quicktime",
            "mkv", "video/x-matroska", "avi", "video/x-msvideo", "mp3", "audio/mpeg", "m4v", "video/mp4");
}

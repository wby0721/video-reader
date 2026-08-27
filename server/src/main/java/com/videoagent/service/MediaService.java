package com.videoagent.service;

import com.videoagent.common.BusinessException;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.MediaUploadCompleteRequest;
import com.videoagent.dto.MediaUploadCompleteResponse;
import com.videoagent.dto.MediaUploadInitRequest;
import com.videoagent.dto.MediaUploadInitResponse;
import com.videoagent.dto.MediaUploadPartResponse;
import com.videoagent.entity.AgentCheckpoint;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.AgentCheckpointRepository;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.repository.FailedAnalysisTaskRepository;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.StageEventPublisher;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 媒体服务：MinIO（S3 兼容）分片上传 + 断点续传（Redis 保存 pending 状态）+ 内容级去重（contentHash）。
 *
 * <p>分片上传流程：init（createMultipartUpload + 记录 pending）→ chunk（uploadPart，可断点续传）→
 * complete（completeMultipartUpload + 计算 contentHash + 落 media_files）；abort 清理。
 * 同一视频重复上传时按 contentHash 复用预处理（见 VideoContextService）。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final String PENDING_UPLOADS = "media:pending-uploads";

    private final S3Client s3Client;
    private final AppProperties properties;
    private final RedissonClient redisson;
    private final MediaFileRepository mediaFileRepository;
    private final AgentCheckpointRepository agentCheckpointRepository;
    private final AnalysisFeedbackRepository feedbackRepository;
    private final FailedAnalysisTaskRepository failedTaskRepository;
    private final StageEventPublisher events;

    public MediaService(S3Client s3Client, AppProperties properties,
                        RedissonClient redisson, MediaFileRepository mediaFileRepository,
                        AgentCheckpointRepository agentCheckpointRepository,
                        AnalysisFeedbackRepository feedbackRepository,
                        FailedAnalysisTaskRepository failedTaskRepository,
                        StageEventPublisher events) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.redisson = redisson;
        this.mediaFileRepository = mediaFileRepository;
        this.agentCheckpointRepository = agentCheckpointRepository;
        this.feedbackRepository = feedbackRepository;
        this.failedTaskRepository = failedTaskRepository;
        this.events = events;
    }

    private String bucket() {
        return properties.minio().bucket();
    }

    /** 1) 初始化分片上传。 */
    public MediaUploadInitResponse initUpload(Long userId, MediaUploadInitRequest request) {
        String safeName = request.filename().replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
        String objectKey = "media/" + userId + "/" + UUID.randomUUID() + "-" + safeName;
        try {
            String uploadId = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                            .bucket(bucket())
                            .key(objectKey)
                            .build())
                    .uploadId();
            PendingUpload pending = new PendingUpload(userId, objectKey, request.filename(), request.totalSize());
            redisson.getMap(PENDING_UPLOADS).put(uploadId, pending);
            log.info("init upload userId={} uploadId={} object={}", userId, uploadId, objectKey);
            return new MediaUploadInitResponse(uploadId, objectKey, bucket());
        } catch (Exception e) {
            throw new BusinessException(500, "初始化上传失败: " + e.getMessage());
        }
    }

    /** 2) 上传单个分片（支持断点续传：任意顺序、任意次数重传，MinIO 以相同 partNumber 覆盖）。 */
    public MediaUploadPartResponse uploadPart(Long userId, String uploadId, int partNumber, byte[] data) {
        PendingUpload pending = getPending(uploadId, userId);
        try {
            String etag = s3Client.uploadPart(UploadPartRequest.builder()
                            .bucket(bucket())
                            .key(pending.objectKey())
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .build(),
                    RequestBody.fromBytes(data))
                    .eTag();
            // S3 的 ETag 头部带引号，complete 阶段需要不带引号的原始值
            String cleanEtag = etag.replace("\"", "");
            return new MediaUploadPartResponse(partNumber, cleanEtag);
        } catch (Exception e) {
            throw new BusinessException(500, "上传分片失败: " + e.getMessage());
        }
    }

    /** 3) 合并分片，计算 contentHash，落 media_files。 */
    @Transactional
    public MediaUploadCompleteResponse completeUpload(Long userId, String uploadId,
                                                      List<MediaUploadCompleteRequest.PartInfo> parts) {
        PendingUpload pending = getPending(uploadId, userId);
        try {
            List<CompletedPart> completed = parts.stream()
                    .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
                    .toList();
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(pending.objectKey())
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
            redisson.getMap(PENDING_UPLOADS).remove(uploadId);
        } catch (Exception e) {
            throw new BusinessException(500, "合并分片失败: " + e.getMessage());
        }

        String contentHash = computeContentHash(pending.objectKey());

        // 内容级复用检测（仅同用户、同内容）：同 contentHash 已产出上下文且确实存在 video-context
        // Checkpoint 的媒体才作为复用源。跨用户不共享——用户的聊天/分析等数据必须相互独立；
        // 同时避免复制空壳（如早期无 Checkpoint 的 CONTEXT_READY 记录）。
        MediaFile source = mediaFileRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
                        userId, contentHash, MediaFile.STATUS_CONTEXT_READY)
                .filter(m -> agentCheckpointRepository
                        .findByMediaIdAndCheckpointName(m.getId(), CheckpointService.CP_VIDEO_CONTEXT)
                        .isPresent())
                .orElse(null);
        boolean reused = source != null;

        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setFilename(pending.filename());
        media.setFilePath(pending.objectKey());
        media.setContentHash(contentHash);
        media.setStatus(reused ? MediaFile.STATUS_CONTEXT_READY : MediaFile.STATUS_UPLOADED);
        mediaFileRepository.save(media);

        // 复用：把源媒体的内容级 Checkpoint（video-context / ingest-asr / ingest-ocr）复制到新 media，
        // 使新 media 立即可用（分析提交无需重新跑推理）
        if (reused && source != null) {
            copyCheckpointsFrom(source.getId(), media.getId());
        }

        log.info("upload complete mediaId={} hash={} reused={}", media.getId(), contentHash, reused);
        return new MediaUploadCompleteResponse(media.getId(), contentHash, media.getStatus(), reused);
    }

    /**
     * 内容级复用白名单：仅复制与「视频内容」强绑定的预处理 Checkpoint（上下文 / ASR / OCR 断点）。
     * 用户级数据绝不跨记录复制：聊天（media-chat）、分析结果（goal-*）、处理时间线（process-timeline）、
     * 反馈等——同一用户重传时分析也应基于当前代码重新生成，而不是沿用旧记录的结果。
     */
    private static final Set<String> CONTENT_LEVEL_CHECKPOINTS = Set.of(
            CheckpointService.CP_VIDEO_CONTEXT,
            CheckpointService.CP_INGEST_ASR,
            CheckpointService.CP_INGEST_OCR);

    /** 复制源媒体的内容级 Checkpoint 到目标媒体（仅白名单，不复制用户数据）。 */
    private void copyCheckpointsFrom(Long sourceMediaId, Long targetMediaId) {
        List<AgentCheckpoint> checkpoints = agentCheckpointRepository.findByMediaId(sourceMediaId);
        int copied = 0, skipped = 0;
        for (AgentCheckpoint cp : checkpoints) {
            if (!CONTENT_LEVEL_CHECKPOINTS.contains(cp.getCheckpointName())) {
                skipped++;
                continue;
            }
            AgentCheckpoint target = agentCheckpointRepository
                    .findByMediaIdAndCheckpointName(targetMediaId, cp.getCheckpointName())
                    .orElseGet(AgentCheckpoint::new);
            target.setMediaId(targetMediaId);
            target.setCheckpointName(cp.getCheckpointName());
            target.setStage(cp.getStage());
            target.setPayload(cp.getPayload());
            agentCheckpointRepository.save(target);
            copied++;
        }
        log.info("内容级复用：media {} 复制 {} 个内容级 Checkpoint 到 media {}（跳过用户级 {} 个）",
                sourceMediaId, copied, targetMediaId, skipped);
    }

    /** 4) 中止上传并清理。 */
    public void abortUpload(Long userId, String uploadId) {
        PendingUpload pending = getPending(uploadId, userId);
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(pending.objectKey())
                    .uploadId(uploadId)
                    .build());
        } catch (Exception e) {
            log.warn("abort upload failed: {}", e.getMessage());
        }
        redisson.getMap(PENDING_UPLOADS).remove(uploadId);
    }

    /** 下载对象为流（ingest 使用）。 */
    public InputStream download(String objectKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket()).key(objectKey).build());
        } catch (Exception e) {
            throw new BusinessException(500, "下载对象失败: " + e.getMessage());
        }
    }

    /** 上传关键帧图片（证据帧引用），返回对象名。 */
    public String putFrame(Path image, String frameKey) {
        String objectKey = "frames/" + frameKey;
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket())
                            .key(objectKey)
                            .contentType("image/jpeg")
                            .build(),
                    RequestBody.fromFile(image));
            return objectKey;
        } catch (Exception e) {
            throw new BusinessException(500, "上传关键帧失败: " + e.getMessage());
        }
    }

    /**
     * 删除媒体记录及全部关联数据：MinIO 视频对象 + 证据帧、agent_checkpoint、
     * analysis_feedback、failed_analysis_tasks、Redis last-goal、SSE 订阅。
     * 注：Qdrant 向量点按 contentHash 内容级共享，不随单条媒体删除（同内容复用仍需要）。
     */
    @Transactional
    public void delete(Long userId, Long mediaId) {
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(media.getFilePath()).build());
        } catch (Exception e) {
            log.warn("删除视频对象失败 mediaId={} key={}: {}", mediaId, media.getFilePath(), e.getMessage());
        }
        deleteObjectsWithPrefix("frames/" + mediaId + "/");

        agentCheckpointRepository.deleteByMediaId(mediaId);
        feedbackRepository.deleteByMediaId(mediaId);
        failedTaskRepository.deleteByMediaId(mediaId);
        mediaFileRepository.delete(media);

        // Redis：最近目标映射（AnalysisController.LAST_GOAL_KEY）
        redisson.getMap("analysis:last-goal").remove(mediaId);
        // SSE：清理最新阶段并完成订阅
        events.remove(mediaId);
        log.info("媒体已删除 mediaId={} userId={}", mediaId, userId);
    }

    /** 删除 MinIO 指定前缀下的全部对象（证据帧）。 */
    private void deleteObjectsWithPrefix(String prefix) {
        try {
            List<ObjectIdentifier> keys = new ArrayList<>();
            String token = null;
            do {
                ListObjectsV2Response resp = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket()).prefix(prefix)
                        .continuationToken(token).build());
                resp.contents().forEach(o -> keys.add(ObjectIdentifier.builder().key(o.key()).build()));
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);
            if (keys.isEmpty()) {
                return;
            }
            for (int i = 0; i < keys.size(); i += 1000) {
                s3Client.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket())
                        .delete(Delete.builder()
                                .objects(keys.subList(i, Math.min(i + 1000, keys.size())))
                                .build())
                        .build());
            }
        } catch (Exception e) {
            log.warn("删除 MinIO 前缀失败 {}: {}", prefix, e.getMessage());
        }
    }

    private PendingUpload getPending(String uploadId, Long userId) {
        PendingUpload pending = (PendingUpload) redisson.getMap(PENDING_UPLOADS).get(uploadId);
        if (pending == null) {
            throw new BusinessException(404, "上传会话不存在或已过期");
        }
        if (!pending.userId().equals(userId)) {
            throw new BusinessException(403, "无权操作该上传会话");
        }
        return pending;
    }

    private String computeContentHash(String objectKey) {
        try (InputStream in = download(objectKey)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new BusinessException(500, "计算内容指纹失败: " + e.getMessage());
        }
    }

    /** 上传会话状态（Redis）。 */
    public record PendingUpload(Long userId, String objectKey, String filename, long totalSize) {}
}

package com.videoagent.service;

import com.videoagent.config.AppProperties;
import com.videoagent.dto.MediaUploadCompleteRequest;
import com.videoagent.dto.MediaUploadCompleteResponse;
import com.videoagent.entity.AgentCheckpoint;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.AgentCheckpointRepository;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.repository.FailedAnalysisTaskRepository;
import com.videoagent.repository.MediaFileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容级复用隔离回归测试（v6.2 修复）：
 * 1) 复用源查找限定同 userId——不同用户的媒体记录与用户数据（聊天/分析等）相互独立；
 * 2) checkpoint 复制仅限内容级白名单（video-context / ingest-asr / ingest-ocr），
 *    聊天（media-chat）、分析结果（goal-*）、处理时间线（process-timeline）绝不跨记录复制；
 * 3) 无同用户复用源 → 全新记录，不复制任何 checkpoint。
 */
class MediaServiceReuseTest {

    private static final long USER_A = 7L;
    private static final long USER_B = 8L;
    private static final long SOURCE_ID = 100L;
    /** completeUpload 对 mock 对象字节算出的真实 SHA-256（"same-video-bytes"）。 */
    private static final String HASH = "0b0cc1116db722a4e7af6a49752fa9a418de5e6fec333b20cbc9adf12d6c2919";

    private final S3Client s3 = mock(S3Client.class);
    private final AppProperties props = mock(AppProperties.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final MediaFileRepository mediaRepo = mock(MediaFileRepository.class);
    private final AgentCheckpointRepository cpRepo = mock(AgentCheckpointRepository.class);
    private final MediaService service = new MediaService(s3, props, redisson, mediaRepo, cpRepo,
            mock(AnalysisFeedbackRepository.class), mock(FailedAnalysisTaskRepository.class),
            mock(StageEventPublisher.class));
    private final Map<String, Object> pendingBacking = new HashMap<>();

    private void mockInfra() {
        when(props.minio()).thenReturn(new AppProperties.Minio("ep", "ak", "sk", "test-bucket"));
        // S3Client.getObject 的返回类型是 ResponseInputStream（调用点有 checkcast），必须构造真实实例
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        new ByteArrayInputStream("same-video-bytes".getBytes(StandardCharsets.UTF_8))));
        RMap<String, Object> pending = mock(RMap.class);
        when(pending.get(anyString())).thenAnswer(inv -> pendingBacking.get(inv.getArgument(0)));
        doAnswer(inv -> { pendingBacking.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(pending).put(anyString(), any());
        doAnswer(inv -> { pendingBacking.remove(inv.getArgument(0)); return null; }).when(pending).remove(anyString());
        when(redisson.getMap(anyString())).thenAnswer(inv -> pending);
        when(mediaRepo.save(any(MediaFile.class))).thenAnswer(inv -> {
            MediaFile m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(500L); // 模拟自增主键
            }
            return m;
        });
    }

    /** 预置上传会话：completeUpload 首先从 Redis 读取 pending（校验 userId）。 */
    private void seedPending(Long userId, String uploadId) {
        pendingBacking.put(uploadId, new MediaService.PendingUpload(userId, "media/" + userId + "/x.mp4", "x.mp4", 100L));
    }

    private static MediaFile sourceMedia(Long userId) {
        MediaFile m = new MediaFile();
        m.setId(SOURCE_ID);
        m.setUserId(userId);
        m.setContentHash(HASH);
        m.setStatus(MediaFile.STATUS_CONTEXT_READY);
        return m;
    }

    private static AgentCheckpoint cp(String name) {
        AgentCheckpoint c = new AgentCheckpoint();
        c.setMediaId(SOURCE_ID);
        c.setCheckpointName(name);
        c.setStage("DONE");
        c.setPayload("{}");
        return c;
    }

    private MediaUploadCompleteResponse complete(Long userId, String uploadId) {
        return service.completeUpload(userId, uploadId, List.of());
    }

    @Test
    void sameUserReuse_copiesOnlyContentLevelCheckpoints() {
        mockInfra();
        seedPending(USER_A, "upload-1");
        when(mediaRepo.findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
                eq(USER_A), eq(HASH), eq(MediaFile.STATUS_CONTEXT_READY)))
                .thenReturn(Optional.of(sourceMedia(USER_A)));
        // 通用空桩先声明，具体桩后声明（Mockito 匹配同参数时后声明的优先）
        when(cpRepo.findByMediaIdAndCheckpointName(anyLong(), anyString())).thenReturn(Optional.empty());
        when(cpRepo.findByMediaIdAndCheckpointName(eq(SOURCE_ID), eq("video-context")))
                .thenReturn(Optional.of(cp("video-context")));
        when(cpRepo.findByMediaId(SOURCE_ID)).thenReturn(List.of(
                cp("video-context"), cp("ingest-asr"), cp("ingest-ocr"),
                cp("media-chat"), cp("goal-abc123-final"), cp("process-timeline")));

        MediaUploadCompleteResponse resp = complete(USER_A, "upload-1");

        assertThat(resp.reused()).isTrue();
        assertThat(resp.status()).isEqualTo(MediaFile.STATUS_CONTEXT_READY);

        // 只复制内容级白名单，用户级 checkpoint 一律跳过
        ArgumentCaptor<AgentCheckpoint> captor = ArgumentCaptor.forClass(AgentCheckpoint.class);
        verify(cpRepo, times(3)).save(captor.capture());
        List<String> copied = captor.getAllValues().stream().map(AgentCheckpoint::getCheckpointName).toList();
        assertThat(copied).containsExactlyInAnyOrder("video-context", "ingest-asr", "ingest-ocr");
        assertThat(copied).noneMatch(n -> n.startsWith("media-chat") || n.startsWith("goal-") || n.equals("process-timeline"));
        // 复制目标必须是新 media（不是源 media）
        assertThat(captor.getAllValues()).allMatch(c -> !c.getMediaId().equals(SOURCE_ID));
    }

    @Test
    void crossUserReuse_isNotShared() {
        mockInfra();
        seedPending(USER_B, "upload-2");
        // 用户 B 上传与用户 A 相同的视频：同用户维度查不到复用源 → 全新处理，不复制任何 checkpoint
        when(mediaRepo.findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
                eq(USER_B), eq(HASH), eq(MediaFile.STATUS_CONTEXT_READY)))
                .thenReturn(Optional.empty());

        MediaUploadCompleteResponse resp = complete(USER_B, "upload-2");

        assertThat(resp.reused()).isFalse();
        assertThat(resp.status()).isEqualTo(MediaFile.STATUS_UPLOADED);
        verify(cpRepo, never()).save(any());
        verify(cpRepo, never()).findByMediaId(anyLong());
    }

    @Test
    void reuseLookup_isScopedToCallingUser() {
        mockInfra();
        seedPending(USER_A, "upload-3");
        when(mediaRepo.findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        complete(USER_A, "upload-3");

        verify(mediaRepo).findFirstByUserIdAndContentHashAndStatusOrderByIdDesc(
                eq(USER_A), eq(HASH), eq(MediaFile.STATUS_CONTEXT_READY));
    }
}

package com.videoagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.dto.VideoContext;
import com.videoagent.entity.AgentCheckpoint;
import com.videoagent.repository.AgentCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Checkpoint 服务（方案 §9.3）：MySQL agent_checkpoint 为恢复真源。
 *
 * <p>阶段二使用：video-context（最终 VideoContext）、ingest-asr（ASR 分支结果）、
 * ingest-ocr（OCR 分支结果）——Kafka 重投时跳过已完成分支，不重复烧推理。
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    /** 内容级 VideoContext 检查点（与 goal 无关，仅依赖 contentHash） */
    public static final String CP_VIDEO_CONTEXT = "video-context";
    public static final String CP_INGEST_ASR = "ingest-asr";
    public static final String CP_INGEST_OCR = "ingest-ocr";
    public static final String STAGE_READY = "READY";

    private final AgentCheckpointRepository repository;
    private final ObjectMapper objectMapper;

    public CheckpointService(AgentCheckpointRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<VideoContext> loadVideoContext(Long mediaId) {
        return load(mediaId, CP_VIDEO_CONTEXT, VideoContext.class);
    }

    @Transactional
    public void saveVideoContext(Long mediaId, VideoContext context) {
        save(mediaId, CP_VIDEO_CONTEXT, STAGE_READY, context);
    }

    public <T> Optional<T> load(Long mediaId, String name, Class<T> type) {
        return repository.findByMediaIdAndCheckpointName(mediaId, name)
                .map(cp -> {
                    try {
                        return objectMapper.readValue(cp.getPayload(), type);
                    } catch (JsonProcessingException e) {
                        log.warn("Checkpoint {}/{} 反序列化失败: {}", mediaId, name, e.getMessage());
                        return null;
                    }
                });
    }

    /** 泛型容器（如 List<...>）反序列化。 */
    public <T> Optional<T> load(Long mediaId, String name, TypeReference<T> type) {
        return repository.findByMediaIdAndCheckpointName(mediaId, name)
                .map(cp -> {
                    try {
                        return objectMapper.readValue(cp.getPayload(), type);
                    } catch (JsonProcessingException e) {
                        log.warn("Checkpoint {}/{} 反序列化失败: {}", mediaId, name, e.getMessage());
                        return null;
                    }
                });
    }

    @Transactional
    public void save(Long mediaId, String name, String stage, Object payload) {
        AgentCheckpoint cp = repository.findByMediaIdAndCheckpointName(mediaId, name)
                .orElseGet(() -> {
                    AgentCheckpoint n = new AgentCheckpoint();
                    n.setMediaId(mediaId);
                    n.setCheckpointName(name);
                    return n;
                });
        cp.setStage(stage);
        cp.setPayload(toJson(payload));
        repository.save(cp);
    }

    public boolean exists(Long mediaId, String name) {
        return repository.findByMediaIdAndCheckpointName(mediaId, name).isPresent();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint 序列化失败", e);
        }
    }
}

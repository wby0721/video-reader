package com.videoagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 流水线阶段事件发布（SSE）：按 mediaId 推送阶段进度（INGEST/ASR/OCR/ALIGN/READY/FAILED）。
 * 单节点内存实现；多实例部署时可替换为 Redis pub/sub。
 */
@Service
public class StageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StageEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, StageEvent> latest = new ConcurrentHashMap<>();

    public StageEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 订阅指定 mediaId 的阶段事件；立即补发当前最新阶段。 */
    public SseEmitter subscribe(Long mediaId) {
        SseEmitter emitter = new SseEmitter(0L); // 不自动超时
        subscribers.computeIfAbsent(mediaId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(mediaId, emitter));
        emitter.onTimeout(() -> remove(mediaId, emitter));
        emitter.onError(e -> remove(mediaId, emitter));

        StageEvent current = latest.get(mediaId);
        if (current != null) {
            send(emitter, current);
        }
        return emitter;
    }

    public void publish(Long mediaId, String stage, Object payload) {
        StageEvent event = new StageEvent(stage, payload);
        latest.put(mediaId, event);
        List<SseEmitter> list = subscribers.get(mediaId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(emitter, event);
        }
    }

    public StageEvent current(Long mediaId) {
        return latest.get(mediaId);
    }

    /** 媒体删除时清理：移除最新阶段并完成所有订阅（避免悬挂 SSE 连接）。 */
    public void remove(Long mediaId) {
        latest.remove(mediaId);
        List<SseEmitter> list = subscribers.remove(mediaId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void send(SseEmitter emitter, StageEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.stage()).data(objectMapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void remove(Long mediaId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(mediaId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public record StageEvent(String stage, Object payload) {}
}

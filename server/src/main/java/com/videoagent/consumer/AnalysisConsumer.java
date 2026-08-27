package com.videoagent.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.KafkaConfig;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.dto.AnalysisTaskMessage;
import com.videoagent.dto.ProcessingTimeline;
import com.videoagent.entity.FailedAnalysisTask;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.FailedAnalysisTaskRepository;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.MediaTitleService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.ingest.VideoContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 分析任务消费者（方案 §7.3 状态机）：
 * <ul>
 *   <li>主主题：异步执行 ingest 管线（VideoContext 构建），失败向上抛出由 DefaultErrorHandler 重试 3 次后转死信；</li>
 *   <li>死信主题：毒消息收敛——落失败任务台账 + 媒体标记 FAILED 后 ACK，避免无限重投。</li>
 * </ul>
 */
@Component
public class AnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalysisConsumer.class);

    private final ObjectMapper objectMapper;
    private final VideoContextService videoContextService;
    private final MediaFileRepository mediaFileRepository;
    private final FailedAnalysisTaskRepository failedTaskRepository;
    private final StageEventPublisher events;
    private final AgentLoopService agentLoopService;
    private final CheckpointService checkpointService;
    private final MediaTitleService mediaTitleService;

    public AnalysisConsumer(ObjectMapper objectMapper, VideoContextService videoContextService,
                            MediaFileRepository mediaFileRepository,
                            FailedAnalysisTaskRepository failedTaskRepository,
                            StageEventPublisher events, AgentLoopService agentLoopService,
                            CheckpointService checkpointService, MediaTitleService mediaTitleService) {
        this.objectMapper = objectMapper;
        this.videoContextService = videoContextService;
        this.mediaFileRepository = mediaFileRepository;
        this.failedTaskRepository = failedTaskRepository;
        this.events = events;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.mediaTitleService = mediaTitleService;
    }

    @KafkaListener(topics = KafkaConfig.ANALYSIS_TOPIC, groupId = "video-analysis-consumer")
    public void onMessage(String messageJson) {
        AnalysisTaskMessage message = parse(messageJson);
        log.info("消费分析任务 mediaId={} action={} goal={}", message.mediaId(), message.action(), message.userGoal());
        // 阶段二：ingest 管线（幂等，已就绪直接跳过）
        videoContextService.process(message);
        // 阶段四：上下文就绪后运行 Agent 循环（目标级 Checkpoint 幂等，断点可恢复）
        MediaFile media = mediaFileRepository.findById(message.mediaId()).orElse(null);
        if (media != null && MediaFile.STATUS_CONTEXT_READY.equals(media.getStatus())) {
            long agentStart = System.currentTimeMillis();
            agentLoopService.run(message.mediaId(), message.userGoal(),
                    AnalysisMode.parse(message.mode()), media.getUserId());
            long agentEnd = System.currentTimeMillis();
            // 记录 Agent 步骤耗时到处理时间线（覆盖旧值；旧数据无时间线则跳过）。
            // 注意：Kafka 重投时结果已缓存、run 瞬间返回（≈0ms），此时不覆盖，
            // 避免把上一次真实耗时冲成 0。
            if (agentEnd - agentStart >= 500) {
                checkpointService.load(message.mediaId(), VideoContextService.CP_TIMELINE, ProcessingTimeline.class)
                        .ifPresent(t -> checkpointService.save(message.mediaId(), VideoContextService.CP_TIMELINE, "AGENT",
                                t.withStep("agent", "Agent 分析", agentEnd - agentStart)));
            }
            // 状态归位：Agent 完成后媒体保持 CONTEXT_READY，支持后续新目标提交
            media.setStatus(MediaFile.STATUS_CONTEXT_READY);
            mediaFileRepository.save(media);
            // 视频标题：标题为空时依据本次分析结果自动生成（用户改过则不覆盖）
            mediaTitleService.ensureTitleAfterAnalysis(message.mediaId(), message.userGoal(),
                    AnalysisMode.parse(message.mode()), media.getUserId());
        }
        log.info("分析任务完成 mediaId={}", message.mediaId());
    }

    @KafkaListener(topics = KafkaConfig.ANALYSIS_DEAD_TOPIC, groupId = "video-analysis-dead-consumer")
    public void onDeadLetter(String messageJson) {
        AnalysisTaskMessage message = parse(messageJson);
        log.error("分析任务进入死信 mediaId={} errorType=POISON", message.mediaId());
        // 毒消息收敛：落台账 + 标记失败后 ACK，避免无限重投
        FailedAnalysisTask task = new FailedAnalysisTask();
        task.setMediaId(message.mediaId());
        task.setAction(message.action());
        task.setUserGoal(message.userGoal());
        task.setAttemptCount(3);
        task.setErrorType("DEAD_LETTERED");
        task.setStatus(FailedAnalysisTask.STATUS_DEAD_LETTERED);
        task.setErrorMessage("消费重试 3 次后仍失败，转入死信");
        failedTaskRepository.save(task);

        mediaFileRepository.findById(message.mediaId()).ifPresent(media -> {
            media.setStatus(MediaFile.STATUS_FAILED);
            mediaFileRepository.save(media);
            events.publish(message.mediaId(), "FAILED", Map.of("error", "任务已转入死信"));
        });
    }

    private AnalysisTaskMessage parse(String json) {
        try {
            return objectMapper.readValue(json, AnalysisTaskMessage.class);
        } catch (Exception e) {
            // 结构性非法消息：直接抛错，由错误处理器重试后转死信（毒消息收敛）
            throw new IllegalArgumentException("消息结构非法: " + json, e);
        }
    }
}

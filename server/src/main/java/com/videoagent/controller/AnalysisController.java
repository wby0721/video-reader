package com.videoagent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.common.ApiResponse;
import com.videoagent.common.BusinessException;
import com.videoagent.config.KafkaConfig;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.AnalysisRouteRequest;
import com.videoagent.dto.AnalysisRouteResponse;
import com.videoagent.dto.AnalysisStatusResponse;
import com.videoagent.dto.AnalysisSubmitRequest;
import com.videoagent.dto.AnalysisSubmitResponse;
import com.videoagent.dto.AnalysisTaskMessage;
import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.ChatEntry;
import com.videoagent.dto.ChatRequest;
import com.videoagent.dto.CriticResult;
import com.videoagent.dto.EvaluationReport;
import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.FeedbackRequest;
import com.videoagent.dto.GlobalEvidenceHit;
import com.videoagent.dto.ProcessingTimeline;
import com.videoagent.dto.VerificationReport;
import com.videoagent.dto.VideoContext;
import com.videoagent.entity.AnalysisFeedback;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.AnalysisFeedbackRepository;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.service.ingest.VideoContextService;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import com.videoagent.utils.CurrentUser;
import org.redisson.api.RedissonClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析任务接口（需鉴权）：
 * <ul>
 *   <li>POST /analysis：提交分析（异步受理 202，Kafka 削峰，Redisson 限流 + 幂等）；</li>
 *   <li>GET /analysis/status：任务状态；</li>
 *   <li>GET /analysis/context：VideoContext（带时间戳证据）；</li>
 *   <li>GET /analysis/events：SSE 阶段进度推送；</li>
 *   <li>GET /analysis/evidence-search：混合证据检索（语义+关键词+画面文字）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final MediaFileRepository mediaFileRepository;
    private final CheckpointService checkpointService;
    private final StageEventPublisher events;
    private final RateLimitService rateLimitService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final VideoEvidenceRetrievalService retrievalService;
    private final AgentLoopService agentLoopService;
    private final RedissonClient redisson;
    private final AnalysisFeedbackRepository feedbackRepository;
    private final com.videoagent.service.eval.AgentTelemetry telemetry;
    private final com.videoagent.service.eval.AgentEvaluationService evaluationService;
    private final com.videoagent.service.trust.FidelityChecker fidelityChecker;
    private final LlmProvider llmProvider;

    public AnalysisController(MediaFileRepository mediaFileRepository, CheckpointService checkpointService,
                              StageEventPublisher events, RateLimitService rateLimitService,
                              KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                              VideoEvidenceRetrievalService retrievalService,
                              AgentLoopService agentLoopService, RedissonClient redisson,
                              AnalysisFeedbackRepository feedbackRepository,
                              com.videoagent.service.eval.AgentTelemetry telemetry,
                              com.videoagent.service.eval.AgentEvaluationService evaluationService,
                              com.videoagent.service.trust.FidelityChecker fidelityChecker,
                              LlmProvider llmProvider) {
        this.mediaFileRepository = mediaFileRepository;
        this.checkpointService = checkpointService;
        this.events = events;
        this.rateLimitService = rateLimitService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.agentLoopService = agentLoopService;
        this.redisson = redisson;
        this.feedbackRepository = feedbackRepository;
        this.telemetry = telemetry;
        this.evaluationService = evaluationService;
        this.fidelityChecker = fidelityChecker;
        this.llmProvider = llmProvider;
    }

    private static final String LAST_GOAL_KEY = "analysis:last-goal";
    private static final String CP_CHAT = "media-chat";
    private static final TypeReference<List<ChatEntry>> CHAT_LIST_TYPE = new TypeReference<>() {};

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisSubmitResponse>> submit(@Valid @RequestBody AnalysisSubmitRequest request,
                                                                      HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        MediaFile media = mediaFileRepository.findByIdAndUserId(request.mediaId(), userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));

        // 幂等：处理中直接返回，不重复投递；CONTEXT_READY 仍投递（触发 Agent 循环，目标级幂等）
        if (MediaFile.STATUS_PROCESSING.equals(media.getStatus())) {
            return ResponseEntity.accepted().body(ApiResponse.ok(
                    new AnalysisSubmitResponse(media.getId(), media.getId(), MediaFile.STATUS_PROCESSING, "任务处理中")));
        }
        if (MediaFile.STATUS_FAILED.equals(media.getStatus())) {
            throw new BusinessException(409, "媒体处理失败，请重新上传");
        }

        // 成本护栏：用户级 + 全局级令牌桶限流
        if (!rateLimitService.tryAcquireUser(userId)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        if (!rateLimitService.tryAcquireGlobal()) {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }

        try {
            AnalysisTaskMessage message = new AnalysisTaskMessage(
                    media.getId(), AnalysisTaskMessage.ACTION_START_ANALYSIS,
                    media.getContentHash(), request.userGoal(), request.mode());
            kafkaTemplate.send(KafkaConfig.ANALYSIS_TOPIC, String.valueOf(media.getId()),
                    objectMapper.writeValueAsString(message));
            // 记录最近目标（供 /analysis/result 定位目标级 Checkpoint）
            redisson.getMap(LAST_GOAL_KEY).put(media.getId(),
                    objectMapper.writeValueAsString(java.util.Map.of(
                            "goal", request.userGoal(), "mode", AnalysisMode.parse(request.mode()).name())));
        } catch (Exception e) {
            log.error("分析任务投递失败 mediaId={}", media.getId(), e);
            throw new BusinessException(500, "任务投递失败: " + e.getMessage());
        }

        media.setStatus(MediaFile.STATUS_PROCESSING);
        mediaFileRepository.save(media);
        events.publish(media.getId(), "SUBMITTED", java.util.Map.of("goal", request.userGoal()));
        log.info("分析任务已受理 mediaId={} goal={}", media.getId(), request.userGoal());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                new AnalysisSubmitResponse(media.getId(), media.getId(), MediaFile.STATUS_PROCESSING, "任务已受理")));
    }

    /** 自动意图路由（方案 §10：POST /analysis/route）。 */
    @PostMapping("/route")
    public ApiResponse<AnalysisRouteResponse> route(@Valid @RequestBody AnalysisRouteRequest request,
                                                    HttpServletRequest http) {
        AnalysisMode mode = agentLoopService.route(request.goal(), CurrentUser.userId(http));
        return ApiResponse.ok(new AnalysisRouteResponse(mode.name(),
                "GENERAL".equals(mode.name()) ? "通用分析" : "已按目标意图路由"));
    }

    @GetMapping("/status")
    public ApiResponse<AnalysisStatusResponse> status(@RequestParam Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        StageEventPublisher.StageEvent event = events.current(mediaId);
        String stage = event != null ? event.stage() : media.getStatus();
        String error = MediaFile.STATUS_FAILED.equals(media.getStatus()) && event != null
                ? String.valueOf(event.payload()) : null;
        boolean contextAvailable = checkpointService.exists(mediaId, CheckpointService.CP_VIDEO_CONTEXT);
        boolean resultAvailable = lastGoal(mediaId).map(g -> checkpointService.exists(mediaId,
                AgentLoopService.goalKey(g.goal(), g.mode()) + "-final")).orElse(false);
        return ApiResponse.ok(new AnalysisStatusResponse(mediaId, media.getStatus(), stage, error,
                contextAvailable, resultAvailable));
    }

    /** 查询最近一次分析的结构化结果（目标级 Checkpoint）。 */
    @GetMapping("/result")
    public ApiResponse<AnalysisResult> result(@RequestParam Long mediaId,
                                              @RequestParam(required = false) String goal,
                                              HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        LastGoal lg = resolveGoal(mediaId, goal);
        if (lg == null) {
            throw new BusinessException(404, "尚未提交过分析任务");
        }
        return checkpointService.load(mediaId, AgentLoopService.goalKey(lg.goal(), lg.mode()) + "-final", AnalysisResult.class)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(404, "分析结果尚未生成"));
    }

    /**
     * 证据验证报告（方案 §10 / §5.5）：L1/L2/L3 判定 + 语义支撑率 + 幻觉率。
     */
    @GetMapping("/verification")
    public ApiResponse<VerificationReport> verification(@RequestParam Long mediaId,
                                                        @RequestParam(required = false) String goal,
                                                        HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        LastGoal lg = resolveGoal(mediaId, goal);
        if (lg == null) {
            throw new BusinessException(404, "尚未提交过分析任务");
        }
        return checkpointService.load(mediaId, AgentLoopService.goalKey(lg.goal(), lg.mode()) + "-verification",
                        VerificationReport.class)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(404, "验证报告尚未生成"));
    }

    /**
     * 质量评估指标 + 可观测 trace（方案 §6.5）。实时重算（无 LLM 调用，接受率等即时生效）。
     */
    @GetMapping("/evaluation")
    public ApiResponse<EvaluationReport> evaluation(@RequestParam Long mediaId,
                                                    @RequestParam(required = false) String goal,
                                                    HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        LastGoal lg = resolveGoal(mediaId, goal);
        if (lg == null) {
            throw new BusinessException(404, "尚未提交过分析任务");
        }
        String goalKey = AgentLoopService.goalKey(lg.goal(), lg.mode());
        com.videoagent.service.eval.AgentTelemetry.RunTrace trace = checkpointService
                .load(mediaId, goalKey + "-telemetry", com.videoagent.service.eval.AgentTelemetry.RunTrace.class)
                .orElse(null);
        EvaluationReport report = evaluationService.evaluate(mediaId, media.getContentHash(), lg.goal(), goalKey,
                checkpointService.load(mediaId, goalKey + "-final", AnalysisResult.class).orElse(null),
                checkpointService.loadVideoContext(mediaId).orElse(null),
                checkpointService.load(mediaId, goalKey + "-verification", VerificationReport.class).orElse(null),
                trace, userId);
        return ApiResponse.ok(report);
    }

    /**
     * 可信度 trace（方案 §6.5）：整条处理流程可追溯——
     * 处理时间线 + Agent 每轮产物（Planner/Executor/Critic）+ 最终结果与支撑验证 + telemetry/评估。
     */
    @GetMapping("/trace")
    public ApiResponse<Map<String, Object>> trace(@RequestParam Long mediaId,
                                                  @RequestParam(required = false) String goal,
                                                  HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        LastGoal lg = resolveGoal(mediaId, goal);
        if (lg == null) {
            throw new BusinessException(404, "尚未提交过分析任务");
        }
        String goalKey = AgentLoopService.goalKey(lg.goal(), lg.mode());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", lg.goal());
        out.put("mode", lg.mode().name());
        out.put("timeline", checkpointService
                .load(mediaId, VideoContextService.CP_TIMELINE, ProcessingTimeline.class).orElse(null));
        out.put("plan", checkpointService.load(mediaId, goalKey + "-plan", AgentPlan.class).orElse(null));

        // Agent 各轮：Executor 结论 + Critic 评审（≤3 轮）
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (int r = 0; r <= 2; r++) {
            AnalysisResult exec = checkpointService
                    .load(mediaId, goalKey + "-executor-" + r, AnalysisResult.class).orElse(null);
            CriticResult crit = checkpointService
                    .load(mediaId, goalKey + "-critic-" + r, CriticResult.class).orElse(null);
            if (exec == null && crit == null) {
                continue;
            }
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("round", r);
            round.put("executor", exec);
            round.put("critic", crit);
            rounds.add(round);
        }
        out.put("rounds", rounds);
        out.put("final", checkpointService.load(mediaId, goalKey + "-final", AnalysisResult.class).orElse(null));
        out.put("verification", checkpointService
                .load(mediaId, goalKey + "-verification", VerificationReport.class).orElse(null));
        out.put("telemetry", checkpointService
                .load(mediaId, goalKey + "-telemetry", com.videoagent.service.eval.AgentTelemetry.RunTrace.class)
                .orElse(null));
        out.put("evaluation", checkpointService
                .load(mediaId, goalKey + "-evaluation", EvaluationReport.class).orElse(null));
        return ApiResponse.ok(out);
    }

    /**
     * 用户反馈（👍/👎）：接受率指标数据源。
     */
    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@Valid @RequestBody FeedbackRequest request, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(request.mediaId(), userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        LastGoal lg = resolveGoal(request.mediaId(), request.goal());
        if (lg == null) {
            throw new BusinessException(404, "尚未提交过分析任务");
        }
        String goalKey = AgentLoopService.goalKey(lg.goal(), lg.mode());
        AnalysisFeedback feedback = feedbackRepository
                .findByUserIdAndMediaIdAndGoalKey(userId, request.mediaId(), goalKey)
                .orElseGet(AnalysisFeedback::new);
        feedback.setUserId(userId);
        feedback.setMediaId(request.mediaId());
        feedback.setGoalKey(goalKey);
        feedback.setRating(request.rating() >= 0 ? 1 : -1);
        feedbackRepository.save(feedback);
        return ApiResponse.ok();
    }

    /** 解析目标：显式 goal 与最近提交一致时沿用其 mode；否则按 GENERAL 处理。 */
    private LastGoal resolveGoal(Long mediaId, String goal) {
        LastGoal last = lastGoal(mediaId).orElse(null);
        if (goal == null || goal.isBlank()) {
            return last;
        }
        if (last != null && last.goal().equals(goal)) {
            return last;
        }
        return new LastGoal(goal, AnalysisMode.GENERAL);
    }

    private record LastGoal(String goal, AnalysisMode mode) {}

    private java.util.Optional<LastGoal> lastGoal(Long mediaId) {
        Object json = redisson.getMap(LAST_GOAL_KEY).get(mediaId);
        if (json == null) {
            return java.util.Optional.empty();
        }
        try {
            var node = objectMapper.readTree(json.toString());
            return java.util.Optional.of(new LastGoal(node.path("goal").asText(),
                    AnalysisMode.parse(node.path("mode").asText())));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    @GetMapping("/context")
    public ApiResponse<VideoContext> context(@RequestParam Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return checkpointService.loadVideoContext(mediaId)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(404, "上下文尚未生成"));
    }

    @GetMapping("/events")
    public SseEmitter events(@RequestParam Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return events.subscribe(mediaId);
    }

    /**
     * 证据检索（方案 §10）：语义 + 关键词 + 画面文字混合检索，返回 TopK 带时间戳证据片段。
     */
    @GetMapping("/evidence-search")
    public ApiResponse<List<EvidenceHit>> evidenceSearch(@RequestParam Long mediaId,
                                                         @RequestParam String query,
                                                         @RequestParam(defaultValue = "5") int topK,
                                                         HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        MediaFile media = mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        VideoContext context = checkpointService.loadVideoContext(mediaId)
                .orElseThrow(() -> new BusinessException(404, "上下文尚未生成，请先完成分析"));
        List<EvidenceHit> hits = retrievalService.search(mediaId, media.getContentHash(), context, query, topK, userId);
        return ApiResponse.ok(hits);
    }

    /**
     * 连续追问：检索证据片段 → LLM 依据片段摘要生成自然语言回答，并持久化对话历史。
     * 回答为连贯段落，不暴露片段编号 / 时间戳 / 置信度分数（那些属证据展示，不在对话中出现，
     * 但会随历史存下，供「可信度 trace → 问答」页展示）。
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        MediaFile media = mediaFileRepository.findByIdAndUserId(request.mediaId(), userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        // 成本护栏：追问与「提交分析」共用用户级 + 全局级令牌桶额度（一次追问 = 一次 LLM 消费）
        if (!rateLimitService.tryAcquireUser(userId)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        if (!rateLimitService.tryAcquireGlobal()) {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }
        VideoContext context = checkpointService.loadVideoContext(request.mediaId())
                .orElseThrow(() -> new BusinessException(404, "上下文尚未生成，请先完成分析"));

        // 持久化对话历史（media 级 Checkpoint）
        List<ChatEntry> history = checkpointService
                .load(request.mediaId(), CP_CHAT, CHAT_LIST_TYPE).orElse(new ArrayList<>());
        history.add(new ChatEntry("user", request.query(), System.currentTimeMillis(), List.of()));

        List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                request.mediaId(), media.getContentHash(), context, request.query(), 5, userId);
        if (hits.isEmpty()) {
            history.add(new ChatEntry("assistant", "视频中没有找到与这个问题相关的内容。",
                    System.currentTimeMillis(), List.of()));
            checkpointService.save(request.mediaId(), CP_CHAT, "CHAT", history);
            return ApiResponse.ok(new ChatResponse("视频中没有找到与这个问题相关的内容。", history));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是视频内容问答助手。请基于下面给出的视频片段摘要，用自然连贯的段落回答用户的问题。\n");
        sb.append("要求：直接给出答案，不要列出片段编号、时间戳或任何置信度分数；若摘要不足，如实说明。\n");
        List<ChatEntry> contextTurns = history.size() > 7
                ? history.subList(history.size() - 7, history.size()) : history;
        if (!contextTurns.isEmpty()) {
            sb.append("\n历史对话（供理解上下文）：\n");
            for (ChatEntry t : contextTurns) {
                sb.append(t.role()).append("：").append(t.content()).append('\n');
            }
        }
        sb.append("\n用户问题：").append(request.query()).append('\n');
        sb.append("\n视频片段摘要：\n");
        for (EvidenceHit h : hits) {
            sb.append("- ").append(trim(h.summary(), 400)).append('\n');
        }
        String answer = llmProvider.forUser(userId).chat(sb.toString(), 500);
        String ans = answer == null ? "" : answer.strip();
        history.add(new ChatEntry("assistant", ans, System.currentTimeMillis(), hits));
        checkpointService.save(request.mediaId(), CP_CHAT, "CHAT", history);
        return ApiResponse.ok(new ChatResponse(ans, history));
    }

    /** 对话历史（持久化）：供工作台恢复聊天 + 可信度 trace「问答」页展示。 */
    @GetMapping("/chat-history")
    public ApiResponse<List<ChatEntry>> chatHistory(@RequestParam Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return ApiResponse.ok(checkpointService.load(mediaId, CP_CHAT, CHAT_LIST_TYPE).orElse(List.of()));
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }

    /** 连续追问回答（附更新后的完整对话历史，前端直接渲染）。 */
    public record ChatResponse(String answer, List<ChatEntry> history) {}

    /**
     * 知识库全局检索（跨项目）：遍历用户所有已完成视频，无 LLM 改写逐项目检索后按分数合并。
     */
    @GetMapping("/global-search")
    public ApiResponse<List<GlobalEvidenceHit>> globalSearch(@RequestParam String query,
                                                             @RequestParam(defaultValue = "10") int topK,
                                                             HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        List<GlobalEvidenceHit> out = new ArrayList<>();
        for (MediaFile media : mediaFileRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!MediaFile.STATUS_CONTEXT_READY.equals(media.getStatus())) {
                continue;
            }
            VideoContext ctx = checkpointService.loadVideoContext(media.getId()).orElse(null);
            if (ctx == null) {
                continue;
            }
            try {
                List<EvidenceHit> hits = retrievalService.searchNoRewrite(
                        media.getId(), media.getContentHash(), ctx, query, 3, userId);
                for (EvidenceHit h : hits) {
                    out.add(new GlobalEvidenceHit(media.getId(), media.getFilename(),
                            h.startMs(), h.endMs(), h.summary(), h.score(), h.source()));
                }
            } catch (Exception e) {
                log.warn("global-search skip mediaId={}: {}", media.getId(), e.getMessage());
            }
        }
        out.sort(Comparator.comparingDouble(GlobalEvidenceHit::score).reversed());
        return ApiResponse.ok(out.stream().limit(Math.max(1, topK)).toList());
    }
}

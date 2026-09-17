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
import com.videoagent.dto.ChatEvidence;
import com.videoagent.dto.ChatHistoryActionRequest;
import com.videoagent.dto.RetrievalAssessment;
import com.videoagent.dto.RetrievalTrace;
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
import com.videoagent.service.ChatHistoryViewService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.agent.AgentLoopService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.auth.RateLimitService;
import com.videoagent.service.ingest.VideoContextService;
import com.videoagent.service.retrieval.VideoEvidenceRetrievalService;
import com.videoagent.service.retrieval.GlobalKnowledgeSearchService;
import com.videoagent.service.retrieval.ChatEvidenceService;
import com.videoagent.service.retrieval.HybridQuery;
import com.videoagent.service.retrieval.QueryRewriter;
import com.videoagent.utils.LlmClient;
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

    private static final int CHAT_ANSWER_MAX_TOKENS = 1_200;

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
    private final GlobalKnowledgeSearchService globalKnowledgeSearchService;
    private final QueryRewriter queryRewriter;
    private final ChatEvidenceService chatEvidenceService;
    private final ChatHistoryViewService chatHistoryViewService;

    public AnalysisController(MediaFileRepository mediaFileRepository, CheckpointService checkpointService,
                              StageEventPublisher events, RateLimitService rateLimitService,
                              KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                              VideoEvidenceRetrievalService retrievalService,
                              AgentLoopService agentLoopService, RedissonClient redisson,
                              AnalysisFeedbackRepository feedbackRepository,
                              com.videoagent.service.eval.AgentTelemetry telemetry,
                              com.videoagent.service.eval.AgentEvaluationService evaluationService,
                              com.videoagent.service.trust.FidelityChecker fidelityChecker,
                              LlmProvider llmProvider,
                              GlobalKnowledgeSearchService globalKnowledgeSearchService,
                              QueryRewriter queryRewriter,
                              ChatEvidenceService chatEvidenceService,
                              ChatHistoryViewService chatHistoryViewService) {
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
        this.globalKnowledgeSearchService = globalKnowledgeSearchService;
        this.queryRewriter = queryRewriter;
        this.chatEvidenceService = chatEvidenceService;
        this.chatHistoryViewService = chatHistoryViewService;
    }

    private static final String LAST_GOAL_KEY = "analysis:last-goal";

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

        // 先用已有历史消解指代；此时不要把当前问题重复放进历史。
        // media-chat 是不可删除审计历史；工作台显示/连续改写只读取未被清空或撤销的记录。
        List<ChatEntry> auditHistory = chatHistoryViewService.audit(request.mediaId());
        List<ChatEntry> visibleHistory = chatHistoryViewService.visible(request.mediaId(), auditHistory);
        LlmClient model = llmProvider.forUser(userId);
        if (model == null) {
            throw new BusinessException(400, "请先在个人设置中配置 LLM API Key");
        }
        String videoTitle = media.getTitle() == null || media.getTitle().isBlank()
                ? media.getFilename() : media.getTitle();
        QueryRewriter.ConversationRewrite rewritten = queryRewriter.rewriteConversation(
                request.query(), visibleHistory, videoTitle, model);
        auditHistory.add(new ChatEntry("user", request.query(), System.currentTimeMillis(), List.of()));

        VideoEvidenceRetrievalService.PreparedSearch search = retrievalService.searchPrepared(
                request.mediaId(), media.getContentHash(), context,
                new HybridQuery(request.query(), rewritten.semanticQuery(),
                        rewritten.keywords(), rewritten.ocrKeywords()), 5, userId);
        ChatEvidenceService.PreparedEvidence prepared = chatEvidenceService.prepare(request.mediaId(), search);
        List<EvidenceHit> hits = prepared.hits();
        List<ChatEvidence> evidence = prepared.evidence();
        RetrievalAssessment assessment = prepared.retrieval();
        RetrievalTrace retrievalTrace = prepared.trace() == null ? null : prepared.trace()
                .withConversationRewrite(rewritten.standaloneQuestion(), rewritten.semanticQuery(),
                        rewritten.keywords(), rewritten.ocrKeywords());
        if (evidence.isEmpty()) {
            String unavailable = assessment.degraded()
                    ? "检索服务暂时降级，未能获得可用证据，请稍后重试。"
                    : "本次没有获得足够相关的视频证据，无法据此回答这个问题。";
            auditHistory.add(new ChatEntry("assistant", unavailable,
                    System.currentTimeMillis(), List.of(), assessment, List.of(), retrievalTrace));
            chatHistoryViewService.saveAudit(request.mediaId(), auditHistory);
            List<ChatEntry> responseHistory = chatHistoryViewService.visible(request.mediaId(), auditHistory);
            return ApiResponse.ok(new ChatResponse(
                    unavailable, List.of(), true, responseHistory, assessment));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是视频内容问答助手，只能根据 EvidencePack 中的视频原文回答。\n")
                .append("证据不足时必须明确说明；不要把外部常识冒充为视频内容。")
                .append("关键结论用[证据1]这样的编号标注来源，不展示检索分数。\n")
                .append("EvidencePack只是本次检索到的候选，不代表视频全文；未在候选中出现的内容，")
                .append("只能说当前证据不足，不能断言整段视频绝对没有提及。\n")
                .append("如果用户询问视频总体内容，请表述为‘根据当前召回片段，视频主要……’，")
                .append("只概括证据实际覆盖的主题。回答应完整收尾，普通回答尽量控制在800个中文字符内。\n")
                .append("检索状态：").append(assessment.status()).append("。")
                .append(assessment.hint()).append('\n')
                .append("独立问题：").append(rewritten.standaloneQuestion()).append("\n\nEvidencePack:\n")
                .append(chatEvidenceService.toPromptText(videoTitle, evidence));
        String answer = model.chat(sb.toString(), CHAT_ANSWER_MAX_TOKENS);
        String ans = answer == null ? "" : answer.strip();
        auditHistory.add(new ChatEntry("assistant", ans, System.currentTimeMillis(), hits, assessment,
                evidence, retrievalTrace));
        chatHistoryViewService.saveAudit(request.mediaId(), auditHistory);
        List<ChatEntry> responseHistory = chatHistoryViewService.visible(request.mediaId(), auditHistory);
        return ApiResponse.ok(new ChatResponse(ans, evidence, false, responseHistory, assessment));
    }

    /** 对话历史（持久化）：供工作台恢复聊天 + 可信度 trace「问答」页展示。 */
    @GetMapping("/chat-history")
    public ApiResponse<List<ChatEntry>> chatHistory(@RequestParam Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
        return ApiResponse.ok(chatHistoryViewService.audit(mediaId));
    }

    /** 工作台追问框可见记录；与不可删除的后台 Trace 审计历史分离。 */
    @GetMapping("/chat-visible-history")
    public ApiResponse<List<ChatEntry>> chatVisibleHistory(@RequestParam Long mediaId, HttpServletRequest http) {
        requireOwnedMedia(mediaId, http);
        return ApiResponse.ok(chatHistoryViewService.visible(mediaId));
    }

    /** 仅清空当前视频追问框，后台 media-chat Trace 不删除。 */
    @PostMapping("/chat-visible-history/clear")
    public ApiResponse<List<ChatEntry>> clearChatVisibleHistory(
            @Valid @RequestBody ChatHistoryActionRequest request, HttpServletRequest http) {
        requireOwnedMedia(request.mediaId(), http);
        return ApiResponse.ok(chatHistoryViewService.clearVisible(request.mediaId()));
    }

    /** 仅撤销工作台最近一轮提问和回答，后台 media-chat Trace 不删除。 */
    @PostMapping("/chat-visible-history/undo")
    public ApiResponse<List<ChatEntry>> undoLastChatRound(
            @Valid @RequestBody ChatHistoryActionRequest request, HttpServletRequest http) {
        requireOwnedMedia(request.mediaId(), http);
        return ApiResponse.ok(chatHistoryViewService.undoLastRound(request.mediaId()));
    }

    private void requireOwnedMedia(Long mediaId, HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        mediaFileRepository.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
    }

    /** 连续追问回答（附更新后的完整对话历史，前端直接渲染）。 */
    public record ChatResponse(String answer, List<ChatEvidence> evidence,
                               boolean insufficientEvidence, List<ChatEntry> history, RetrievalAssessment retrieval) {
        public ChatResponse(String answer, List<ChatEvidence> evidence, boolean insufficientEvidence, List<ChatEntry> history) {
            this(answer, evidence, insufficientEvidence, history, null);
        }
    }

    /** 知识库全局定位：一次用户级混合检索，不调用生成式 LLM。 */
    @GetMapping("/global-search/details")
    public ApiResponse<GlobalKnowledgeSearchService.SearchResponse> globalSearchDetails(@RequestParam String query,
            @RequestParam(defaultValue = "10") int topK, HttpServletRequest http) {
        return ApiResponse.ok(globalKnowledgeSearchService.searchDetailed(CurrentUser.userId(http), query, topK));
    }

    @GetMapping("/global-search")
    public ApiResponse<List<GlobalEvidenceHit>> globalSearch(@RequestParam String query,
                                                             @RequestParam(defaultValue = "10") int topK,
                                                             HttpServletRequest http) {
        Long userId = CurrentUser.userId(http);
        return ApiResponse.ok(globalKnowledgeSearchService.search(userId, query, topK));
    }
}

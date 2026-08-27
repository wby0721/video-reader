package com.videoagent.service.agent;

import com.videoagent.dto.AgentPlan;
import com.videoagent.dto.AnalysisMode;
import com.videoagent.dto.AnalysisResult;
import com.videoagent.dto.CriticResult;
import com.videoagent.dto.EvaluationReport;
import com.videoagent.dto.VideoChunk;
import com.videoagent.dto.VideoContext;
import com.videoagent.dto.VerificationReport;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.StageEventPublisher;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.eval.AgentEvaluationService;
import com.videoagent.service.eval.AgentTelemetry;
import com.videoagent.service.retrieval.RetrievalIndexService;
import com.videoagent.service.trust.EvidenceVerificationService;
import com.videoagent.utils.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Agent 核心循环（方案 §6.3）：Planner → Executor → Critic，≤2 轮。
 *
 * <ul>
 *   <li>证据约束：Executor 输入仅含检索 TopK 证据包，结论必须绑定时间戳证据；</li>
 *   <li>定向补检索：Critic 返回 requiredTimestamps → 下一轮按时间戳补证据；</li>
 *   <li>执行预算：轮次上限 + 时长上限 + Token 估算，超限保留警告产出结果；</li>
 *   <li>断点恢复：plan / executor{round} / critic{round} / final 按目标级键落 Checkpoint，
 *       Kafka 重投从最近成功阶段继续，不重复烧 LLM。</li>
 * </ul>
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private static final int MAX_ROUNDS = 2;
    private static final long MAX_DURATION_MS = 300_000L;

    private final Planner planner;
    private final Executor executor;
    private final Critic critic;
    private final ModeRouter modeRouter;
    private final ModeRegistry modeRegistry;
    private final EvidencePackService evidencePackService;
    private final LlmProvider llmProvider;
    private final CheckpointService checkpointService;
    private final StageEventPublisher events;
    private final RetrievalIndexService retrievalIndexService;
    private final MediaFileRepository mediaFileRepository;
    private final EvidenceVerificationService verificationService;
    private final AgentTelemetry telemetry;
    private final AgentEvaluationService evaluationService;

    public AgentLoopService(Planner planner, Executor executor, Critic critic, ModeRouter modeRouter,
                            ModeRegistry modeRegistry, EvidencePackService evidencePackService,
                            LlmProvider llmProvider, CheckpointService checkpointService,
                            StageEventPublisher events, RetrievalIndexService retrievalIndexService,
                            MediaFileRepository mediaFileRepository,
                            EvidenceVerificationService verificationService,
                            AgentTelemetry telemetry, AgentEvaluationService evaluationService) {
        this.planner = planner;
        this.executor = executor;
        this.critic = critic;
        this.modeRouter = modeRouter;
        this.modeRegistry = modeRegistry;
        this.evidencePackService = evidencePackService;
        this.llmProvider = llmProvider;
        this.checkpointService = checkpointService;
        this.events = events;
        this.retrievalIndexService = retrievalIndexService;
        this.mediaFileRepository = mediaFileRepository;
        this.verificationService = verificationService;
        this.telemetry = telemetry;
        this.evaluationService = evaluationService;
    }

    /** 自动意图路由入口（/analysis/route）。 */
    public AnalysisMode route(String goal, Long userId) {
        return modeRouter.route(goal, llmProvider.forUser(userId));
    }

    /** Agent 循环入口。 */
    public AnalysisResult run(Long mediaId, String goal, AnalysisMode mode, Long userId) {
        String goalKey = goalKey(goal, mode);

        // 幂等：该目标已完成 → 直接返回
        Optional<AnalysisResult> done = checkpointService.load(mediaId, goalKey + "-final", AnalysisResult.class);
        if (done.isPresent()) {
            events.publish(mediaId, "AGENT_COMPLETED", Map.of("cached", true));
            return done.get();
        }

        LlmClient model = llmProvider.forUser(userId);
        if (model == null) {
            throw new IllegalStateException("Agent 需要 LLM：请配置服务端 LLM_API_KEY 或用户自带 Key");
        }

        VideoContext context = checkpointService.loadVideoContext(mediaId)
                .orElseThrow(() -> new IllegalStateException("VideoContext 尚未生成"));
        MediaFile media = mediaFileRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalStateException("媒体不存在: " + mediaId));
        List<VideoChunk> chunks = retrievalIndexService.index(mediaId, media.getContentHash(), context, userId);

        ModeProfile profile = modeRegistry.get(mode);
        long startedAt = System.currentTimeMillis();
        TokenMeter meter = new TokenMeter();

        // 阶段六：可观测追踪
        AgentTelemetry.RunTrace trace = telemetry.begin(goalKey);

        // 1) Planner（断点）
        AgentPlan plan = loadOr(mediaId, goalKey + "-plan", AgentPlan.class, () -> {
            events.publish(mediaId, "AGENT_PLAN", Map.of("goal", goal, "mode", mode.name()));
            long t0 = System.currentTimeMillis();
            AgentPlan p = planner.plan(model, goal, profile, overview(chunks));
            telemetry.stage("planner", System.currentTimeMillis() - t0, 1, est(p));
            return p;
        });

        // 2) 循环：Executor → Critic（≤MAX_ROUNDS 轮，反馈驱动定向补证据）
        String feedback = null;
        List<Long> requiredTimestamps = List.of();
        Set<Long> requestedUnavailable = new HashSet<>(); // 视频中不存在的时间戳：过滤掉，防止 Critic 每轮重复请求空转
        AnalysisResult lastResult = null;
        String lastCriticFeedback = "";
        int round = 0;
        while (round <= MAX_ROUNDS) {
            if (System.currentTimeMillis() - startedAt > MAX_DURATION_MS) {
                log.warn("Agent 执行预算（时长）超限 mediaId={} round={}", mediaId, round);
                break;
            }
            final int r = round;
            final String fb = feedback;
            final List<Long> req = requiredTimestamps.stream()
                    .filter(ts -> !requestedUnavailable.contains(ts)).toList();

            EvidencePackService.EvidencePack pack = evidencePackService.build(
                    mediaId, media.getContentHash(), context, chunks, plan.tasks(), req, userId);

            // 记录本轮定向请求中「视频里不存在」的时间戳，后续轮次不再重复请求
            for (Long ts : req) {
                if (chunks.stream().noneMatch(c -> ts >= c.startTime() && ts < c.endTime())) {
                    requestedUnavailable.add(ts);
                }
            }

            AnalysisResult result = loadOr(mediaId, goalKey + "-executor-" + r, AnalysisResult.class, () -> {
                events.publish(mediaId, "AGENT_EXECUTE", Map.of("round", r, "evidenceItems", pack.items().size()));
                long t0 = System.currentTimeMillis();
                AnalysisResult res = executor.execute(model, plan, pack, profile, fb);
                telemetry.stage("executor-r" + r, System.currentTimeMillis() - t0, 1, est(res));
                meter.add(res);
                return res;
            });
            lastResult = result;

            // 阶段五：L1 原文保真 + L2 语义蕴含（每轮轻量模式，供 Critic 参考）
            VerificationReport roundVerification;
            try {
                long t0 = System.currentTimeMillis();
                roundVerification = verificationService.verify(result, context, model, false);
                telemetry.stage("verification-r" + r, System.currentTimeMillis() - t0, 0, est(roundVerification));
            } catch (Exception ve) {
                log.warn("证据验证失败（本轮跳过）: {}", ve.getMessage());
                roundVerification = null;
            }

            final VerificationReport rv = roundVerification;
            CriticResult critique = loadOr(mediaId, goalKey + "-critic-" + r, CriticResult.class, () -> {
                events.publish(mediaId, "AGENT_CRITIC", Map.of("round", r));
                long t0 = System.currentTimeMillis();
                CriticResult cr = critic.critique(model, plan, result, pack, profile, rv);
                telemetry.stage("critic-r" + r, System.currentTimeMillis() - t0, 1, est(cr));
                meter.add(cr);
                return cr;
            });
            lastCriticFeedback = String.join("；", critique.feedback());
            events.publish(mediaId, "AGENT_CRITIC_RESULT", Map.of("round", r, "passed", critique.passed(),
                    "missing", critique.missingRequirements().size(), "unsupported", critique.unsupportedClaims().size()));

            if (critique.passed() || r >= MAX_ROUNDS) {
                String warning = null;
                if (!critique.passed()) {
                    warning = "达到轮次上限(" + (MAX_ROUNDS + 1) + ")，Critic 未通过：" + lastCriticFeedback;
                }
                AnalysisResult finalResult = result.withWarning(warning);
                checkpointService.save(mediaId, goalKey + "-final", "DONE", finalResult);
                // 阶段五：终轮完整验证报告（含 LLM 蕴含判定 + L3 独立复核）
                long tv0 = System.currentTimeMillis();
                VerificationReport finalReport = verificationService.finalize(finalResult, context, model);
                telemetry.stage("verification-final", System.currentTimeMillis() - tv0,
                        finalReport.verdicts().size() + 1, est(finalReport));
                checkpointService.save(mediaId, goalKey + "-verification", "DONE", finalReport);
                events.publish(mediaId, "AGENT_COMPLETED", Map.of("rounds", r + 1, "tokensEstimate", meter.estimate(),
                        "supported", finalReport.supportedConclusions(), "unsupported", finalReport.unsupportedConclusions(),
                        "hallucinationRate", finalReport.hallucinationRate()));
                log.info("Agent 完成 mediaId={} mode={} rounds={} tokens≈{} passed={} 验证[支持{} 幻觉{} 率{}]",
                        mediaId, mode, r + 1, meter.estimate(), critique.passed(),
                        finalReport.supportedConclusions(), finalReport.unsupportedConclusions(),
                        finalReport.hallucinationRate());

                // 阶段六：可观测 trace + 多维评估落 Checkpoint
                saveTelemetryAndEvaluation(mediaId, goal, goalKey, finalResult, context, finalReport, model, userId);
                return finalResult;
            }
            feedback = String.join("\n", critique.feedback());
            requiredTimestamps = critique.requiredTimestamps();
            events.publish(mediaId, "AGENT_ROUND", Map.of("round", r + 1, "feedback", critique.feedback().size()));
            round++;
        }

        // 预算超限兜底：保留最近一次草稿 + 警告
        AnalysisResult fallback = lastResult == null
                ? AnalysisResult.of("视频分析", List.of("执行预算超限，未能产出完整结果"), List.of(), List.of(), List.of(),
                        "Agent 执行预算超限")
                : lastResult.withWarning("Agent 执行预算超限，结果可能不完整");
        checkpointService.save(mediaId, goalKey + "-final", "DONE", fallback);
        VerificationReport emptyReport = VerificationReport.of(List.of(), "未生成（预算超限）");
        saveTelemetryAndEvaluation(mediaId, goal, goalKey, fallback, context, emptyReport, model, userId);
        events.publish(mediaId, "AGENT_COMPLETED", Map.of("budgetExhausted", true, "tokensEstimate", meter.estimate()));
        return fallback;
    }

    /** 落 telemetry + evaluation Checkpoint。 */
    private void saveTelemetryAndEvaluation(Long mediaId, String goal, String goalKey, AnalysisResult result,
                                            VideoContext context, VerificationReport report,
                                            LlmClient model, Long userId) {
        AgentTelemetry.RunTrace trace = telemetry.end();
        if (trace == null) {
            trace = new AgentTelemetry.RunTrace(goalKey, List.of());
        }
        checkpointService.save(mediaId, goalKey + "-telemetry", "DONE", trace);
        MediaFile media = mediaFileRepository.findById(mediaId).orElse(null);
        EvaluationReport evaluation = evaluationService.evaluate(mediaId,
                media == null ? null : media.getContentHash(), goal, goalKey,
                result, context, report, trace, userId);
        checkpointService.save(mediaId, goalKey + "-evaluation", "DONE", evaluation);
        log.info("评估完成 mediaId={} 指标={}", mediaId, evaluation.metrics());
    }

    /** 目标级 Checkpoint 键（方案 §9.3）：goalDigest(goal, mode)。 */
    public static String goalKey(String goal, AnalysisMode mode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((goal + "|" + mode).getBytes(StandardCharsets.UTF_8));
            return "goal-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "goal-" + Integer.toHexString(goal.hashCode());
        }
    }

    private <T> T loadOr(Long mediaId, String name, Class<T> type, Supplier<T> compute) {
        Optional<T> cached = checkpointService.load(mediaId, name, type);
        if (cached.isPresent()) {
            return cached.get();
        }
        T value = compute.get();
        checkpointService.save(mediaId, name, "DONE", value);
        return value;
    }

    private static String overview(List<VideoChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (VideoChunk c : chunks) {
            sb.append(String.format("分块%d [%dms~%dms]: %s 关键词=%s%n", ++i, c.startTime(), c.endTime(),
                    trim(c.segmentSummary(), 150), String.join(",", c.keywords())));
        }
        return sb.toString();
    }

    private static String trim(String s, int cap) {
        return s == null ? "" : (s.length() > cap ? s.substring(0, cap) : s);
    }

    /** Token 粗估（1 字符 ≈ 0.7 token）。 */
    private static long est(Object o) {
        return o == null ? 0 : Math.max(1, (long) (o.toString().length() * 0.7));
    }

    /** Token 估算（中文按 1 字 ≈ 0.7 token 粗估）。 */
    private static final class TokenMeter {
        private long estimate = 0;

        void add(AnalysisResult result) {
            estimate += est(result.toString());
        }

        void add(CriticResult critique) {
            estimate += est(critique.toString());
        }

        long estimate() {
            return estimate;
        }

        private static long est(String s) {
            return s == null ? 0 : Math.max(1, (long) (s.length() * 0.7));
        }
    }
}

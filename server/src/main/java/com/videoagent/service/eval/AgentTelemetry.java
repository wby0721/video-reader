package com.videoagent.service.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 阶段可观测追踪（方案 §6.5）：记录每个角色阶段的耗时、LLM 调用次数与 Token 估算，
 * 形成完整 trace（线程内累积，AgentLoopService 使用）。
 */
@Component
public class AgentTelemetry {

    private final ThreadLocal<RunTrace> current = new ThreadLocal<>();

    /** 开始一次 Agent 运行追踪。 */
    public RunTrace begin(String goalKey) {
        RunTrace trace = new RunTrace(goalKey, new ArrayList<>());
        current.set(trace);
        return trace;
    }

    /** 记录一个阶段的耗时与 LLM 调用。 */
    public void stage(String name, long durationMs, int llmCalls, long tokensEstimate) {
        RunTrace trace = current.get();
        if (trace != null) {
            trace.stages().add(new StageTrace(name, durationMs, llmCalls, tokensEstimate));
        }
    }

    /** 结束追踪并返回。 */
    public RunTrace end() {
        RunTrace trace = current.get();
        current.remove();
        return trace;
    }

    /** 一次 Agent 运行的完整 trace。 */
    public record RunTrace(String goalKey, List<StageTrace> stages) {
        public long totalDurationMs() {
            return stages.stream().mapToLong(StageTrace::durationMs).sum();
        }

        public int totalLlmCalls() {
            return stages.stream().mapToInt(StageTrace::llmCalls).sum();
        }

        public long totalTokensEstimate() {
            return stages.stream().mapToLong(StageTrace::tokensEstimate).sum();
        }
    }

    /** 单阶段追踪。 */
    public record StageTrace(String stage, long durationMs, int llmCalls, long tokensEstimate) {}
}

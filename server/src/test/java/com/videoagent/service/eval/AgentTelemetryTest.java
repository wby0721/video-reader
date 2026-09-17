package com.videoagent.service.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTelemetryTest {

    @Test
    void countsOnlyRetrievalStagesThatActuallyMissedSessionCache() {
        AgentTelemetry telemetry = new AgentTelemetry();
        telemetry.begin("goal-key");

        telemetry.retrievalStage("retrieval-r0", 30, 3);
        telemetry.retrievalStage("retrieval-r1", 2, 0);
        telemetry.retrievalStage("retrieval-r2", 15, 1);
        telemetry.stage("executor-r0", 100, 1, 200);

        AgentTelemetry.RunTrace trace = telemetry.end();

        assertThat(trace).isNotNull();
        assertThat(trace.totalRetrievalBatches()).isEqualTo(2);
        assertThat(trace.totalLlmCalls()).isEqualTo(1);
        assertThat(trace.stages()).extracting(AgentTelemetry.StageTrace::stage)
                .containsExactly("retrieval-r0", "retrieval-r1", "retrieval-r2", "executor-r0");
    }
}

package com.videoagent.service.retrieval;

import com.videoagent.dto.RetrievalTrace;

import java.util.List;

/** 统一检索内核输出；denseSource 仅用于诊断降级链路。 */
public record RetrievalResult(
        List<RetrievalCandidate> candidates,
        String denseSource,
        RetrievalTrace trace
) {
    public RetrievalResult(List<RetrievalCandidate> candidates, String denseSource) {
        this(candidates, denseSource, null);
    }
}

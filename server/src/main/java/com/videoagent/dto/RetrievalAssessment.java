package com.videoagent.dto;

/** Public, score-free diagnostics; relevance is not a guarantee of answerability. */
public record RetrievalAssessment(Status status, boolean degraded, String hint) {
    public enum Status { CANDIDATE_EVIDENCE, LOW_RELEVANCE, NO_EVIDENCE, DEGRADED }
}

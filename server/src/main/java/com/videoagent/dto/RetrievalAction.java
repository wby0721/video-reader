package com.videoagent.dto;

/** Critic 对下一轮证据处理的结构化动作。 */
public enum RetrievalAction {
    REUSE,
    ADD_TIMESTAMP,
    SEARCH;

    public static RetrievalAction parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

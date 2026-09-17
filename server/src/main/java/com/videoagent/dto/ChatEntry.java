package com.videoagent.dto;

import java.util.List;

/**
 * 聊天记录条目（持久化到 Checkpoint「media-chat」）。
 *
 * @param role     user / assistant
 * @param content  消息文本
 * @param ts       时间戳（毫秒）
 * @param evidence     内部精排命中，保留用于旧记录兼容和检索诊断
 * @param evidencePack 回答提示词实际使用的合并原文证据；其顺序与回答中的[证据N]严格一致
 */
public record ChatEntry(
        String role,
        String content,
        long ts,
        List<EvidenceHit> evidence,
        RetrievalAssessment retrieval,
        List<ChatEvidence> evidencePack,
        RetrievalTrace retrievalTrace
) {
    public ChatEntry(String role, String content, long ts, List<EvidenceHit> evidence) {
        this(role, content, ts, evidence, null, List.of(), null);
    }

    public ChatEntry(String role, String content, long ts, List<EvidenceHit> evidence,
                     RetrievalAssessment retrieval) {
        this(role, content, ts, evidence, retrieval, List.of(), null);
    }

    public ChatEntry(String role, String content, long ts, List<EvidenceHit> evidence,
                     RetrievalAssessment retrieval, List<ChatEvidence> evidencePack) {
        this(role, content, ts, evidence, retrieval, evidencePack, null);
    }
}

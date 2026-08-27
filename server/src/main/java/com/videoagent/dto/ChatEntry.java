package com.videoagent.dto;

import java.util.List;

/**
 * 聊天记录条目（持久化到 Checkpoint「media-chat」）。
 *
 * @param role     user / assistant
 * @param content  消息文本
 * @param ts       时间戳（毫秒）
 * @param evidence assistant 回答所依据的检索证据片段（Top-N 摘要），供可信度 trace 展示
 */
public record ChatEntry(
        String role,
        String content,
        long ts,
        List<EvidenceHit> evidence
) {}

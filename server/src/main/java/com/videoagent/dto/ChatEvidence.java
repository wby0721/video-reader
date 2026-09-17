package com.videoagent.dto;

import java.util.List;

/** 连续追问回答使用的原文证据区间。 */
public record ChatEvidence(
        List<String> chunkIds,
        Long mediaId,
        long startMs,
        long endMs,
        String quote,
        List<String> ocrTexts
) {}
